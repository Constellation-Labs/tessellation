package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.Parallel
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendAction
import io.constellationnetwork.security.Hashed

import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies
import retry.implicits.retrySyntaxError

class GlobalSnapshotOpsManager[F[_]: Async: Parallel](
  lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
  lastGlobalSnapshotsCached: SignallingRef[F, Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]],
  globalSnapshotsAlreadyProcessed: SignallingRef[F, Map[Address, Map[SnapshotOrdinal, List[SnapshotOrdinal]]]]
) {
  val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("GlobalSnapshotOps")

  def getGlobalSnapshotWithRetry(
    ordinal: SnapshotOrdinal,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  ): F[Hashed[GlobalIncrementalSnapshot]] = {
    val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))
    getGlobalSnapshotByOrdinal(ordinal)
      .retryingOnFailuresAndAllErrors(
        wasSuccessful = maybeSnapshot => maybeSnapshot.isDefined.pure[F],
        policy = retryPolicy,
        onFailure = (_, retryDetails) =>
          logger.warn(s"Got None when trying to fetch incremental global snapshot $ordinal {attempt=${retryDetails.retriesSoFar}}"),
        onError = (err, retryDetails) =>
          logger.error(err)(s"Error when trying to fetch incremental global snapshot $ordinal {attempt=${retryDetails.retriesSoFar}}")
      )
      .flatMap {
        case Some(snapshot) => snapshot.pure[F]
        case None =>
          new RuntimeException(s"Global snapshot not found for ordinal $ordinal after retries")
            .raiseError[F, Hashed[GlobalIncrementalSnapshot]]
      }
  }

  def getLastGlobalSnapshotsSpendActions(
    globalSnapshotViewOrdinal: SnapshotOrdinal,
    lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    currencyId: Address,
    metagraphSyncData: Option[SortedMap[Address, snapshot.MetagraphSyncDataInfo]],
    currentCurrencySnapshotOrdinal: SnapshotOrdinal,
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal
  ): F[(SortedMap[Address, List[SpendAction]], SortedSet[SnapshotOrdinal])] = {
    val emptySpendActions = SortedMap.empty[Address, List[SpendAction]]
    val emptyProcessedGlobalSnapshots = SortedSet.empty[SnapshotOrdinal]

    metagraphSyncData match {
      case None => (emptySpendActions, emptyProcessedGlobalSnapshots).pure[F]
      case Some(metagraphSyncData) =>
        metagraphSyncData.get(currencyId) match {
          case None => (emptySpendActions, emptyProcessedGlobalSnapshots).pure[F]
          case Some(syncDataInfo) =>
            for {
              allMetagraphsGlobalSnapshotsAlreadyProcessed <- globalSnapshotsAlreadyProcessed.get

              metagraphOrdinalsByCurrencyOrdinal =
                allMetagraphsGlobalSnapshotsAlreadyProcessed.getOrElse(currencyId, Map.empty)

              allProcessedOrdinals =
                metagraphOrdinalsByCurrencyOrdinal.values.flatten.toSet

              alreadyProcessedForCurrentOrdinal =
                metagraphOrdinalsByCurrencyOrdinal.getOrElse(currentCurrencySnapshotOrdinal, List.empty)

              unappliedGlobalOrdinalsToProcess = syncDataInfo.unappliedGlobalChangeOrdinals
                .filter(o => o <= globalSnapshotViewOrdinal && !allProcessedOrdinals.contains(o))

              globalOrdinalsToProcess = (alreadyProcessedForCurrentOrdinal ++ unappliedGlobalOrdinalsToProcess).toSet

              result <-
                if (globalOrdinalsToProcess.isEmpty) {
                  (emptySpendActions, emptyProcessedGlobalSnapshots).pure[F]
                } else {
                  for {
                    spendActions <- processUnappliedOrdinals(
                      globalOrdinalsToProcess,
                      lastGlobalSnapshots,
                      getGlobalSnapshotByOrdinal,
                      lastUnsyncGlobalSnapshotOrdinal,
                      updatedLastSyncGlobalFromPeersInConsensus
                    )
                    _ <- globalSnapshotsAlreadyProcessed.update { current =>
                      val currentMetagraphProcessedOrdinals = current.getOrElse(currencyId, Map.empty)

                      val updatedMetagraphProcessedOrdinals = currentMetagraphProcessedOrdinals
                        .updated(
                          currentCurrencySnapshotOrdinal,
                          currentMetagraphProcessedOrdinals
                            .getOrElse(currentCurrencySnapshotOrdinal, List.empty)
                            ++ unappliedGlobalOrdinalsToProcess
                        )
                        .view
                        .mapValues(_.distinct.sorted)
                        .toSeq
                        .sortBy(_._1.value.value)
                        .takeRight(lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory.value)
                        .toMap

                      current.updated(currencyId, updatedMetagraphProcessedOrdinals)
                    }

                    _ <- globalSnapshotsAlreadyProcessed.get.flatMap { processed =>
                      val totalAddresses = processed.size
                      val totalEntries = processed.values.map(_.size).sum
                      val totalOrdinals = processed.values.flatMap(_.values).map(_.size).sum
                      logger.info(
                        s"--- [ORDINAL=$globalSnapshotViewOrdinal] globalSnapshotsAlreadyProcessed size: $totalAddresses addresses, $totalEntries entries, $totalOrdinals total ordinals"
                      )
                    }
                  } yield (spendActions, unappliedGlobalOrdinalsToProcess)
                }
            } yield result
        }
    }
  }

  private def processUnappliedOrdinals(
    unappliedOrdinals: Set[SnapshotOrdinal],
    lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal
  ): F[SortedMap[Address, List[SpendAction]]] = {
    val snapshotCache = lastGlobalSnapshots.map(s => s.ordinal -> s).toMap
    val (cached, missing) = unappliedOrdinals.partition(snapshotCache.contains)

    val fromCache = cached.toList.flatMap { ordinal =>
      snapshotCache.get(ordinal).flatMap(_.spendActions).toList
    }

    val fetchMissing = missing.toList.parTraverse { ordinal =>
      getGlobalSnapshotWithRetry(ordinal, getGlobalSnapshotByOrdinal)
        .map(_.spendActions.getOrElse(SortedMap.empty[Address, List[SpendAction]]))
    }

    fetchMissing.map(fromFetched =>
      combineSpendActions(fromCache ++ fromFetched, lastUnsyncGlobalSnapshotOrdinal, updatedLastSyncGlobalFromPeersInConsensus)
    )
  }

  private def combineSpendActions(
    spendActionsList: List[SortedMap[Address, List[SpendAction]]],
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal
  ): SortedMap[Address, List[SpendAction]] =
    if (lastUnsyncGlobalSnapshotOrdinal > updatedLastSyncGlobalFromPeersInConsensus) {
      spendActionsList
        .reduceOption(_ |+| _)
        .getOrElse(SortedMap.empty)
    } else {
      spendActionsList
        .reduceOption(_ ++ _)
        .getOrElse(SortedMap.empty)
    }

  def updateGlobalSnapshotCache(
    snapshot: Hashed[GlobalIncrementalSnapshot]
  ): F[Unit] =
    for {
      _ <- lastGlobalSnapshotsCached.update { current =>
        val updated = current.updated(snapshot.ordinal, snapshot)
        updated.toSeq
          .sortBy(_._1.value.value)
          .takeRight(lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory.value)
          .toMap
      }
    } yield ()
}

object GlobalSnapshotOpsManager {
  def make[F[_]: Async: Parallel](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    lastGlobalSnapshotsCached: SignallingRef[F, Map[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]],
    globalSnapshotsAlreadyProcessed: SignallingRef[F, Map[Address, Map[SnapshotOrdinal, List[SnapshotOrdinal]]]]
  ): GlobalSnapshotOpsManager[F] =
    new GlobalSnapshotOpsManager[F](
      lastGlobalSnapshotsSyncConfig,
      lastGlobalSnapshotsCached,
      globalSnapshotsAlreadyProcessed
    )
}
