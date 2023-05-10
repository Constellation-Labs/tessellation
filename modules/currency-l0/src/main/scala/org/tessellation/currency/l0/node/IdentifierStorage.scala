package org.tessellation.currency.l0.node

import cats.effect.kernel.{Async, Ref}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, MonadThrow}

import org.tessellation.schema.address.Address

trait IdentifierStorage[F[_]] {
  def setInitial(address: Address): F[Unit]
  def get: F[Address]
}

object IdentifierStorage {
  def make[F[_]: Async: Ref.Make]: F[IdentifierStorage[F]] =
    Ref.of[F, Option[Address]](None).map(make(_))

  def make[F[_]: MonadThrow](identifierR: Ref[F, Option[Address]]): IdentifierStorage[F] =
    new IdentifierStorage[F] {
      def setInitial(address: Address): F[Unit] =
        identifierR.modify {
          case None => (address.some, Applicative[F].unit)
          case other =>
            (
              other,
              MonadThrow[F].raiseError[Unit](new Throwable(s"Failure setting initial identifier! Encountered non empty "))
            )
        }.flatten

      def get: F[Address] =
        identifierR.get.flatMap {
          case None          => MonadThrow[F].raiseError[Address](new Throwable(s"Identifier not set! Encountered attempt to read it"))
          case Some(address) => Applicative[F].pure(address)
        }
    }
}
