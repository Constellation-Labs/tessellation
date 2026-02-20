package io.constellationnetwork.currency.l0.infrastructure.snapshot.services

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.cli.MetagraphOwnerMessageOpts.MetagraphOwnerMessagePath
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelValidator.getFeeAddresses
import io.constellationnetwork.node.shared.infrastructure.currencyMessage.CurrencyMessageLoader
import io.constellationnetwork.node.shared.infrastructure.snapshot.CurrencyMessageValidator
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.node.shared.snapshot.currency.{CurrencyMessageEvent, CurrencySnapshotEvent}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.currencyMessage.{CurrencyMessage, MessageType}
import io.constellationnetwork.schema.http.ErrorCause
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

trait CurrencyMessagesService[F[_]] {
  def setInitialCurrencyOwner(ownerMessagePath: Option[MetagraphOwnerMessagePath]): F[Option[CurrencySnapshotEvent]]
}

object CurrencyMessagesService {
  def make[F[_]: Async: Hasher](
    mkCell: CurrencySnapshotEvent => Cell[F, StackF, _, Either[CellError, Ω], _],
    validator: CurrencyMessageValidator[F],
    identifierStorage: IdentifierStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
  ): CurrencyMessagesService[F] =
    new CurrencyMessagesService[F] {
      val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

      def currencyMessageLoader(ownerMessagePath: MetagraphOwnerMessagePath): F[Signed[CurrencyMessage]] =
        CurrencyMessageLoader.make[F].load(ownerMessagePath)

      override def setInitialCurrencyOwner(
        maybeOwnerMessagePath: Option[MetagraphOwnerMessagePath]
      ): F[Option[CurrencySnapshotEvent]] =
        maybeOwnerMessagePath match {
          case Some(ownerMessagePath) =>
            for {
              ownerMessage <- currencyMessageLoader(ownerMessagePath)
              metagraphId <- identifierStorage.get
              combinedLastGlobalSnapshot <- lastGlobalSnapshotStorage.getCombined
              allFeesAddresses = combinedLastGlobalSnapshot match {
                case Some((_, info)) => getFeeAddresses(info)
                case None            => SortedMap.empty[Address, Set[Address]]
              }
              validation <- validator.validateInitialOwner(
                ownerMessage,
                metagraphId,
                allFeesAddresses,
                shouldPerformMetagraphSpecificValidations = false
              )

              event <- validation match {
                case Invalid(errors) =>
                  val msg = errors.toNonEmptyList.toList.map(_.show).mkString(", ")
                  logger.warn(s"Message is invalid, reason: ${errors.show}") *>
                    Async[F].raiseError[CurrencySnapshotEvent](new RuntimeException(s"Invalid message: $msg"))

                case Valid(message) =>
                  val event: CurrencySnapshotEvent = CurrencyMessageEvent(message)
                  mkCell(event).run().as(event)
              }
            } yield event.some
          case None => none[CurrencySnapshotEvent].pure[F]
        }

    }
}
