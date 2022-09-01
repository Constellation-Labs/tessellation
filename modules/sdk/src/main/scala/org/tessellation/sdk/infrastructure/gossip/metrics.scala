package org.tessellation.sdk.infrastructure.gossip

import java.util.concurrent.TimeUnit

import cats.Monad
import cats.syntax.all._

import scala.concurrent.duration.FiniteDuration

import org.tessellation.schema.gossip.{CommonRumorBinary, RumorBatch, RumorBinary}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.metrics.Metrics.TagSeq
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._

object metrics {

  def updateRumorsReceived[F[_]: Monad: Metrics](batch: RumorBatch): F[Unit] =
    batch.traverse {
      case (_, signedRumor) =>
        val rumorTags = getRumorTags(signedRumor)
        val rumorSize = signedRumor.value.content.length
        Metrics[F].incrementCounter("dag_rumors_received_total", rumorTags) >>
          Metrics[F].incrementCounterBy("dag_rumors_received_bytes_total", rumorSize, rumorTags)
    }.void

  def updateRumorsSent[F[_]: Monad: Metrics](batch: RumorBatch): F[Unit] =
    batch.traverse {
      case (_, signedRumor) =>
        val rumorTags = getRumorTags(signedRumor)
        val rumorSize = signedRumor.value.content.length
        Metrics[F].incrementCounter("dag_rumors_sent_total", rumorTags) >>
          Metrics[F].incrementCounterBy("dag_rumors_sent_bytes_total", rumorSize, rumorTags)
    }.void

  def updateRumorsConsumed[F[_]: Monad: Metrics](outcome: String, signedRumor: Signed[RumorBinary]): F[Unit] =
    Metrics[F]
      .incrementCounter("dag_rumors_consumed_total", getRumorTags(signedRumor) :+ ("outcome", outcome))

  def updateRumorsSpread[F[_]: Monad: Metrics](signedRumor: Signed[RumorBinary]): F[Unit] =
    Metrics[F].incrementCounter("dag_rumors_spread_total", getRumorTags(signedRumor))

  def getRumorTags(signedRumor: Signed[RumorBinary]): TagSeq =
    Seq(
      ("content_type", signedRumor.contentType.value),
      ("content_type_short", signedRumor.contentType.value.replaceAll("\\w+\\.", "")),
      ("rumor_type", if (signedRumor.value.isInstanceOf[CommonRumorBinary]) "common" else "peer")
    )

  def incrementGossipRoundSucceeded[F[_]: Monad: Metrics]: F[Unit] =
    Metrics[F].incrementCounter("dag_gossip_round_succeeded_total")

  def updateRoundDurationSum[F[_]: Monad: Metrics](duration: FiniteDuration): F[Unit] =
    Metrics[F].incrementCounterBy("dag_gossip_round_duration_seconds_sum", duration.toUnit(TimeUnit.SECONDS))

}
