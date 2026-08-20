package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.GlobalSnapshotOpsManager._
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.HistoricalGlobalSnapshotResolver._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendAction
import io.constellationnetwork.security.Hashed

import eu.timepit.refined.auto._
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import retry.RetryPolicies
import retry.implicits.retrySyntaxError

/** Historical Global L0 inputs used by Currency L0 artifact recreation.
  *
  * Signed Currency protocol-0.0.1 history keeps the legacy callback semantics during historical replay. Live processing always enforces the
  * retention boundary so an unsupported dormant lineage cannot turn local archive availability into a consensus input. Signed
  * protocol-1.0.0 history uses only signed parent history plus the consensus-retained window during both live processing and replay.
  */
class GlobalSnapshotOpsManager[F[_]: Async](
  lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
  globalSnapshotsAlreadyProcessed: SignallingRef[F, Map[Address, Map[SnapshotOrdinal, List[SnapshotOrdinal]]]],
  metrics: Option[Metrics[F]] = None
) {
  private val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("GlobalSnapshotOps")
  private val retainedCount = lastGlobalSnapshotsSyncConfig.maxLastGlobalSnapshotsInMemory.value

  private def recordMetric(update: Metrics[F] => F[Unit]): F[Unit] =
    metrics.traverse_(telemetry => update(telemetry).attempt.void)

  private def getGlobalSnapshotWithRetry(
    purpose: Purpose,
    ordinal: SnapshotOrdinal,
    parentOrdinal: SnapshotOrdinal,
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  ): F[Hashed[GlobalIncrementalSnapshot]] = {
    val retryPolicy = RetryPolicies.exponentialBackoff[F](1.second).join(RetryPolicies.limitRetries(5))

    getGlobalSnapshotByOrdinal(ordinal)
      .retryingOnFailuresAndAllErrors(
        wasSuccessful = maybeSnapshot => maybeSnapshot.exists(_.ordinal === ordinal).pure[F],
        policy = retryPolicy,
        onFailure = (_, retryDetails) =>
          logger.warn(s"Global snapshot ordinal=$ordinal unavailable or mismatched attempt=${retryDetails.retriesSoFar}"),
        onError = (error, retryDetails) =>
          logger.error(error)(s"Global snapshot ordinal=$ordinal fetch failed attempt=${retryDetails.retriesSoFar}")
      )
      .flatMap(
        _.filter(_.ordinal === ordinal).liftTo[F](MissingInsideRetainedWindow(purpose, ordinal, parentOrdinal))
      )
  }

  def resolveGlobalSnapshot(
    purpose: Purpose,
    ordinal: SnapshotOrdinal,
    parentOrdinal: SnapshotOrdinal,
    lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    mode: DependencyMode
  ): F[Hashed[GlobalIncrementalSnapshot]] = {
    val recent = lastGlobalSnapshots.find(_.ordinal === ordinal)

    mode match {
      case DeterministicHistory | LiveBounded =>
        HistoricalGlobalSnapshotResolver
          .resolve(purpose, ordinal, parentOrdinal, retainedCount, lastGlobalSnapshots)(_.ordinal)
          .fold(
            error => recordDependency(purpose, errorOutcome(error)) >> error.raiseError[F, Hashed[GlobalIncrementalSnapshot]],
            snapshot => recordDependency(purpose, "recent") >> snapshot.pure[F]
          )

      case HistoricalReplay =>
        recent.fold(
          getGlobalSnapshotWithRetry(purpose, ordinal, parentOrdinal, getGlobalSnapshotByOrdinal)
            .flatTap(_ => recordDependency(purpose, "fetched"))
        )(snapshot => recordDependency(purpose, "recent") >> snapshot.pure[F])
    }
  }

  def getLastGlobalSnapshotsSpendActions(
    globalSnapshotViewOrdinal: SnapshotOrdinal,
    lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    currencyId: Address,
    metagraphSyncData: Option[SortedMap[Address, snapshot.MetagraphSyncDataInfo]],
    currentCurrencySnapshotOrdinal: SnapshotOrdinal,
    previousGlobalSyncView: Option[GlobalSyncView],
    previouslyDeclared: SortedSet[SnapshotOrdinal],
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal,
    mode: DependencyMode,
    deterministicProcessedHistory: Boolean
  ): F[(SortedMap[Address, List[SpendAction]], SortedSet[SnapshotOrdinal])] =
    if (deterministicProcessedHistory)
      getDeterministicSpendActions(
        globalSnapshotViewOrdinal,
        lastGlobalSnapshots,
        getGlobalSnapshotByOrdinal,
        currencyId,
        metagraphSyncData,
        previousGlobalSyncView,
        previouslyDeclared,
        lastUnsyncGlobalSnapshotOrdinal,
        updatedLastSyncGlobalFromPeersInConsensus,
        mode
      )
    else
      getLegacySpendActions(
        globalSnapshotViewOrdinal,
        lastGlobalSnapshots,
        getGlobalSnapshotByOrdinal,
        currencyId,
        metagraphSyncData,
        currentCurrencySnapshotOrdinal,
        lastUnsyncGlobalSnapshotOrdinal,
        updatedLastSyncGlobalFromPeersInConsensus,
        mode
      )

  private def getDeterministicSpendActions(
    globalSnapshotViewOrdinal: SnapshotOrdinal,
    lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    currencyId: Address,
    metagraphSyncData: Option[SortedMap[Address, snapshot.MetagraphSyncDataInfo]],
    previousGlobalSyncView: Option[GlobalSyncView],
    previouslyDeclared: SortedSet[SnapshotOrdinal],
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal,
    mode: DependencyMode
  ): F[(SortedMap[Address, List[SpendAction]], SortedSet[SnapshotOrdinal])] =
    metagraphSyncData.flatMap(_.get(currencyId)) match {
      case None => (SortedMap.empty[Address, List[SpendAction]], SortedSet.empty[SnapshotOrdinal]).pure[F]
      case Some(syncDataInfo) =>
        ProcessedGlobalSnapshotHistory
          .derive(
            previousGlobalSyncView,
            previouslyDeclared,
            syncDataInfo.unappliedGlobalChangeOrdinals,
            globalSnapshotViewOrdinal
          )
          .fold(
            error =>
              recordMetric(
                _.incrementCounter(
                  "dag_currency_l0_processed_history_total",
                  Seq(Metrics.unsafeLabelName("outcome") -> "unproven")
                )
              ) >> error.raiseError[F, (SortedMap[Address, List[SpendAction]], SortedSet[SnapshotOrdinal])],
            plan =>
              for {
                snapshots <- resolveGlobalSnapshots(
                  UnappliedSpendAction,
                  plan.newlyRequired,
                  lastUnsyncGlobalSnapshotOrdinal,
                  lastGlobalSnapshots,
                  getGlobalSnapshotByOrdinal,
                  mode
                )
                spendActions = combineSpendActions(
                  snapshots.flatMap(_.spendActions).toList,
                  lastUnsyncGlobalSnapshotOrdinal,
                  updatedLastSyncGlobalFromPeersInConsensus
                )
                _ <- recordMetric(
                  _.incrementCounterBy(
                    "dag_currency_l0_processed_history_total",
                    plan.carried.size,
                    Seq(Metrics.unsafeLabelName("outcome") -> "carried")
                  )
                )
                _ <- recordMetric(
                  _.incrementCounterBy(
                    "dag_currency_l0_processed_history_total",
                    plan.newlyRequired.size,
                    Seq(Metrics.unsafeLabelName("outcome") -> "processed")
                  )
                )
              } yield (spendActions, plan.cumulative)
          )
    }

  private def getLegacySpendActions(
    globalSnapshotViewOrdinal: SnapshotOrdinal,
    lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    currencyId: Address,
    metagraphSyncData: Option[SortedMap[Address, snapshot.MetagraphSyncDataInfo]],
    currentCurrencySnapshotOrdinal: SnapshotOrdinal,
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal,
    mode: DependencyMode
  ): F[(SortedMap[Address, List[SpendAction]], SortedSet[SnapshotOrdinal])] =
    metagraphSyncData.flatMap(_.get(currencyId)) match {
      case None => (SortedMap.empty[Address, List[SpendAction]], SortedSet.empty[SnapshotOrdinal]).pure[F]
      case Some(syncDataInfo) =>
        for {
          processed <- globalSnapshotsAlreadyProcessed.get
          byCurrencyOrdinal = processed.getOrElse(currencyId, Map.empty)
          allProcessed = byCurrencyOrdinal.valuesIterator.flatten.toSet
          alreadyForCurrent = byCurrencyOrdinal.getOrElse(currentCurrencySnapshotOrdinal, List.empty)
          newlyRequired = syncDataInfo.unappliedGlobalChangeOrdinals
            .filter(ordinal => ordinal <= globalSnapshotViewOrdinal && !allProcessed.contains(ordinal))
          required = (alreadyForCurrent ++ newlyRequired).toSet
          result <-
            if (required.isEmpty)
              (SortedMap.empty[Address, List[SpendAction]], SortedSet.empty[SnapshotOrdinal]).pure[F]
            else
              for {
                snapshots <- required.toList.sorted.traverse { ordinal =>
                  resolveGlobalSnapshot(
                    UnappliedSpendAction,
                    ordinal,
                    lastUnsyncGlobalSnapshotOrdinal,
                    lastGlobalSnapshots,
                    getGlobalSnapshotByOrdinal,
                    mode
                  )
                }
                spendActions = combineSpendActions(
                  snapshots.flatMap(_.spendActions),
                  lastUnsyncGlobalSnapshotOrdinal,
                  updatedLastSyncGlobalFromPeersInConsensus
                )
                _ <- globalSnapshotsAlreadyProcessed.update { current =>
                  val currentByOrdinal = current.getOrElse(currencyId, Map.empty)
                  val updatedByOrdinal = currentByOrdinal
                    .updated(
                      currentCurrencySnapshotOrdinal,
                      (currentByOrdinal.getOrElse(currentCurrencySnapshotOrdinal, List.empty) ++ newlyRequired).distinct.sorted
                    )
                    .toSeq
                    .sortBy(_._1.value.value)
                    .takeRight(retainedCount)
                    .toMap
                  current.updated(currencyId, updatedByOrdinal)
                }
              } yield (spendActions, newlyRequired)
        } yield result
    }

  private def resolveGlobalSnapshots(
    purpose: Purpose,
    ordinals: SortedSet[SnapshotOrdinal],
    parentOrdinal: SnapshotOrdinal,
    lastGlobalSnapshots: List[Hashed[GlobalIncrementalSnapshot]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    mode: DependencyMode
  ): F[List[Hashed[GlobalIncrementalSnapshot]]] =
    ordinals.toList.traverse { ordinal =>
      resolveGlobalSnapshot(purpose, ordinal, parentOrdinal, lastGlobalSnapshots, getGlobalSnapshotByOrdinal, mode)
    }

  private def combineSpendActions(
    spendActionsList: List[SortedMap[Address, List[SpendAction]]],
    lastUnsyncGlobalSnapshotOrdinal: SnapshotOrdinal,
    updatedLastSyncGlobalFromPeersInConsensus: SnapshotOrdinal
  ): SortedMap[Address, List[SpendAction]] =
    if (lastUnsyncGlobalSnapshotOrdinal > updatedLastSyncGlobalFromPeersInConsensus)
      spendActionsList.reduceOption(_ |+| _).getOrElse(SortedMap.empty)
    else
      spendActionsList.reduceOption(_ ++ _).getOrElse(SortedMap.empty)

  private def errorOutcome(error: Error): String = error match {
    case _: OutsideRetainedWindow       => "outside_retention"
    case _: MissingInsideRetainedWindow => "missing_recent"
  }

  private def recordDependency(purpose: Purpose, outcome: String): F[Unit] =
    recordDependencyBy(purpose, outcome, 1)

  private def recordDependencyBy(purpose: Purpose, outcome: String, count: Int): F[Unit] =
    recordMetric(
      _.incrementCounterBy(
        "dag_l0_state_channel_dependency_total",
        count,
        Seq(
          Metrics.unsafeLabelName("purpose") -> purpose.metricLabel,
          Metrics.unsafeLabelName("outcome") -> outcome
        )
      )
    )
}

object GlobalSnapshotOpsManager {
  sealed trait DependencyMode
  case object HistoricalReplay extends DependencyMode
  case object LiveBounded extends DependencyMode
  case object DeterministicHistory extends DependencyMode

  /** Signed Currency snapshot protocol 1.0.0 outranks the caller's replay mode. Once a lineage enters deterministic history, live creation
    * and historical recreation resolve dependencies identically from the retained consensus window.
    */
  def selectDependencyMode(historicalReplay: Boolean, deterministicHistoryActive: Boolean): DependencyMode =
    if (deterministicHistoryActive) DeterministicHistory
    else if (historicalReplay) HistoricalReplay
    else LiveBounded

  def make[F[_]: Async](
    lastGlobalSnapshotsSyncConfig: LastGlobalSnapshotsSyncConfig,
    globalSnapshotsAlreadyProcessed: SignallingRef[F, Map[Address, Map[SnapshotOrdinal, List[SnapshotOrdinal]]]],
    metrics: Option[Metrics[F]] = None
  ): GlobalSnapshotOpsManager[F] =
    new GlobalSnapshotOpsManager[F](lastGlobalSnapshotsSyncConfig, globalSnapshotsAlreadyProcessed, metrics)
}
