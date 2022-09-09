package org.tessellation.sdk.infrastructure.gossip

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.gossip.{CommonRumorRaw, RumorBatch, RumorRaw}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.metrics.Metrics.TagSeq

import eu.timepit.refined.auto._

object metrics {

  def updateRumorsReceived[F[_]: Monad: Metrics](batch: RumorBatch): F[Unit] =
    batch.traverse {
      case (_, signedRumor) =>
        val rumorTags = getRumorTags(signedRumor)
        Metrics[F].incrementCounter("dag_rumors_received_total", rumorTags)
    }.void

  def updateRumorsSent[F[_]: Monad: Metrics](batch: RumorBatch): F[Unit] =
    batch.traverse {
      case (_, signedRumor) =>
        val rumorTags = getRumorTags(signedRumor)
        Metrics[F].incrementCounter("dag_rumors_sent_total", rumorTags)
    }.void

  def updateRumorsConsumed[F[_]: Monad: Metrics](outcome: String, rumor: RumorRaw): F[Unit] =
    Metrics[F]
      .incrementCounter("dag_rumors_consumed_total", getRumorTags(rumor) :+ ("outcome", outcome))

  def updateRumorsSpread[F[_]: Monad: Metrics](rumor: RumorRaw): F[Unit] =
    Metrics[F].incrementCounter("dag_rumors_spread_total", getRumorTags(rumor))

  def getRumorTags(rumor: RumorRaw): TagSeq =
    Seq(
      ("content_type", rumor.contentType.value),
      ("content_type_short", rumor.contentType.value.replaceAll("\\w+\\.", "")),
      ("rumor_type", if (rumor.isInstanceOf[CommonRumorRaw]) "common" else "peer")
    )

  def incrementGossipRoundSucceeded[F[_]: Monad: Metrics]: F[Unit] =
    Metrics[F].incrementCounter("dag_gossip_round_succeeded_total")

  def updateRoundDurationSum[F[_]: Monad: Metrics](duration: FiniteDuration): F[Unit] =
    Metrics[F].incrementCounterBy("dag_gossip_round_duration_seconds_sum", duration.toUnit(TimeUnit.SECONDS))

}
