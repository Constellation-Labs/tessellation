package org.tessellation.currency.l0.snapshot.programs

import cats.Applicative
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import org.tessellation.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import org.tessellation.currency.dataApplication.{BaseDataApplicationL0Service, DataApplicationTraverse, L0NodeContext}
import org.tessellation.currency.l0.node.IdentifierStorage
import org.tessellation.currency.l0.snapshot.CurrencyConsensusManager
import org.tessellation.currency.l0.snapshot.schema.{CurrencyConsensusOutcome, Finished}
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotContext, CurrencySnapshotInfo}
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import org.tessellation.node.shared.domain.snapshot.services.GlobalL0Service
import org.tessellation.node.shared.domain.snapshot.storage.{LastSnapshotStorage, SnapshotStorage}
import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.consensus.trigger.EventTrigger
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security._
import org.tessellation.security.hash.Hash

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
    collateral: Collateral[F],
    consensusManager: CurrencyConsensusManager[F],
    dataApplication: Option[(BaseDataApplicationL0Service[F], CalculatedStateLocalFileSystemStorage[F])]
  )(implicit context: L0NodeContext[F]): Rollback[F] = new Rollback[F] {
    private val logger = Slf4jLogger.getLogger[F]

    val fetchGlobalSnapshotsRetryPolicy = limitRetries[F](10).join(constantDelay(3.seconds))

    def rollback(implicit hasher: Hasher[F]): F[Unit] = for {
      (globalSnapshot, globalSnapshotInfo) <- globalL0Service.pullLatestSnapshot

      identifier <- identifierStorage.get
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

          DataApplicationTraverse.make[F](globalSnapshot, fetchSnapshot, da, cs, identifier).flatMap { dat =>
            dat.loadChain().flatMap {
              case Some(_) => Applicative[F].unit
              case _       => new Exception(s"Metagraph traversing failed").raiseError[F, Unit]
            }
          }

      }.getOrElse(Applicative[F].unit)

      (globalSnapshotUpdated, globalSnapshotInfoUpdated) <- globalL0Service.pullLatestSnapshot
      _ <- lastGlobalSnapshot.setInitial(globalSnapshotUpdated, globalSnapshotInfoUpdated)
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
