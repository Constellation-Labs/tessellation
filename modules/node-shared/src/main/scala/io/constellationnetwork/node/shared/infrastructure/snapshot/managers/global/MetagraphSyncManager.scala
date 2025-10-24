package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.node.shared.config.types.MetagraphsSyncConfig
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{GlobalSnapshotsProcessed, SpendAction, SpendTransaction}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.snapshot.MetagraphSyncDataInfo
import io.constellationnetwork.schema.swap._

import monocle.syntax.all._

trait MetagraphSyncManager[F[_]] {
  def acceptMetagraphSyncData(
    lastSnapshotContext: GlobalSnapshotInfo,
    incomingCurrencySnapshots: SortedMap[Address, List[CurrencySnapshotWithState]],
    globalSnapshotsProcessed: Map[Address, List[GlobalSnapshotsProcessed]],
    acceptedSpendActions: Map[Address, List[SpendAction]],
    currentGlobalOrdinal: SnapshotOrdinal,
    currentGlobalEpochProgress: EpochProgress
  ): F[SortedMap[Address, MetagraphSyncDataInfo]]
}

object MetagraphSyncManager {

  def make[F[_]: Async: Parallel](
    metagraphsSyncConfig: MetagraphsSyncConfig
  ): MetagraphSyncManager[F] = new MetagraphSyncManager[F] {

    def acceptMetagraphSyncData(
      lastSnapshotContext: GlobalSnapshotInfo,
      incomingCurrencySnapshots: SortedMap[Address, List[CurrencySnapshotWithState]],
      globalSnapshotsProcessed: Map[Address, List[GlobalSnapshotsProcessed]],
      acceptedSpendActions: Map[Address, List[SpendAction]],
      currentGlobalOrdinal: SnapshotOrdinal,
      currentGlobalEpochProgress: EpochProgress
    ): F[SortedMap[Address, MetagraphSyncDataInfo]] =
      lastSnapshotContext.metagraphSyncData.map { existingData =>
        for {
          updatedFromSnapshots <- updateFromCurrencySnapshots(
            existingData,
            incomingCurrencySnapshots,
            globalSnapshotsProcessed,
            currentGlobalOrdinal,
            currentGlobalEpochProgress
          )

          updatedFromSpendActions <- updateFromSpendActions(
            updatedFromSnapshots,
            acceptedSpendActions,
            currentGlobalOrdinal
          )

        } yield updatedFromSpendActions
      }.getOrElse(SortedMap.empty[Address, MetagraphSyncDataInfo].pure[F])

    private def updateFromCurrencySnapshots(
      existingData: SortedMap[Address, MetagraphSyncDataInfo],
      incomingCurrencySnapshots: SortedMap[Address, List[CurrencySnapshotWithState]],
      globalSnapshotsProcessed: Map[Address, List[GlobalSnapshotsProcessed]],
      currentOrdinal: SnapshotOrdinal,
      currentEpochProgress: EpochProgress
    ): F[SortedMap[Address, MetagraphSyncDataInfo]] =
      incomingCurrencySnapshots.toList.parTraverse {
        case (address, _) =>
          val currentInfo = existingData.getOrElse(address, MetagraphSyncDataInfo.empty)
          val metagraphGlobalSnapshotsProcessed =
            globalSnapshotsProcessed.getOrElse(address, List.empty).flatMap(_.ordinals).toSet
          val updatedUnappliedGlobalChangeOrdinals =
            currentInfo.unappliedGlobalChangeOrdinals.diff(metagraphGlobalSnapshotsProcessed)

          val updatedInfo = currentInfo
            .focus(_.globalOrdinalLastAcceptedOn)
            .replace(currentOrdinal)
            .focus(_.globalEpochProgressLastAcceptedOn)
            .replace(currentEpochProgress)
            .focus(_.unappliedGlobalChangeOrdinals)
            .replace(updatedUnappliedGlobalChangeOrdinals)

          (address -> updatedInfo).pure[F]
      }.map { updatedEntries =>
        val updatedMap = SortedMap.from(updatedEntries)
        existingData ++ updatedMap
      }

    private def updateFromSpendActions(
      currentData: SortedMap[Address, MetagraphSyncDataInfo],
      spendActions: Map[Address, List[SpendAction]],
      currentOrdinal: SnapshotOrdinal
    ): F[SortedMap[Address, MetagraphSyncDataInfo]] = {
      val allCurrencySpendTransactions = extractCurrencySpendTransactions(spendActions)

      val transactionsByMetagraph = allCurrencySpendTransactions.groupBy(_.currencyId.get.value)

      transactionsByMetagraph.toList.foldM(currentData) {
        case (acc, (metagraphId, _)) =>
          val currentInfo = acc.getOrElse(metagraphId, MetagraphSyncDataInfo.empty)

          val updatedUnappliedGlobalChangeOrdinals =
            trimUnappliedOrdinals(currentInfo.unappliedGlobalChangeOrdinals, currentOrdinal)

          val updatedInfo = currentInfo
            .focus(_.unappliedGlobalChangeOrdinals)
            .replace(updatedUnappliedGlobalChangeOrdinals)

          acc.updated(metagraphId, updatedInfo).pure[F]
      }
    }

    private def extractCurrencySpendTransactions(spendActions: Map[Address, List[SpendAction]]): List[SpendTransaction] =
      spendActions.values.flatten
        .flatMap(_.spendTransactions.toList)
        .filter(_.currencyId.isDefined)
        .toList

    private def trimUnappliedOrdinals(
      currentOrdinals: SortedSet[SnapshotOrdinal],
      newOrdinal: SnapshotOrdinal
    ): SortedSet[SnapshotOrdinal] = {
      val maxSize = metagraphsSyncConfig.maxUnappliedGlobalChangeOrdinals.value
      val updated = currentOrdinals + newOrdinal

      if (updated.size <= maxSize) updated
      else updated.dropRight(updated.size - maxSize)
    }
  }
}
