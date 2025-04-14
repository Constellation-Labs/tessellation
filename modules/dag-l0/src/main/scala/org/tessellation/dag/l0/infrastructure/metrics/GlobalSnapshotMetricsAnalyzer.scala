package org.tessellation.dag.l0.infrastructure.metrics

import cats.Parallel
import cats.effect.{Async, Clock, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.dag.l0.infrastructure.metrics.CurrencySnapshotInfoOps.CurrencySnapshotInfoOpsImpl
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.address.Address
import org.tessellation.security.{Hasher, HasherSelector}

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger

/**
 * Configuration for sampling metrics collection to optimize performance.
 * 
 * @param samplingInterval Default interval for collecting metrics (e.g., every N snapshots)
 * @param milestoneInterval Always collect metrics on milestone snapshots (every N snapshots)
 * @param timeThrottleInterval Minimum time between detailed metrics collection
 * @param balanceSizeThresholds Thresholds for balance map sizes to adjust sampling frequency
 */
case class MetricsSamplingConfig(
  samplingInterval: Int = 10,
  milestoneInterval: Int = 100,
  timeThrottleInterval: FiniteDuration = 5.minutes,
  balanceSizeThresholds: List[(Int, Int)] = List(
    (1000, 1),    // Small: collect every N snapshots
    (10000, 2),   // Medium: collect every N*2 snapshots
    (50000, 5),   // Large: collect every N*5 snapshots
    (Int.MaxValue, 10) // Very large: collect every N*10 snapshots
  )
)

trait GlobalSnapshotMetricsAnalyzer[F[_]] {
  def logStateChannelProcessingTime(operation: String, duration: FiniteDuration): F[Unit]
  def logStateChannelSize(address: Address, snapshotInfo: CurrencySnapshotInfo): F[Unit]
  def timeOperation[A](operation: String)(fa: F[A]): F[A]
  def analyzeCurrencySnapshot(snapshot: CurrencyIncrementalSnapshot): F[Unit]
  
  def analyzeBalanceMap(info: CurrencySnapshotInfo, address: Address): F[Unit]
  def trackStateProofTiming(info: CurrencySnapshotInfo, ordinal: SnapshotOrdinal): F[FiniteDuration]
  
  def logSnapshotProcessingBreakdown(
    ordinal: SnapshotOrdinal,
    proposalTime: FiniteDuration,
    stateProofTime: FiniteDuration,
    balanceSize: Int,
    totalTime: FiniteDuration
  ): F[Unit]
  
  def shouldCollectDetailedMetrics(
    ordinal: SnapshotOrdinal,
    balanceSize: Int
  ): F[Boolean]
  
  def recordBasicMetrics(
    globalInfo: org.tessellation.schema.GlobalSnapshotInfo, 
    ordinal: SnapshotOrdinal
  ): F[Unit]
}

object GlobalSnapshotMetricsAnalyzer {
  val DefaultConfig: MetricsSamplingConfig = MetricsSamplingConfig()

  def make[F[_] : Async : Parallel : Metrics : Clock : HasherSelector](
                                                                        config: MetricsSamplingConfig = DefaultConfig
                                                                      ): F[GlobalSnapshotMetricsAnalyzer[F]] =
    Slf4jLogger.create[F].flatMap { logger =>

      Ref.of[F, Option[FiniteDuration]](None).map { lastCollectionTimeRef =>
        new GlobalSnapshotMetricsAnalyzer[F] {
          def logStateChannelProcessingTime(operation: String, duration: FiniteDuration): F[Unit] =
            logger.debug(
              s"State channel processing: $operation, duration=${duration.toMillis}ms"
            ) >>
              Metrics[F].recordTime(
                "dag_statechannel_processing_time",
                duration,
                Seq(("operation", operation))
              )

          def logStateChannelSize(address: Address, snapshotInfo: CurrencySnapshotInfo): F[Unit] = {
            val addressBalance = snapshotInfo.balances.get(address).map(_.value.value).getOrElse(0L)
            val (totalSize, _, largeBalancesCount, smallBalancesCount) = snapshotInfo.analyzeBalanceMapSize

            logger.debug(
              s"State channel details: address=${address.show}, balanceValue=$addressBalance, " +
                s"totalBalances=$totalSize, largeBalancesCount=$largeBalancesCount, largeBalancesCount=$largeBalancesCount"
            ) >>
              Metrics[F].updateGauge("dag_total_balances_count", totalSize) >>
              Metrics[F].updateGauge("dag_large_balances_count", largeBalancesCount)
          }

          def timeOperation[A](operation: String)(fa: F[A]): F[A] =
            Clock[F].monotonic.flatMap { startNanos =>
              fa.flatTap { _ =>
                Clock[F].monotonic.flatMap { endNanos =>
                  val duration = endNanos - startNanos
                  logStateChannelProcessingTime(operation, duration)
                }
              }
            }

          def analyzeCurrencySnapshot(snapshot: CurrencyIncrementalSnapshot): F[Unit] = {
            val ordinal = snapshot.ordinal
            val blockCount = snapshot.blocks.size

            val logInfo = logger.debug(
              s"Analyzing Currency snapshot: ordinal=${ordinal.show}, " +
                s"blocks=$blockCount, " +
                s"has messages=${snapshot.messages.isDefined}, " +
                s"has fee transactions=${snapshot.feeTransactions.isDefined}"
            )

            val updateBlocksCount = Metrics[F].updateGauge(
              "dag_snapshot_blocks_count",
              blockCount,
              Seq(("ordinal", ordinal.show))
            )

            val updateMessagesCount = snapshot.messages.fold(Async[F].unit) { messages =>
              Metrics[F].updateGauge(
                "dag_snapshot_messages_count",
                messages.size,
                Seq(("ordinal", ordinal.show))
              )
            }

            val updateFeeTxCount = snapshot.feeTransactions.fold(Async[F].unit) { feeTxs =>
              Metrics[F].updateGauge(
                "dag_snapshot_fee_transactions_count",
                feeTxs.size,
                Seq(("ordinal", ordinal.show))
              )
            }

            logInfo >> (updateBlocksCount, updateMessagesCount, updateFeeTxCount).parMapN { (_, _, _) => () }
          }

          def analyzeBalanceMap(info: CurrencySnapshotInfo, address: Address): F[Unit] = {
            val balanceMapSize = info.balances.size
            val lastTxRefsSize = info.lastTxRefs.size
            val messageMapSize = info.lastMessages.map(_.size).getOrElse(0)
            val feeTxRefsSize = info.lastFeeTxRefs.map(_.size).getOrElse(0)

            val largeBalancesCount = info.balances.count { case (_, balance) => balance.value.value > CurrencySnapshotInfoOps.LargeBalanceThreshold }

            val logMetrics = logger.debug(
              s"CurrencySnapshotInfo metrics: address=${address.show}, " +
                s"balancesCount=$balanceMapSize, lastTxRefsCount=$lastTxRefsSize, " +
                s"messagesCount=$messageMapSize, feeTxRefsCount=$feeTxRefsSize, " +
                s"largeBalancesCount=$largeBalancesCount"
            )

            val updateBalanceCount = Metrics[F].updateGauge("dag_currency_balances_count", balanceMapSize)
            val updateTxRefsCount = Metrics[F].updateGauge("dag_currency_lasttxrefs_count", lastTxRefsSize)
            val updateMessageCount = Metrics[F].updateGauge("dag_currency_messages_count", messageMapSize)
            val updateFeeTxRefsCount = Metrics[F].updateGauge("dag_currency_feetxrefs_count", feeTxRefsSize)

            // Execute logging first, then run all metrics updates in parallel
            logMetrics >>
              (updateBalanceCount, updateTxRefsCount, updateMessageCount, updateFeeTxRefsCount).parMapN { (_, _, _, _) => () }
          }

          def trackStateProofTiming(info: CurrencySnapshotInfo, ordinal: SnapshotOrdinal): F[FiniteDuration] = {
            val balanceSize = info.balances.size

            Clock[F].monotonic.flatMap { startNanos =>
              HasherSelector[F].withCurrent { implicit hasher =>
                Hasher[F].hash(info.balances).flatMap { _ =>
                  Clock[F].monotonic.flatMap { endNanos =>
                    val duration = endNanos - startNanos

                    logger.debug(
                      s"Balance map hash time: ${duration.toMillis}ms for $balanceSize entries, ordinal=${ordinal.show}"
                    ) >>
                      Metrics[F].recordTime(
                        "dag_currency_balances_hash_time",
                        duration,
                        Seq(("size", balanceSize.toString))
                      ) >>
                      duration.pure[F]
                  }
                }
              }
            }
          }

          def logSnapshotProcessingBreakdown(
                                              ordinal: SnapshotOrdinal,
                                              proposalTime: FiniteDuration,
                                              stateProofTime: FiniteDuration,
                                              balanceSize: Int,
                                              totalTime: FiniteDuration
                                            ): F[Unit] = {
            val proposalPercentage = (proposalTime.toMillis * 100) / totalTime.toMillis.max(1)
            val stateProofPercentage = (stateProofTime.toMillis * 100) / totalTime.toMillis.max(1)

            logger.info(
              s"Snapshot processing breakdown for ordinal=${ordinal.show}:\n" +
                s"  Total time: ${totalTime.toMillis}ms\n" +
                s"  Proposal creation: ${proposalTime.toMillis}ms (${proposalPercentage}%)\n" +
                s"  State proof computation: ${stateProofTime.toMillis}ms (${stateProofPercentage}%)\n" +
                s"  Balance map size: $balanceSize addresses"
            ) >>
              Metrics[F].recordTime(
                "dag_snapshot_processing_breakdown",
                proposalTime,
                Seq(("phase", "proposal"), ("ordinal", ordinal.show))
              ) >>
              Metrics[F].recordTime(
                "dag_snapshot_processing_breakdown",
                stateProofTime,
                Seq(("phase", "state_proof"), ("ordinal", ordinal.show))
              ) >>
              Metrics[F].recordTime(
                "dag_snapshot_processing_by_balance_size",
                totalTime,
                Seq(("balances_count", balanceSize.toString))
              )
          }

          def shouldCollectDetailedMetrics(
                                            ordinal: SnapshotOrdinal,
                                            balanceSize: Int
                                          ): F[Boolean] = {
            for {
              currentTime <- Clock[F].monotonic
              // Always collect on milestone snapshots
              isMilestone = ordinal.value % config.milestoneInterval == 0

              // Find the appropriate multiplier based on balance size
              multiplier = config.balanceSizeThresholds
                .find { case (threshold, _) => balanceSize < threshold }
                .map(_._2)
                .getOrElse(config.balanceSizeThresholds.last._2)

              // Regular sampling based on adjusted frequency
              samplingFrequency = config.samplingInterval * multiplier
              isRegularSample = ordinal.value % samplingFrequency == 0

              // Time-based throttling
              lastTimeOpt <- lastCollectionTimeRef.get
              isTimeToCollect = lastTimeOpt match {
                case None => true // First collection
                case Some(lastTime) => (currentTime - lastTime) >= config.timeThrottleInterval
              }

              // Determine if we should collect metrics
              shouldCollect = isMilestone || (isTimeToCollect && isRegularSample)

              // Update the last collection time if we're collecting metrics
              _ <- lastCollectionTimeRef.set(Some(currentTime)).whenA(shouldCollect)
            } yield shouldCollect
          }

          def recordBasicMetrics(
                                  globalInfo: org.tessellation.schema.GlobalSnapshotInfo,
                                  ordinal: SnapshotOrdinal
                                ): F[Unit] = {
            val totalAddresses = globalInfo.balances.size
            val totalCurrencySnapshots = globalInfo.lastCurrencySnapshots.size

            Metrics[F].updateGauge("dag_global_snapshot_addresses_count", totalAddresses) >>
              Metrics[F].updateGauge("dag_global_snapshot_currencies_count", totalCurrencySnapshots) >>
              Metrics[F].updateGauge("dag_global_snapshot_tx_refs_count", globalInfo.lastTxRefs.size) >>
              logger.debug(
                s"Basic snapshot metrics: ordinal=${ordinal.show}, " +
                  s"addresses=$totalAddresses, currencies=$totalCurrencySnapshots"
              ).whenA(ordinal.value % (config.samplingInterval / 2) == 0) // Log less frequently
          }
        }
      }
    }
}
