package org.tessellation.currency.l0.snapshot.storages

import cats.Functor
import cats.effect.kernel.Ref
import cats.syntax.functor._

import org.tessellation.security.hash.Hash

trait LastSignedBinaryHashStorage[F[_]] {
  def set(hash: Hash): F[Unit]

  def get: F[Hash]
}

object LastSignedBinaryHashStorage {

  def make[F[_]: Ref.Make: Functor]: F[LastSignedBinaryHashStorage[F]] = Ref.of[F, Hash](Hash.empty).map { lastHashR =>
    new LastSignedBinaryHashStorage[F] {
      def set(hash: Hash): F[Unit] = lastHashR.set(hash)

      def get: F[Hash] = lastHashR.get
    }

  }
}
