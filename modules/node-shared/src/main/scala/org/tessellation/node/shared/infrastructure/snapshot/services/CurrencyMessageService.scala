package org.tessellation.node.shared.infrastructure.snapshot.services

import cats.Functor
import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.infrastructure.snapshot.services.CurrencyMessageService.CurrencyMessageServiceErrorOr
import org.tessellation.schema.currencyMessage.{CurrencyMessage, MessageType}
import org.tessellation.security.signature.SignedValidator.{SignedValidationError, SignedValidationErrorOr}
import org.tessellation.security.signature.{Signed, SignedValidator}

import derevo.cats.{eqv, show}
import derevo.derive

trait CurrencyMessageService[F[_]] {
  def validate(message: Signed[CurrencyMessage]): F[CurrencyMessageServiceErrorOr[Signed[CurrencyMessage]]]
}

object CurrencyMessageService {

  def make[F[_]: Functor](
    validator: SignedValidator[F],
    seedlist: Option[Set[SeedlistEntry]],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): CurrencyMessageService[F] =
    new CurrencyMessageService[F] {
      val peers = seedlist.map(_.map(_.peerId))

      def validate(message: Signed[CurrencyMessage]): F[CurrencyMessageServiceErrorOr[Signed[CurrencyMessage]]] =
        snapshotStorage.head.map {
          case None => SnapshotNotFound.invalidNec
          case Some((_, info)) =>
            info.lastMessages
              .getOrElse(SortedMap.empty[MessageType, Signed[CurrencyMessage]])
              .get(message.messageType) match {
              case Some(lastMessage) if message.parentOrdinal < lastMessage.ordinal =>
                ParentOrdinalBelowLastMessageOrdinal.invalidNec
              case _ => mapError(validator.validateSignedBySeedlistMajority(peers, message))
            }

        }

      private def mapError(
        validated: SignedValidationErrorOr[Signed[CurrencyMessage]]
      ): CurrencyMessageServiceErrorOr[Signed[CurrencyMessage]] =
        validated match {
          case Invalid(e) => Invalid(e.map(ValidationError(_)))
          case Valid(cm)  => cm.validNec
        }
    }

  @derive(eqv, show)
  sealed trait CurrencyMessageServiceError
  case class ValidationError(error: SignedValidationError) extends CurrencyMessageServiceError
  case object SnapshotNotFound extends CurrencyMessageServiceError
  case object ParentOrdinalBelowLastMessageOrdinal extends CurrencyMessageServiceError

  type CurrencyMessageServiceErrorOr[A] = ValidatedNec[CurrencyMessageServiceError, A]
}
