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
import io.constellationnetwork.currency.l0.modules.Storages
import io.constellationnetwork.currency.l0.snapshot.CurrencyConsensusManager
import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusOutcome, Finished}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotContext, CurrencySnapshotInfo}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.dataApplication.DataApplicationTraverse
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotContextFunctions
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{CombinedSnapshotCheckpointFileSystemStorage, IdentifierStorage}
import io.constellationnetwork.node.shared.modules.SharedStorages
import io.constellationnetwork.schema.GlobalIncrementalSnapshot
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies.{constantDelay, limitRetries}
import retry.syntax.all._

sealed trait RollbackError extends NoStackTrace

case object LastSnapshotHashNotFound extends RollbackError

case object LastSnapshotInfoNotFound extends RollbackError

trait Rollback[F[_]] {
  def rollback(implicit hasher: Hasher[F]): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo, Hash)]
}

object Rollback {
  def make[F[_]: Async: KryoSerializer: HasherSelector: JsonSerializer: SecurityProvider](
    nodeId: PeerId,
    globalL0Service: GlobalL0Service[F],
    identifierStorage: IdentifierStorage[F],
    collateral: Collateral[F],
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
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  )(implicit context: L0NodeContext[F]): Rollback[F] = new Rollback[F] {
    private val logger = Slf4jLogger.getLoggerFromName[F]("CurrencyRollback")

    val fetchGlobalSnapshotsRetryPolicy = limitRetries[F](10).join(constantDelay(3.seconds))

    def rollback(implicit hasher: Hasher[F]): F[(Signed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo, Hash)] = for {
      (globalSnapshot, globalSnapshotInfo) <- globalL0Service.pullLatestSnapshot

      identifier <- identifierStorage.get
      metagraphSyncData = globalSnapshotInfo.metagraphSyncData.flatMap(_.get(identifier))

      globalSnapshotStartingPoint: Hashed[GlobalIncrementalSnapshot] <- metagraphSyncData match {
        case Some(value) =>
          logger.info(
            s"Using global snapshot at ordinal ${value.globalOrdinalLastAcceptedOn} as the starting point, which includes the last currency snapshot for metagraph ${identifier.show}"
          ) >>
            globalL0Service
              .pullGlobalSnapshot(value.globalOrdinalLastAcceptedOn)
              .flatMap {
                case Some(snapshot) => snapshot.pure[F]
                case None =>
                  logger.warn(s"Global snapshot ordinal ${value.globalOrdinalLastAcceptedOn} not found, using current global snapshot") >>
                    globalSnapshot.pure[F]
              }
              .handleErrorWith { e =>
                logger.error(e)(
                  s"Could not fetch global snapshot ordinal: ${value.globalOrdinalLastAcceptedOn}, starting from latest global snapshot"
                ) >> globalSnapshot.pure[F]
              }
        case None => globalSnapshot.pure[F]
      }

      lastBinaryHash <- globalSnapshotInfo.lastStateChannelSnapshotHashes
        .get(identifier)
        .toOptionT
        .getOrRaise(LastSnapshotHashNotFound)

      (lastIncremental, lastInfo) <- globalSnapshotInfo.lastCurrencySnapshots
        .get(identifier)
        .flatMap(_.toOption)
        .toOptionT
        .getOrRaise(LastSnapshotInfoNotFound)

      _ <- collateral
        .hasCollateral(nodeId)
        .flatMap(OwnCollateralNotSatisfied.raiseError[F, Unit].unlessA)

      _ <- snapshotStorage.prepend(lastIncremental, lastInfo).flatMap { prepended =>
        if (prepended)
          logger.info(s"Prepended last currency snapshot ordinal=${lastIncremental.ordinal.show} to snapshot storage before loadChain")
        else
          logger.warn(
            s"Could not prepend last currency snapshot ordinal=${lastIncremental.ordinal.show} to snapshot storage (already at different head); loadChain may diverge"
          )
      }

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

      _ <- logger.info(s"[Rollback] Cleanup for snapshots greater than ${lastIncremental.ordinal}")
      _ <- currencySnapshotCleanupStorage.cleanupAbove(lastIncremental.ordinal)
      _ <- combinedSnapshotCheckpointFileSystemStorage.deleteAbove(lastIncremental.ordinal)

      _ <- logger.info(
        s"Finished rollback to currency snapshot of ${lastIncremental.ordinal.show} pulled from global snapshot of ${globalSnapshot.ordinal.show}"
      )
    } yield (lastIncremental, lastInfo, lastBinaryHash)
  }

}
