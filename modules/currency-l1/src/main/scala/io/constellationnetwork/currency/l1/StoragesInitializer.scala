package io.constellationnetwork.currency.l1

import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.l1.modules.{Services, Storages}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo, CurrencySnapshotStateProof}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema.GlobalSnapshotInfo
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

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

  private def fetchCurrencySnapshot[F[_]: Async: Logger](
    globalState: GlobalSnapshotInfo,
    metagraphId: Address
  ): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)] =
    globalState.lastCurrencySnapshots
      .get(metagraphId) match {
      case Some(Right((snapshot, state))) =>
        (snapshot, state).pure
      case Some(Left(_)) =>
        val errorMsg = s"Found Left value in lastCurrencySnapshots for metagraphId=$metagraphId"
        Logger[F].error(errorMsg) >>
          (new Throwable(errorMsg)).raiseError[F, (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]
      case None =>
        val errorMsg = s"No currency snapshot found for metagraphId=$metagraphId"
        Logger[F].error(errorMsg) >>
          (new Throwable(errorMsg)).raiseError[F, (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]
    }

  def initializeGlobalSnapshotStorages[
    F[_]: Async: Logger,
    R <: CliMethod
  ](
    services: Services[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, R],
    sharedStorages: SharedStorages[F]
  ): F[GlobalSnapshotInfo] =
    pullGlobalSnapshotWithRetry(services.globalL0).flatMap { globalSnapshotCombined =>
      val (snapshot, state) = globalSnapshotCombined
      val ordinal = snapshot.ordinal
      for {
        _ <- Logger[F].info(s"Initializing global snapshot storages with ordinal=$ordinal")

        _ <- Logger[F].info(s"Initializing lastNGlobalSnapshot shared storage with ordinal=$ordinal")
        _ <- sharedStorages.lastNGlobalSnapshot.setInitialFetchingGL0(
          snapshot,
          state,
          services.globalL0.asLeft.some,
          none
        )
        _ <- Logger[F].info(s"Successfully initialized lastNGlobalSnapshot shared storage")

        _ <- Logger[F].info(s"Initializing lastGlobalSnapshot storage with ordinal=$ordinal")
        _ <- sharedStorages.lastGlobalSnapshot.setInitial(
          snapshot,
          state
        )
        _ <- Logger[F].info(s"Successfully initialized lastGlobalSnapshot storage")

        _ <- Logger[F].info(s"Successfully initialized global snapshot storages with ordinal=$ordinal")
      } yield state

    }

  def initializeCurrencySnapshotStorages[
    F[_]: Async: Logger: Hasher: SecurityProvider,
    R <: CliMethod
  ](
    storages: Storages[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    globalSnapshotInfo: GlobalSnapshotInfo,
    metagraphId: Address
  ) =
    for {
      _ <- Logger[F].info(s"Fetching currency snapshot for metagraphId=$metagraphId")
      (currencySnapshot, latestCurrencyState) <- fetchCurrencySnapshot(globalSnapshotInfo, metagraphId)
      hashedCurrencySnapshot <- currencySnapshot.toHashed

      _ <- Logger[F].info(s"Initializing lastSnapshot storage with ordinal=${currencySnapshot.ordinal} ")
      _ <- storages.lastSnapshot.setInitial(hashedCurrencySnapshot, latestCurrencyState)
      _ <- Logger[F].info(s"Successfully initialized lastSnapshot storage with ordinal=${currencySnapshot.ordinal}")
    } yield ()
}
