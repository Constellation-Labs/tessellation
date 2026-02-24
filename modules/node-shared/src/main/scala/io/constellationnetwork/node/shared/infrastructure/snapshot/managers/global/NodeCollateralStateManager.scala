package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralAcceptanceResult
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.nodeCollateral._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

trait NodeCollateralStateManager[F[_]] {
  def acceptNodeCollaterals(
    lastSnapshotContext: GlobalSnapshotInfo,
    epochProgress: EpochProgress,
    withdrawalTimeLimit: EpochProgress
  ): (
    SortedMap[Address, SortedSet[NodeCollateralRecord]],
    SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
    SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]
  )

  def getUpdatedCreateNodeCollaterals(
    nodeCollateralAcceptanceResult: UpdateNodeCollateralAcceptanceResult,
    unexpiredCreateNodeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]]
  )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[NodeCollateralRecord]]]

  def getUpdatedWithdrawNodeCollaterals(
    nodeCollateralAcceptanceResult: UpdateNodeCollateralAcceptanceResult,
    unexpiredWithdrawNodeCollaterals: SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
    lastSnapshotContext: GlobalSnapshotInfo
  )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]]
}

object NodeCollateralStateManager {

  def make[F[_]: Async](mptStore: MptStore[F, GlobalStateKey]): NodeCollateralStateManager[F] = new NodeCollateralStateManager[F] {

    def acceptNodeCollaterals(
      lastSnapshotContext: GlobalSnapshotInfo,
      epochProgress: EpochProgress,
      withdrawalTimeLimit: EpochProgress
    ): (
      SortedMap[Address, SortedSet[NodeCollateralRecord]],
      SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
      SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]
    ) = {
      val existingNodeCollaterals =
        lastSnapshotContext.activeNodeCollaterals.getOrElse(SortedMap.empty[Address, SortedSet[NodeCollateralRecord]])
      val existingWithdrawals =
        lastSnapshotContext.nodeCollateralWithdrawals.getOrElse(SortedMap.empty[Address, SortedSet[PendingNodeCollateralWithdrawal]])

      def isWithdrawalExpired(withdrawalEpoch: EpochProgress): Boolean =
        (withdrawalEpoch |+| withdrawalTimeLimit) <= epochProgress

      val unexpiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filterNot {
            case PendingNodeCollateralWithdrawal(_, _, withdrawalEpoch) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }

      val expiredWithdrawals = existingWithdrawals.map {
        case (address, withdrawals) =>
          address -> withdrawals.filter {
            case PendingNodeCollateralWithdrawal(_, _, withdrawalEpoch) =>
              isWithdrawalExpired(withdrawalEpoch)
          }
      }.filter { case (_, withdrawalList) => withdrawalList.nonEmpty }
      (existingNodeCollaterals, unexpiredWithdrawals, expiredWithdrawals)
    }

    def getUpdatedCreateNodeCollaterals(
      nodeCollateralAcceptanceResult: UpdateNodeCollateralAcceptanceResult,
      unexpiredCreateNodeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]]
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[NodeCollateralRecord]]] = {

      val acceptedTokenLockRefs = nodeCollateralAcceptanceResult.acceptedCreates.map {
        case (addr, creates) => (addr, creates.map(_._1.tokenLockRef).toSet)
      }
      val filteredUnexpiredCreateNodeCollaterals = unexpiredCreateNodeCollaterals.map {
        case (addr, creates) =>
          val tokenLocks = acceptedTokenLockRefs.getOrElse(addr, Set.empty)
          (addr, creates.filterNot(c => tokenLocks(c.event.tokenLockRef)))
      }
      val acceptedCreates = nodeCollateralAcceptanceResult.acceptedCreates.map {
        case (addr, cs) => addr -> cs.map(c => NodeCollateralRecord(c._1, c._2)).toSortedSet
      }
      val activeCollaterals: SortedMap[Address, SortedSet[NodeCollateralRecord]] =
        filteredUnexpiredCreateNodeCollaterals |+| acceptedCreates
      // remove withdrawn stakes from the active list
      val withdrawnCollaterals = nodeCollateralAcceptanceResult.acceptedWithdrawals.flatMap(_._2.map(_._1.collateralRef)).toSet
      activeCollaterals.toList.traverse {
        case (addr, records) =>
          records.toList.traverse { record =>
            NodeCollateralReference.of(record.event).map(ref => (record, withdrawnCollaterals(ref.hash)))
          }.map(records => (addr, records.filterNot(_._2).map(_._1).toSortedSet))
      }
        .map(_.filterNot(_._2.isEmpty))
        .map(SortedMap.from(_))
    }

    def getUpdatedWithdrawNodeCollaterals(
      nodeCollateralAcceptanceResult: UpdateNodeCollateralAcceptanceResult,
      unexpiredWithdrawNodeCollaterals: SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]],
      lastSnapshotContext: GlobalSnapshotInfo
    )(implicit hasher: Hasher[F]): F[SortedMap[Address, SortedSet[PendingNodeCollateralWithdrawal]]] =
      nodeCollateralAcceptanceResult.acceptedWithdrawals.toList.traverse {
        case (addr, acceptedWithdrawls) =>
          acceptedWithdrawls.traverse {
            case (ev, ep) =>
              mptStore
                .getNodeCollaterals(addr)
                .flatMap { maybeCollaterals =>
                  maybeCollaterals.flatTraverse {
                    _.findM { s =>
                      NodeCollateralReference.of(s.event).map(_.hash === ev.collateralRef)
                    }.map(_.map(rec => PendingNodeCollateralWithdrawal(rec.event, rec.createdAt, ep)))
                  }
                }
                .flatMap(Async[F].fromOption(_, new RuntimeException("Unexpected None when processing node collaterals")))
          }.map(pending => addr -> pending.toSortedSet)
      }.map(SortedMap.from(_))
        .map(unexpiredWithdrawNodeCollaterals |+| _)
        .map(_.filterNot(_._2.isEmpty))
  }
}
