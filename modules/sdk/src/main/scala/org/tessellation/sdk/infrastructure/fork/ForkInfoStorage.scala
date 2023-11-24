package org.tessellation.sdk.infrastructure.fork

import cats.Monad
import cats.effect.Ref
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.ForkInfoStorageConfig
import org.tessellation.sdk.domain.fork._

import eu.timepit.refined.auto._

object ForkInfoStorage {

  def make[F[_]: Monad: Ref.Make](config: ForkInfoStorageConfig): F[ForkInfoStorage[F]] =
    Ref[F]
      .of(ForkInfoMap.empty)
      .map(make(_, config))

  def make[F[_]: Monad](
    storeRef: Ref[F, ForkInfoMap],
    config: ForkInfoStorageConfig
  ): ForkInfoStorage[F] = new ForkInfoStorage[F] {
    def add(peerId: PeerId, entry: ForkInfo): F[Unit] = storeRef.update { s =>
      val updated = s.forks.updatedWith(peerId) { maybeEntries =>
        maybeEntries
          .getOrElse(ForkInfoEntries(config.maxSize))
          .add(entry)
          .some
      }

      ForkInfoMap(updated)
    }

    def getForkInfo: F[ForkInfoMap] = storeRef.get
  }

}
