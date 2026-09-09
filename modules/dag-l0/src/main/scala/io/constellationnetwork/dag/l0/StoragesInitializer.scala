package io.constellationnetwork.dag.l0

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.dag.l0.modules.{Programs, Storages}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.collateral.LatestBalances
import io.constellationnetwork.node.shared.domain.snapshot.programs.Download
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.security.{Hashed, Hasher}

import org.typelevel.log4cats.Logger

object StoragesInitializer {
  def initializeStorages[
    F[_]: Async: Logger: Hasher
  ](
    globalSnapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshotStorage: LastNGlobalSnapshotStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    download: Download[F, GlobalIncrementalSnapshot],
    hashedGlobalIncrementalSnapshot: Hashed[GlobalIncrementalSnapshot],
    globalSnapshotInfo: GlobalSnapshotInfo
  ): F[Unit] = {
    val ordinal = hashedGlobalIncrementalSnapshot.ordinal

    for {
      _ <- Logger[F].info(s"Starting storage initialization with ordinal=$ordinal")

      _ <- Logger[F].info(s"Initializing globalSnapshot storage with ordinal=$ordinal")
      _ <- globalSnapshotStorage.prepend(hashedGlobalIncrementalSnapshot.signed, globalSnapshotInfo)
      _ <- Logger[F].info(s"Successfully initialized globalSnapshot storage")

      _ <- Logger[F].info(s"Initializing lastNGlobalSnapshot shared storage with ordinal=$ordinal")
      // Fill the recent-window predecessors from local storage first, falling back to the network
      // only for ordinals genuinely absent on disk.
      //
      // Passing `none` here made the walk network-only. On testnet gl0 the rollback target's
      // predecessors were all present locally (11,271 files under the ordinal index) and it still
      // fetched them from peers; one failed fetch then raised Download$CannotFetchSnapshot out of
      // storage initialization and killed the process -- 21 restarts against perfectly intact
      // data, reported as "cannot fetch" when nothing needed fetching. `fetchSingleSnapshot`
      // already implements local-first/network-fallback for the `Right` case (SnapshotStorage.get
      // reads through to snapshotLocalFileSystemStorage), so this call site simply never supplied
      // the storage it already had in scope.
      _ <- lastNGlobalSnapshotStorage.setInitialFetchingGL0(
        hashedGlobalIncrementalSnapshot,
        globalSnapshotInfo,
        globalSnapshotStorage.asRight.some,
        Some((hash, ordinal) => download.fetchSnapshot(hash, ordinal))
      )
      _ <- Logger[F].info(s"Successfully initialized lastNGlobalSnapshot shared storage")

      _ <- Logger[F].info(s"Initializing lastGlobalSnapshot storage with ordinal=$ordinal")
      _ <- lastGlobalSnapshotStorage.setInitial(
        hashedGlobalIncrementalSnapshot,
        globalSnapshotInfo
      )
      _ <- Logger[F].info(s"Successfully initialized lastGlobalSnapshot storage")

      _ <- Logger[F].info(s"Storage initialization completed successfully with ordinal=$ordinal")
    } yield ()
  }
}
