package io.constellationnetwork.currency.l0

import cats.MonadThrow
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.l0.modules.{Services, Storages}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema.{GlobalSnapshotWithState, GlobalSnapshotWithStateDeltas}
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

  private def initializeGlobalSnapshotStorages[
    F[_]: Async: Logger,
    R <: CliMethod
  ](
    services: Services[F, R],
    storages: Storages[F],
    sharedStorages: SharedStorages[F]
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

        _ <- Logger[F].info(s"Successfully initialized all global snapshot storages with ordinal=$ordinal")
      } yield ()
    }

  private def initializeCurrencySnapshotStorages[
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

  def initializeStorages[
    F[_]: Async: Logger: Hasher,
    R <: CliMethod
  ](
    storages: Storages[F],
    sharedStorages: SharedStorages[F],
    services: Services[F, R],
    maybeCurrencySnapshot: Option[Signed[CurrencyIncrementalSnapshot]] = None,
    maybeCurrencySnapshotInfo: Option[CurrencySnapshotInfo] = None
  ): F[Unit] =
    for {
      _ <- Logger[F].info(s"Starting storage initialization")

      _ <-
        if (maybeCurrencySnapshot.isDefined || maybeCurrencySnapshotInfo.isDefined) {
          Logger[F].info(s"Initializing currency storages (currency snapshot provided)") >>
            initializeCurrencySnapshotStorages(storages, maybeCurrencySnapshot, maybeCurrencySnapshotInfo) >>
            Logger[F].info(s"Successfully initialized currency storages")
        } else {
          Logger[F].info(s"Skipping currency storage initialization (no currency snapshot provided)").void
        }

      _ <- Logger[F].info(s"Initializing global storages")
      _ <- initializeGlobalSnapshotStorages(services, storages, sharedStorages)
      _ <- Logger[F].info(s"Successfully initialized global storages")

      _ <- Logger[F].info(s"Storage initialization completed successfully")
    } yield ()
}
