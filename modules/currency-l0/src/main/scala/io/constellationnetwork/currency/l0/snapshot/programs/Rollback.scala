package io.constellationnetwork.currency.l0.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL0Service, DataApplicationTraverse, L0NodeContext}
import io.constellationnetwork.currency.l0.snapshot.CurrencyConsensusManager
import io.constellationnetwork.currency.l0.snapshot.schema.{CurrencyConsensusOutcome, Finished}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotContext, CurrencySnapshotInfo}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.infrastructure.consensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash

import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies.{constantDelay, limitRetries}
import retry.syntax.all._

sealed trait RollbackError extends NoStackTrace

case object LastSnapshotHashNotFound extends RollbackError

case object LastSnapshotInfoNotFound extends RollbackError

trait Rollback[F[_]] {
  def rollback(implicit hasher: Hasher[F]): F[Unit]
}

object Rollback {
  def make[F[_]: Async: KryoSerializer: HasherSelector: JsonSerializer: SecurityProvider](
    nodeId: PeerId,
    globalL0Service: GlobalL0Service[F],
    identifierStorage: IdentifierStorage[F],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshots: LastNGlobalSnapshotStorage[F],
    collateral: Collateral[F],
    consensusManager: CurrencyConsensusManager[F],
    dataApplication: Option[(BaseDataApplicationL0Service[F], CalculatedStateLocalFileSystemStorage[F])]
  )(implicit context: L0NodeContext[F]): Rollback[F] = new Rollback[F] {
    private val logger = Slf4jLogger.getLoggerFromName[F]("CurrencyRollback")

    val fetchGlobalSnapshotsRetryPolicy = limitRetries[F](10).join(constantDelay(3.seconds))

    def rollback(implicit hasher: Hasher[F]): F[Unit] = for {
      (globalSnapshot, globalSnapshotInfo) <- globalL0Service.pullLatestSnapshot

      identifier <- identifierStorage.get
      lastGlobalSnapshotWithCurrencySnapshot = globalSnapshotInfo.lastGlobalSnapshotsWithCurrency.flatMap(_.get(identifier))

      globalSnapshotStartingPoint: Hashed[GlobalIncrementalSnapshot] <- lastGlobalSnapshotWithCurrencySnapshot match {
        case Some(value) =>
          logger.info(
            s"Using global snapshot at ordinal ${value.ordinal} as the starting point, which includes the last currency snapshot for metagraph ${identifier.show}"
          ) >>
            globalL0Service
              .pullGlobalSnapshot(value.ordinal)
              .flatMap {
                case Some(snapshot) => snapshot.pure[F]
                case None =>
                  logger.warn(s"Global snapshot ordinal ${value.ordinal} not found, using current global snapshot") >>
                    globalSnapshot.pure[F]
              }
              .handleErrorWith { e =>
                logger.error(e)(
                  s"Could not fetch global snapshot ordinal: ${value.ordinal}, starting from latest global snapshot"
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

      _ <- snapshotStorage.prepend(lastIncremental, lastInfo)

      _ <- collateral
        .hasCollateral(nodeId)
        .flatMap(OwnCollateralNotSatisfied.raiseError[F, Unit].unlessA)

      _ <- dataApplication.map {
        case ((da, cs)) =>
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

          DataApplicationTraverse.make[F](globalSnapshotStartingPoint, fetchSnapshot, da, cs, identifier).flatMap { dat =>
            dat.loadChain().flatMap {
              case Some(_) => Applicative[F].unit
              case _       => new Exception(s"Metagraph traversing failed").raiseError[F, Unit]
            }
          }

      }.getOrElse(Applicative[F].unit)

      (globalSnapshotUpdated, globalSnapshotInfoUpdated) <- globalL0Service.pullLatestSnapshot
      _ <- lastGlobalSnapshot.setInitial(globalSnapshotUpdated, globalSnapshotInfoUpdated)
      _ <- lastNGlobalSnapshots.setInitial(globalSnapshotUpdated, globalSnapshotInfoUpdated)
      _ <- logger.info(
        s"Setting the last global snapshot as: ${globalSnapshotUpdated.ordinal.show}"
      )

      _ <- consensusManager.startFacilitatingAfterRollback(
        lastIncremental.ordinal,
        CurrencyConsensusOutcome(
          lastIncremental.ordinal,
          Facilitators(List(nodeId)),
          RemovedFacilitators.empty,
          WithdrawnFacilitators.empty,
          Finished(
            lastIncremental,
            lastBinaryHash,
            CurrencySnapshotContext(identifier, lastInfo),
            EventTrigger,
            Candidates.empty,
            Hash.empty
          )
        )
      )

      _ <- logger.info(
        s"Finished rollback to currency snapshot of ${lastIncremental.ordinal.show} pulled from global snapshot of ${globalSnapshot.ordinal.show}"
      )
    } yield ()
  }

}
