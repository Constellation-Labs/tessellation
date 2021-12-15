package org.tessellation.ext.collection

import cats.effect.{Ref, Sync}
import cats.syntax.functor._

object SetRefUtils {
  implicit class RefOps[F[_]: Sync, K, V](val ref: Ref[F, Set[K]]) {

    def add(k: K): F[Unit] =
      ref.update(_ + k)

    def remove(k: K): F[Unit] =
      ref.update(_ - k)

    def exists(k: K): F[Boolean] =
      ref.get.map(_.contains(k))
  }
}
