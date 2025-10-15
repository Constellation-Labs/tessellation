package io.constellationnetwork.dag.l1

import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.dag.l1.modules.{Services, Storages}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}

import org.typelevel.log4cats.Logger
import retry.RetryPolicies
import retry.implicits.retrySyntaxError

object StoragesInitializer {

  private def pullGlobalSnapshotWithRetry[F[_]: Async: Logger](
    globalL0Service: GlobalL0Service[F]
  ) = {
    val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))
    globalL0Service.pullLatestSnapshotFromRandomPeer
      .retryingOnAllErrors(
        policy = retryPolicy,
        onError = (err, retryDetails) =>
          Logger[F].error(err)(s"Failed to fetch incremental global snapshot (attempt ${retryDetails.retriesSoFar + 1}/5)")
      )
  }

  def initializeStorages[
    F[_]: Async: Logger,
    R <: CliMethod
  ](
    storages: Storages[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    sharedStorages: SharedStorages[F],
    services: Services[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo, R]
  ): F[Unit] =
    pullGlobalSnapshotWithRetry(services.globalL0).flatMap { globalSnapshotCombined =>
      val (snapshot, state) = globalSnapshotCombined
      val ordinal = snapshot.ordinal
      for {
        _ <- Logger[F].info(s"Starting storage initialization with ordinal=$ordinal")

        _ <- Logger[F].info(s"Initializing lastNGlobalSnapshot shared storage with ordinal=$ordinal")
        _ <- sharedStorages.lastNGlobalSnapshot.setInitialFetchingGL0(
          snapshot,
          state,
          services.globalL0.asLeft.some,
          none
        )
        _ <- Logger[F].info(s"Successfully initialized lastNGlobalSnapshot shared storage")

        _ <- Logger[F].info(s"Initializing lastGlobalSnapshot shared storage with ordinal=$ordinal")
        _ <- sharedStorages.lastGlobalSnapshot.setInitial(
          snapshot,
          state
        )
        _ <- Logger[F].info(s"Successfully initialized lastGlobalSnapshot shared storage")

        _ <- Logger[F].info(s"Storage initialization completed successfully with ordinal=$ordinal")
      } yield ()
    }
}
