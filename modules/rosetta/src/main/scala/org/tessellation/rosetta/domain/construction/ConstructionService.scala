package org.tessellation.rosetta.domain.construction

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.domain.error.{ConstructionError, InvalidPublicKey, MalformedTransaction}
import org.tessellation.rosetta.domain.{AccountIdentifier, RosettaPublicKey, TransactionIdentifier}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed

trait ConstructionService[F[_]] {
  def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier]
  def getTransactionIdentifier(hex: Hex): EitherT[F, ConstructionError, TransactionIdentifier]
}

object ConstructionService {
  def make[F[_]: Async: SecurityProvider: KryoSerializer](): ConstructionService[F] = new ConstructionService[F] {
    def derive(publicKey: RosettaPublicKey): EitherT[F, ConstructionError, AccountIdentifier] =
      publicKey.hexBytes
        .toPublicKeyByEC[F]
        .map(_.toAddress)
        .map(AccountIdentifier(_, None))
        .attemptT
        .leftMap(_ => InvalidPublicKey)

    def getTransactionIdentifier(hex: Hex): EitherT[F, ConstructionError, TransactionIdentifier] = KryoSerializer[F]
      .deserialize[Signed[Transaction]](hex.toBytes)
      .liftTo[F]
      .flatMap(_.toHashed[F])
      .map(_.hash)
      .map(TransactionIdentifier(_))
      .attemptT
      .leftMap(_ => MalformedTransaction)
  }
}
