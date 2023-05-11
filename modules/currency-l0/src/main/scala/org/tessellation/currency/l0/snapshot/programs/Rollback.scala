package org.tessellation.currency.l0.snapshot.programs

import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.currency.l0.snapshot.storages.LastSignedBinaryHashStorage
import org.tessellation.currency.l0.snapshot.{CurrencySnapshotArtifact, CurrencySnapshotContext}
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.collateral.{Collateral, OwnCollateralNotSatisfied}
import org.tessellation.sdk.domain.snapshot.services.GlobalL0Service
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.consensus.ConsensusManager

import org.typelevel.log4cats.slf4j.Slf4jLogger

sealed trait RollbackError extends NoStackTrace
case object LastSnapshotHashNotFound extends RollbackError
case object LastIncrementalSnapshotNotFound extends RollbackError
case object LastSnapshotInfoNotFound extends RollbackError

trait Rollback[F[_]] {
  def rollback: F[Unit]
}

object Rollback {
  def make[F[_]: Async](
    nodeId: PeerId,
    identifier: Address,
    globalL0Service: GlobalL0Service[F],
    lastSignedBinaryHashStorage: LastSignedBinaryHashStorage[F],
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    collateral: Collateral[F],
    consensusManager: ConsensusManager[F, SnapshotOrdinal, CurrencySnapshotArtifact, CurrencySnapshotContext]
  ): Rollback[F] = new Rollback[F] {
    private val logger = Slf4jLogger.getLogger[F]

    def rollback: F[Unit] = for {
      (globalSnapshot, globalSnapshotInfo) <- globalL0Service.pullLatestSnapshot

      lastBinaryHash <- globalSnapshotInfo.lastStateChannelSnapshotHashes
        .get(identifier)
        .toOptionT
        .getOrRaise(LastSnapshotHashNotFound)

      (maybeLastIncremental, lastInfo) <- globalSnapshotInfo.lastCurrencySnapshots
        .get(identifier)
        .toOptionT
        .getOrRaise(LastSnapshotInfoNotFound)

      lastIncremental <- maybeLastIncremental.toOptionT.getOrRaise(LastIncrementalSnapshotNotFound)

      _ <- snapshotStorage.prepend(lastIncremental, lastInfo)

      _ <- collateral
        .hasCollateral(nodeId)
        .flatMap(OwnCollateralNotSatisfied.raiseError[F, Unit].unlessA)

      _ <- lastSignedBinaryHashStorage.set(lastBinaryHash)

      _ <- consensusManager.startFacilitatingAfterRollback(
        lastIncremental.ordinal,
        lastIncremental,
        lastInfo
      )

      _ <- logger.info(
        s"Finished rollback to currency snapshot of ${lastIncremental.ordinal.show} pulled from global snapshot of ${globalSnapshot.ordinal.show}"
      )
    } yield ()
  }

}
