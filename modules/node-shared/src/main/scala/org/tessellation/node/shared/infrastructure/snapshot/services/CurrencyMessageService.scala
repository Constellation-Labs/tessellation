package org.tessellation.node.shared.infrastructure.snapshot.services

import cats.data.Validated.{Invalid, Valid}
import cats.data.ValidatedNec
import cats.effect.Async
import cats.syntax.functor._
import cats.syntax.validated._

import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.infrastructure.snapshot.services.CurrencyMessageService.CurrencyMessageServiceErrorOr
import org.tessellation.node.shared.infrastructure.snapshot.storage.CurrencyMessageStorage
import org.tessellation.schema.currencyMessage.CurrencyMessage
import org.tessellation.security.signature.SignedValidator.{SignedValidationError, SignedValidationErrorOr}
import org.tessellation.security.signature.{Signed, SignedValidator}
import org.tessellation.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.derive

trait CurrencyMessageService[F[_]] {
  def offer(message: Signed[CurrencyMessage]): F[CurrencyMessageServiceErrorOr[Unit]]
}

object CurrencyMessageService {

  def make[F[_]: Async: SecurityProvider: Hasher](
    validator: SignedValidator[F],
    seedlist: Option[Set[SeedlistEntry]],
    storage: CurrencyMessageStorage[F]
  ): CurrencyMessageService[F] =
    new CurrencyMessageService[F] {
      val peers = seedlist.map(_.map(_.peerId))

      def offer(message: Signed[CurrencyMessage]): F[CurrencyMessageServiceErrorOr[Unit]] =
        validator
          .validateSignedBySeedlistMajority(peers, message)
          .traverse(_ => storage.set(message))
          .map(mapError)

      private def mapError(validated: SignedValidationErrorOr[Boolean]): CurrencyMessageServiceErrorOr[Unit] =
        validated match {
          case Invalid(e)   => Invalid(e.map(ValidationError(_)))
          case Valid(false) => OlderOrdinalError.invalidNec
          case Valid(true)  => ().validNec
        }
    }

  @derive(eqv, show)
  sealed trait CurrencyMessageServiceError
  case class ValidationError(error: SignedValidationError) extends CurrencyMessageServiceError
  case object OlderOrdinalError extends CurrencyMessageServiceError

  type CurrencyMessageServiceErrorOr[A] = ValidatedNec[CurrencyMessageServiceError, A]
}
