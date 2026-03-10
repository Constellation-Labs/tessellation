package io.constellationnetwork.node.shared.logger.sink

import cats.effect._
import cats.syntax.all._

import io.constellationnetwork.node.shared.logger.{ConsensusLogger, LogContext}
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Simple consensus logger that writes to Slf4j. Used as fallback when ClickHouse is not configured.
  */
object Slf4jConsensusLogger {

  def make[F[_]: Async](
    logger: SelfAwareStructuredLogger[F],
    ctxRef: Ref[F, LogContext]
  ): ConsensusLogger[F] =
    new ConsensusLogger[F] {

      private def log(event: String, facilitators: List[PeerId]): F[Unit] =
        ctxRef.get.flatMap { ctx =>
          val ordStr = ctx.ordinal.fold("unknown")(_.value.value.toString)
          logger.debug(s"[CONSENSUS] $event ordinal=$ordStr facilitators=${facilitators.size}")
        }

      def collectingFacilities(fs: List[PeerId]): F[Unit] = log("Collecting facilities", fs)
      def collectingProposals(fs: List[PeerId]): F[Unit] = log("Collecting proposals", fs)
      def collectingSignatures(fs: List[PeerId]): F[Unit] = log("Collecting signatures", fs)
      def roundFinished(fs: List[PeerId]): F[Unit] = log("Round finished", fs)
    }
}
