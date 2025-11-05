package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.Order
import cats.data.Validated
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.getFeeAddresses
import io.constellationnetwork.node.shared.infrastructure.snapshot.{CurrencyMessageValidator, GlobalSnapshotSyncValidator}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class MessageValidationOpsManager[F[_]: Async](
  messageValidator: CurrencyMessageValidator[F],
  globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F]
) {
  val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("MessageValidationOps")

  def acceptMessages(
    lastContextMessages: Option[SortedMap[MessageType, Signed[CurrencyMessage]]],
    messagesForAcceptance: List[Signed[CurrencyMessage]],
    metagraphId: Address,
    snapshotOrdinal: SnapshotOrdinal,
    lastGlobalSnapshotBalances: SortedMap[Address, Balance],
    lastCurrencySnapshots: SortedMap[Address, Either[Signed[
      CurrencySnapshot
    ], (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]],
    shouldPerformMetagraphSpecificValidations: Boolean
  )(implicit hs: Hasher[F]): F[CurrencyMessagesAcceptanceResult] = {
    val msgOrdering = Order
      .whenEqual[Signed[CurrencyMessage]](
        Order.whenEqual(Order.by(_.parentOrdinal), Order.reverse(Order.by(_.proofs.size))),
        Order[Signed[CurrencyMessage]]
      )
      .toOrdering

    messagesForAcceptance
      .sorted(msgOrdering)
      .foldLeftM(
        (
          lastContextMessages.getOrElse(SortedMap.empty[MessageType, Signed[CurrencyMessage]]),
          List.empty[Signed[CurrencyMessage]],
          List.empty[Signed[CurrencyMessage]]
        )
      ) {
        case ((lastMsgs, toAdd, toReject), message) =>
          val allFeesAddresses = getFeeAddresses(lastCurrencySnapshots)
          val balance = lastGlobalSnapshotBalances.getOrElse(message.address, Balance.empty)

          // We should call the validateInitialOwner if the ordinal is 2 and it's the first message
          val validationResult =
            if (snapshotOrdinal === SnapshotOrdinal.unsafeApply(2L) && message.parentOrdinal === MessageOrdinal.MinValue) {
              messageValidator.validateInitialOwner(message, metagraphId, allFeesAddresses, shouldPerformMetagraphSpecificValidations)
            } else {
              messageValidator.validate(message, lastMsgs, metagraphId, allFeesAddresses, shouldPerformMetagraphSpecificValidations)
            }

          validationResult.flatMap {
            case Validated.Valid(_) =>
              val updatedLastMsgs = lastMsgs.updated(message.messageType, message)
              val updatedToAdd = message :: toAdd

              logger.info(
                s"Message accepted - " +
                  s"Address: ${message.address}, " +
                  s"MessageType: ${message.messageType}, " +
                  s"ParentOrdinal: ${message.parentOrdinal}, " +
                  s"ProofCount: ${message.proofs.size}, " +
                  s"Balance: ${balance.value}"
              ) >> (updatedLastMsgs, updatedToAdd, toReject).pure[F]

            case Validated.Invalid(errors) =>
              val updatedToReject = message :: toReject

              logger.warn(
                s"Message rejected - " +
                  s"Address: ${message.address}, " +
                  s"MessageType: ${message.messageType}, " +
                  s"ParentOrdinal: ${message.parentOrdinal}, " +
                  s"ProofCount: ${message.proofs.size}, " +
                  s"Balance: ${balance.value}, " +
                  s"Errors: ${errors.toList.mkString(", ")}"
              ) >> (lastMsgs, toAdd, updatedToReject).pure[F]
          }
      }
      .flatTap {
        case (_, toAdd, toReject) =>
          logger
            .info(
              s"Message acceptance complete - " +
                s"Total processed: ${messagesForAcceptance.size}, " +
                s"Accepted: ${toAdd.size}, " +
                s"Rejected: ${toReject.size}"
            )
            .whenA(messagesForAcceptance.nonEmpty)
      }
      .map {
        case (contextUpdate, toAdd, toReject) =>
          CurrencyMessagesAcceptanceResult(contextUpdate, toAdd, toReject)
      }
  }

  def acceptGlobalSnapshotSyncs(
    lastGlobalSnapshotSyncView: Option[SortedMap[PeerId, Signed[GlobalSnapshotSync]]],
    globalSnapshotSyncsForAcceptance: List[Signed[GlobalSnapshotSync]],
    metagraphId: Address,
    facilitators: Set[PeerId]
  )(implicit hs: Hasher[F]): F[GlobalSnapshotSyncAcceptanceResult] = {
    val ordering = Order
      .whenEqual[Signed[GlobalSnapshotSync]](
        Order.by(_.parentOrdinal),
        Order[Signed[GlobalSnapshotSync]]
      )
      .toOrdering

    globalSnapshotSyncsForAcceptance
      .sorted(ordering)
      .foldLeftM(
        (
          lastGlobalSnapshotSyncView.getOrElse(SortedMap.empty[PeerId, Signed[GlobalSnapshotSync]]),
          List.empty[Signed[GlobalSnapshotSync]],
          List.empty[Signed[GlobalSnapshotSync]]
        )
      ) {
        case ((lastSyncs, toAdd, toReject), sync) =>
          globalSnapshotSyncValidator.validate(sync, metagraphId, facilitators, lastSyncs).map {
            case Validated.Valid(_) =>
              val peerId = sync.proofs.head.id.toPeerId
              val updatedLastSyncs = lastSyncs.updated(peerId, sync)
              val updatedToAdd = sync :: toAdd

              (updatedLastSyncs, updatedToAdd, toReject)
            case Validated.Invalid(_) =>
              val updatedToReject = sync :: toReject

              (lastSyncs, toAdd, updatedToReject)
          }
      }
      .map { case (contextUpdate, toAdd, toReject) => GlobalSnapshotSyncAcceptanceResult(contextUpdate, toAdd, toReject) }
  }
}

object MessageValidationOpsManager {
  def make[F[_]: Async](
    messageValidator: CurrencyMessageValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F]
  ): MessageValidationOpsManager[F] =
    new MessageValidationOpsManager[F](messageValidator, globalSnapshotSyncValidator)
}
