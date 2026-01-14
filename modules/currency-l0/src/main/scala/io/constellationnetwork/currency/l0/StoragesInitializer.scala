package io.constellationnetwork.currency.l0

import cats.effect.Async
import cats.syntax.all._
import cats.{MonadThrow, Parallel}

import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.modules.{Services, Storages}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

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

  def initializeGlobalSnapshotStorages[
    F[_]: Async: Logger: Parallel: Hasher,
    R <: CliMethod
  ](
    services: Services[F, R],
    storages: Storages[F],
    sharedStorages: SharedStorages[F]
  )(
    implicit stateProofSelector: GlobalStateProofSelector
  ) =
    pullGlobalSnapshotWithRetry(services.globalL0).flatMap { globalSnapshotCombined =>
      val (snapshot, state) = globalSnapshotCombined
      val ordinal = snapshot.ordinal
      for {
        _ <- Logger[F].info(s"Initializing global snapshot storages with ordinal=$ordinal")

        _ <- Logger[F].info(s"Initializing lastSyncGlobalSnapshot storage with ordinal=$ordinal")
        _ <- storages.lastSyncGlobalSnapshot.setInitial(snapshot, state)
        _ <- Logger[F].info(s"Successfully initialized lastSyncGlobalSnapshot storage")

        _ <- Logger[F].info(s"Initializing lastNGlobalSnapshot shared storage with ordinal=$ordinal")
        _ <- sharedStorages.lastNGlobalSnapshot.setInitialFetchingGL0(
          snapshot,
          state,
          services.globalL0.asLeft.some,
          none
        )
        _ <- Logger[F].info(s"Successfully initialized lastNGlobalSnapshot shared storage")

        _ <- Logger[F].info(s"Writing global snapshot to file storage with ordinal=$ordinal")
        _ <- storages.globalSnapshotsWithStateFileStorage.write(snapshot.ordinal, GlobalSnapshotWithState(snapshot.signed, state))
        _ <- Logger[F].info(s"Successfully wrote to globalSnapshotsWithStateFileStorage")

        _ <- Logger[F].info(s"Writing global snapshot deltas to file storage with ordinal=$ordinal")
        _ <- storages.globalSnapshotsWithStateDeltasFileStorage
          .write(snapshot.ordinal, GlobalSnapshotWithStateDeltas(snapshot.signed, state.activeAllowSpends, state.activeTokenLocks))
        _ <- Logger[F].info(s"Successfully wrote to globalSnapshotsWithStateDeltasFileStorage")

        _ <- Logger[F].info(s"Initializing lastGlobalSnapshot storage with ordinal=$ordinal")
        _ <- sharedStorages.lastGlobalSnapshot.setInitial(
          snapshot,
          state
        )
        _ <- Logger[F].info(s"Successfully initialized lastGlobalSnapshot storage")

        kvPairs <- state.allStateEntries[F]
        _ <- sharedStorages.mptStore.syncFull(kvPairs, snapshot.ordinal)

        _ <- Logger[F].info(s"Successfully initialized all global snapshot storages with ordinal=$ordinal")
      } yield ()
    }

  def initializeCurrencySnapshotStorages[
    F[_]: Async: Logger: Hasher,
    R <: CliMethod
  ](
    storages: Storages[F],
    maybeCurrencySnapshot: Option[Signed[CurrencyIncrementalSnapshot]] = None,
    maybeCurrencySnapshotInfo: Option[CurrencySnapshotInfo] = None
  ) =
    for {
      _ <- Logger[F].info(s"Initializing currency snapshot storages")
      _ <- (maybeCurrencySnapshot, maybeCurrencySnapshotInfo).mapN { (currencySnapshot, currencySnapshotInfo) =>
        val ordinal = currencySnapshot.ordinal
        Logger[F].info(s"Prepending currency snapshot with ordinal=$ordinal") >>
          storages.snapshot.prepend(currencySnapshot, currencySnapshotInfo) >>
          Logger[F].info(s"Successfully prepended currency snapshot with ordinal=$ordinal")
      }.fold(
        MonadThrow[F].raiseError[Unit](new IllegalArgumentException("Currency snapshot and info must both be provided or both be absent"))
      )(identity)
      _ <- Logger[F].info(s"Successfully initialized currency snapshot storages")
    } yield ()
}
