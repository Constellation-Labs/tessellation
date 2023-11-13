package org.tessellation.currency.l0.snapshot.storages

import cats.Functor
import cats.effect.kernel.Ref
import cats.syntax.functor._

import org.tessellation.security.hash.Hash

trait LastBinaryHashStorage[F[_]] {
  def set(hash: Hash): F[Unit]

  def get: F[Hash]
}

object LastBinaryHashStorage {

  def make[F[_]: Ref.Make: Functor]: F[LastBinaryHashStorage[F]] = Ref.of[F, Hash](Hash.empty).map { lastHashR =>
    new LastBinaryHashStorage[F] {
      def set(hash: Hash): F[Unit] = lastHashR.set(hash)

      def get: F[Hash] = lastHashR.get
    }

  }
}
