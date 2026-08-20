package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.Order
import cats.data.Validated
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.getFeeAddresses
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.{
  CurrencyMessageValidator,
  GlobalSnapshotSyncValidator,
  RecoveryGlobalSnapshotSync
}
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class MessageValidationOpsManager[F[_]: Async: Metrics](
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
    facilitators: Set[PeerId],
    recoveryResetContext: Option[RecoveryGlobalSnapshotSync.ValidationContext],
    resetRecognitionEnabled: Boolean
  )(implicit hs: Hasher[F]): F[GlobalSnapshotSyncAcceptanceResult] = {
    val ordering = Order
      .whenEqual[Signed[GlobalSnapshotSync]](
        Order.by(_.parentOrdinal),
        Order[Signed[GlobalSnapshotSync]]
      )
      .toOrdering

    val inherited = lastGlobalSnapshotSyncView.getOrElse(SortedMap.empty[PeerId, Signed[GlobalSnapshotSync]])

    def recordReset(outcome: String): F[Unit] =
      Metrics[F]
        .incrementCounter(
          "dag_currency_l0_recovery_sync_refresh_total",
          Seq(
            Metrics.unsafeLabelName("mode") -> "reset",
            Metrics.unsafeLabelName("outcome") -> outcome
          )
        )
        .attempt
        .void

    def rejectAll(reason: String): F[GlobalSnapshotSyncAcceptanceResult] =
      logger.warn(s"RECOVERY_SYNC_RESET_REJECTED reason=$reason") >>
        recordReset("rejected") >>
        GlobalSnapshotSyncAcceptanceResult(inherited, List.empty, globalSnapshotSyncsForAcceptance).pure[F]

    def validateRecoveryReset(sync: Signed[GlobalSnapshotSync]): F[Boolean] = {
      val signer = sync.proofs.head.id.toPeerId

      recoveryResetContext match {
        case None =>
          logger.warn("RECOVERY_SYNC_RESET_CANDIDATE_INVALID reason=missing_consensus_context").as(false)
        case Some(context) =>
          RecoveryGlobalSnapshotSync.validateReset(signer, sync.value, context) match {
            case Left(error) =>
              logger.warn(s"RECOVERY_SYNC_RESET_CANDIDATE_INVALID reason=${error.productPrefix}").as(false)
            case Right(_) =>
              globalSnapshotSyncValidator
                .validate(sync, metagraphId, facilitators, inherited, GlobalSnapshotSyncValidator.RecoveryReset)
                .flatMap {
                  case Validated.Valid(_) => true.pure[F]
                  case Validated.Invalid(errors) =>
                    val reason = errors.toNonEmptyList.toList.map(_.getClass.getSimpleName.stripSuffix("$")).mkString("_")
                    logger.warn(s"RECOVERY_SYNC_RESET_CANDIDATE_INVALID reason=validator_$reason").as(false)
                }
          }
      }
    }

    def acceptOrdinary(
      candidates: List[Signed[GlobalSnapshotSync]]
    ): F[GlobalSnapshotSyncAcceptanceResult] =
      candidates
        .sorted(ordering)
        .foldLeftM(
          (
            inherited,
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

    if (!resetRecognitionEnabled)
      acceptOrdinary(globalSnapshotSyncsForAcceptance)
    else {
      val (resetShaped, ordinaryCandidates) = globalSnapshotSyncsForAcceptance.partition { sync =>
        val signer = sync.proofs.head.id.toPeerId
        RecoveryGlobalSnapshotSync.hasResetShape(signer, sync.parentOrdinal, inherited.keySet, facilitators)
      }

      resetShaped
        .traverse(sync => validateRecoveryReset(sync).tupleLeft(sync))
        .flatMap { classified =>
          val validResets = classified.collect { case (sync, true) => sync }
          val invalidResets = classified.collect { case (sync, false) => sync }

          acceptOrdinary(ordinaryCandidates).flatMap { ordinary =>
            validResets match {
              case reset :: Nil if ordinary.accepted.isEmpty =>
                val signer = reset.proofs.head.id.toPeerId
                logger.warn(s"RECOVERY_SYNC_RESET_ACCEPTED signer=${signer.value.value.take(8)}") >>
                  recordReset("accepted") >>
                  GlobalSnapshotSyncAcceptanceResult(
                    SortedMap(signer -> reset),
                    List(reset),
                    invalidResets ++ ordinary.notAccepted,
                    isRecoveryReset = true
                  ).pure[F]
              case Nil =>
                ordinary.copy(notAccepted = invalidResets ++ ordinary.notAccepted).pure[F]
              case _ =>
                rejectAll("competing_valid_sync_declarations")
            }
          }
        }
    }
  }
}

object MessageValidationOpsManager {
  def make[F[_]: Async: Metrics](
    messageValidator: CurrencyMessageValidator[F],
    globalSnapshotSyncValidator: GlobalSnapshotSyncValidator[F]
  ): MessageValidationOpsManager[F] =
    new MessageValidationOpsManager[F](messageValidator, globalSnapshotSyncValidator)
}
