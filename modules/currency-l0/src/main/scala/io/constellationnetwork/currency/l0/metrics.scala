package io.constellationnetwork.currency.l0

import cats.Monad
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.services.{State => SendBinaryState}
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.TagSeq

import eu.timepit.refined.auto._

object metrics {

  def updateStateChannelRetryParametersMetrics[F[_]: Monad: Metrics](state: SendBinaryState): F[Unit] = {
    val stateTags = getSendBinaryTags(state)
    Metrics[F].incrementCounter("dag_binaries_state_channel_retry_total", stateTags)
  }

  def updateFailedConfirmingStateChannelBinaryMetrics[F[_]: Monad: Metrics](): F[Unit] =
    Metrics[F].incrementCounter("dag_binaries_failed_confirmation")

  private def getSendBinaryTags(state: SendBinaryState): TagSeq =
    Seq(
      ("binaries_tracked_number", state.tracked.size.toString),
      ("binaries_cap", state.cap.toString),
      ("binaries_retry_mode", state.retryMode.toString),
      ("binaries_no_confirmations_since_retry_count", state.noConfirmationsSinceRetryCount.toString),
      ("binaries_backoff_exponent", state.backoffExponent.toString)
    )

}
