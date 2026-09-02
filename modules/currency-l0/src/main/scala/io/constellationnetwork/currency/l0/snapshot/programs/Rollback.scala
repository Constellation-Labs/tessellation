package io.constellationnetwork.currency.l0.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.storage.{
  CalculatedStateLocalFileSystemStorage,
  GlobalSnapshotsWithStateDeltasLocalFileSystemStorage,
  GlobalSnapshotsWithStateLocalFileSystemStorage
}
import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, L0NodeContext}
import io.constellationnetwork.currency.l0.domain.snapshot.storages.CurrencySnapshotCleanupStorage
import io.constellationnetwork.currency.l0.snapshot.storage.{
  CurrencyFeeContextReceiptStorage,
  RecoverySyncPublicationStorage,
  StateChannelBinaryOutboxStorage
}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.{ExactSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.dataApplication.DataApplicationTraverse
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{CombinedSnapshotCheckpointFileSystemStorage, IdentifierStorage}
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies.{constantDelay, limitRetries}
import retry.RetryPolicy
import retry.syntax.all._

sealed trait RollbackError extends NoStackTrace

case object LastSnapshotHashNotFound extends RollbackError

case object LastSnapshotInfoNotFound extends RollbackError

trait Rollback[F[_]] {
  def rollback(implicit hasher: Hasher[F]): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo, Hash)]
}

object Rollback {
  def make[F[_]: Async: KryoSerializer: HasherSelector: JsonSerializer: SecurityProvider](
    globalL0Service: GlobalL0Service[F],
    identifierStorage: IdentifierStorage[F],
    dataApplication: Option[(BaseDataApplicationL0Service[F], CalculatedStateLocalFileSystemStorage[F])],
    currencySnapshotCleanupStorage: CurrencySnapshotCleanupStorage[F],
    globalSnapshotsWithStateLocalFileSystemStorage: GlobalSnapshotsWithStateLocalFileSystemStorage[F],
    globalSnapshotsWithStateDeltasLocalFileSystemStorage: GlobalSnapshotsWithStateDeltasLocalFileSystemStorage[F],
    globalSnapshotContextFunctions: GlobalSnapshotContextFunctions[F],
    combinedSnapshotCheckpointFileSystemStorage: CombinedSnapshotCheckpointFileSystemStorage[
      F,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    recoverySyncPublicationStorage: RecoverySyncPublicationStorage[F],
    stateChannelBinaryOutboxStorage: StateChannelBinaryOutboxStorage[F],
    feeContextReceiptStorage: CurrencyFeeContextReceiptStorage[F],
    validateLeadBeforeMutation: (Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo) => F[Unit]
  )(implicit context: L0NodeContext[F]): Rollback[F] = new Rollback[F] {
    private val logger = Slf4jLogger.getLoggerFromName[F]("CurrencyRollback")

    val fetchGlobalSnapshotsRetryPolicy = limitRetries[F](10).join(constantDelay(3.seconds))

    def rollback(implicit hasher: Hasher[F]): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo, Hash)] = for {
      (globalSnapshot, globalSnapshotInfo) <- globalL0Service.pullLatestSnapshot

      identifier <- identifierStorage.get
      metagraphSyncData = globalSnapshotInfo.metagraphSyncData.flatMap(_.get(identifier))

      globalSnapshotStartingPoint: Hashed[GlobalIncrementalSnapshot] <- resolveStartingGlobalSnapshot(
        globalSnapshot,
        metagraphSyncData.map(_.globalOrdinalLastAcceptedOn),
        (ordinal: SnapshotOrdinal) => globalL0Service.pullGlobalSnapshot(ordinal),
        fastPathRetryPolicy[F],
        identifier.show
      )

      lastBinaryHash <- globalSnapshotInfo.lastStateChannelSnapshotHashes
        .get(identifier)
        .toOptionT
        .getOrRaise(LastSnapshotHashNotFound)

      (lastIncremental, lastInfo) <- globalSnapshotInfo.lastCurrencySnapshots
        .get(identifier)
        .flatMap(_.toOption)
        .toOptionT
        .getOrRaise(LastSnapshotInfoNotFound)

      // The controlled rollback lead is the sole initial committee authority. Reject an
      // ineligible lead before cleanup, exact-snapshot replacement, or data-application writes.
      _ <- validateLeadBeforeMutation(lastIncremental, lastInfo)

      // Explicit coordinated rollback is the only authority that can discard a committed
      // recovery receipt or ordinary-binary outbox suffix. Ordinary restart/re-download
      // never calls this.
      _ <- recoverySyncPublicationStorage.discardForCanonicalReplacement
      _ <- stateChannelBinaryOutboxStorage.discardAllForCanonicalReplacement
      _ <- feeContextReceiptStorage.discardAllForCanonicalReplacement

      _ <- logger.info(s"[Rollback] Cleanup for snapshots greater than ${lastIncremental.ordinal}")
      lastIncrementalHash <- lastIncremental.value.hash
      exactInstalled <- ExactSnapshotStorage.installCanonicalSuffixForRecovery(
        snapshotStorage,
        lastIncremental,
        lastInfo,
        currencySnapshotCleanupStorage.cleanupCanonicalSuffix(lastIncremental.ordinal, lastIncrementalHash) >>
          combinedSnapshotCheckpointFileSystemStorage.deleteAbove(lastIncremental.ordinal)
      )
      _ <- Async[F].raiseUnless(exactInstalled)(
        new IllegalStateException(
          s"Could not install exact rollback Currency artifact/context ordinal=${lastIncremental.ordinal.show}; " +
            "durable recovery replacement/readback failed; keep consensus stopped and inspect local storage"
        )
      )
      _ <- logger.info(s"Installed exact rollback Currency snapshot ordinal=${lastIncremental.ordinal.show} before loadChain")

      _ <- dataApplication.map {
        case (da, cs) =>
          val fetchSnapshot: Hash => F[Option[Hashed[GlobalIncrementalSnapshot]]] = (hash: Hash) =>
            globalL0Service
              .pullGlobalSnapshot(hash)
              .retryingOnFailuresAndAllErrors(
                wasSuccessful = maybeSnapshot => maybeSnapshot.isDefined.pure[F],
                policy = fetchGlobalSnapshotsRetryPolicy,
                onFailure = (_, retryDetails) =>
                  logger.warn(s"Failure when trying to fetch incremental global snapshot {attempt=${retryDetails.retriesSoFar}}"),
                onError = (err, retryDetails) =>
                  logger.error(err)(s"Error when trying to fetch incremental global snapshot {attempt=${retryDetails.retriesSoFar}}")
              )
              .flatMap {
                case Some(snapshot) => snapshot.some.pure[F]
                case None =>
                  new Exception(s"Global snapshot not found for hash=${hash.show} after retries")
                    .raiseError[F, Option[Hashed[GlobalIncrementalSnapshot]]]
              }

          val dat = DataApplicationTraverse
            .make[F](
              globalSnapshotStartingPoint,
              fetchSnapshot,
              da,
              cs,
              globalSnapshotsWithStateLocalFileSystemStorage,
              globalSnapshotsWithStateDeltasLocalFileSystemStorage,
              identifier,
              globalSnapshotContextFunctions,
              globalL0Service
            )

          dat.loadChain().flatMap {
            case Some(_) => Applicative[F].unit
            case _       => new Exception(s"Metagraph traversing failed").raiseError[F, Unit]
          }

      }.getOrElse(Applicative[F].unit)

      _ <- logger.info(
        s"Finished rollback to currency snapshot of ${lastIncremental.ordinal.show} pulled from global snapshot of ${globalSnapshot.ordinal.show}"
      )
    } yield (lastIncremental, lastInfo, lastBinaryHash)
  }

  // The metagraphSyncData fast path pulls a potentially days-old global snapshot, and
  // GlobalL0Service.pullGlobalSnapshot draws ONE random GL0 peer per call, converting any error
  // (including HTTP 404) to None. Many peers hold no deep snapshot history (a restarted peer
  // serves only post-restart ordinals), so a single draw used to silently degrade the rollback
  // into a one-by-one lastSnapshotHash walk from the tip -- observed on IntegrationNet as a
  // 4,310-snapshot, 73-minute walk after a 404 that failed in under 100ms. Because every retry
  // re-rolls the peer, retrying doubles as a peer sweep with replacement; the failure mode is a
  // fast 404, so many attempts with a short delay beat the slow-path 10 x 3s policy.
  private[programs] def fastPathRetryPolicy[F[_]: Applicative]: RetryPolicy[F] =
    limitRetries[F](15).join(constantDelay(1.second))

  /** Resolves the rollback starting snapshot: the fast-path target ordinal when the sync-data hint is present (retried as a peer sweep),
    * else the latest global snapshot. Type-parametric in the snapshot payload so the retry/fallback decision is directly testable without
    * fixtures.
    */
  private[programs] def resolveStartingGlobalSnapshot[F[_]: Async, A](
    latestGlobalSnapshot: A,
    fastPathTargetOrdinal: Option[SnapshotOrdinal],
    pullGlobalSnapshot: SnapshotOrdinal => F[Option[A]],
    retryPolicy: RetryPolicy[F],
    metagraphId: String
  ): F[A] = {
    val logger = Slf4jLogger.getLoggerFromName[F]("CurrencyRollback")

    def fallbackToLatest(targetOrdinal: SnapshotOrdinal, error: Option[Throwable]): F[A] = {
      val message =
        s"Global snapshot ordinal $targetOrdinal unavailable after exhausting the peer-sweep retries; " +
          "falling back to the latest global snapshot. The rollback will now walk lastSnapshotHash " +
          "links one by one from the tip, which can take hours for a metagraph that has been down for days"
      error.fold(logger.error(message))(e => logger.error(e)(message)) >> latestGlobalSnapshot.pure[F]
    }

    fastPathTargetOrdinal match {
      case Some(targetOrdinal) =>
        logger.info(
          s"Using global snapshot at ordinal $targetOrdinal as the starting point, which includes the last currency snapshot for metagraph $metagraphId"
        ) >>
          pullGlobalSnapshot(targetOrdinal)
            .retryingOnFailuresAndAllErrors(
              wasSuccessful = maybeSnapshot => maybeSnapshot.isDefined.pure[F],
              policy = retryPolicy,
              onFailure = (_, retryDetails) =>
                logger.warn(
                  s"Global snapshot ordinal $targetOrdinal not found on the drawn peer, retrying with a new random peer {attempt=${retryDetails.retriesSoFar}}"
                ),
              onError = (err, retryDetails) =>
                logger.error(err)(
                  s"Error pulling global snapshot ordinal $targetOrdinal, retrying with a new random peer {attempt=${retryDetails.retriesSoFar}}"
                )
            )
            .flatMap {
              case Some(snapshot) => snapshot.pure[F]
              case None           => fallbackToLatest(targetOrdinal, none)
            }
            .handleErrorWith(e => fallbackToLatest(targetOrdinal, e.some))
      case None => latestGlobalSnapshot.pure[F]
    }
  }

}
