package io.constellationnetwork.dag.l0

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l0.modules.{Programs, Storages}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.{Hashed, Hasher}

import org.typelevel.log4cats.Logger

object StoragesInitializer {
  def initializeStorages[
    F[_]: Async: Logger: Hasher
  ](
    storages: Storages[F],
    sharedStorages: SharedStorages[F],
    programs: Programs[F],
    hashedGlobalIncrementalSnapshot: Hashed[GlobalIncrementalSnapshot],
    globalSnapshotInfo: GlobalSnapshotInfo
  ): F[Unit] = {
    val ordinal = hashedGlobalIncrementalSnapshot.ordinal

    for {
      _ <- Logger[F].info(s"Starting storage initialization with ordinal=$ordinal")

      _ <- Logger[F].info(s"Initializing globalSnapshot storage with ordinal=$ordinal")
      _ <- storages.globalSnapshot.prepend(hashedGlobalIncrementalSnapshot.signed, globalSnapshotInfo)
      _ <- Logger[F].info(s"Successfully initialized globalSnapshot storage")

      _ <- Logger[F].info(s"Initializing lastNGlobalSnapshot shared storage with ordinal=$ordinal")
      _ <- sharedStorages.lastNGlobalSnapshot.setInitialFetchingGL0(
        hashedGlobalIncrementalSnapshot,
        globalSnapshotInfo,
        none,
        Some((hash, ordinal) => programs.download.fetchSnapshot(hash, ordinal))
      )
      _ <- Logger[F].info(s"Successfully initialized lastNGlobalSnapshot shared storage")

      _ <- Logger[F].info(s"Initializing lastGlobalSnapshot storage with ordinal=$ordinal")
      _ <- sharedStorages.lastGlobalSnapshot.setInitial(
        hashedGlobalIncrementalSnapshot,
        globalSnapshotInfo
      )
      _ <- Logger[F].info(s"Successfully initialized lastGlobalSnapshot storage")

      _ <- Logger[F].info(s"Storage initialization completed successfully with ordinal=$ordinal")
    } yield ()
  }
}
