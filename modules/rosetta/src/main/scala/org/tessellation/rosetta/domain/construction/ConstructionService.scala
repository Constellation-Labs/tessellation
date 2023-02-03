package org.tessellation.rosetta.domain.construction

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.functor._

import org.tessellation.rosetta.domain.error.{ConstructionError, InvalidPublicKey}
import org.tessellation.rosetta.domain.{AccountIdentifier, RosettaPublicKey}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops._

trait ConstructionService[F[_]] {
  def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier]
}

object ConstructionService {
  def make[F[_]: Async: SecurityProvider](): ConstructionService[F] = new ConstructionService[F] {
    def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier] =
      publicKey.hexBytes
        .toPublicKeyByEC[F]
        .map(_.toAddress)
        .map(AccountIdentifier(_, None))
        .attemptT
        .leftMap(_ => InvalidPublicKey)
  }
}
