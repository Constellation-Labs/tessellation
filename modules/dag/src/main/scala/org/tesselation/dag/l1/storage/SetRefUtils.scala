package org.tesselation.dag.l1.storage

import cats.effect.{Ref, Sync}
import cats.syntax.functor._

object SetRefUtils {
  implicit class RefOps[F[_]: Sync, K, V](val ref: Ref[F, Set[K]]) {

    def add(k: K): F[Unit] =
      ref.modify(a => (a + k, ()))

    def remove(k: K): F[Unit] =
      ref.modify(a => (a - k, ()))

    def exists(k: K): F[Boolean] =
      ref.get.map(_.contains(k))
  }
}
