package io.constellationnetwork.node.shared.logger.sink

import cats.Applicative
import cats.effect._
import cats.syntax.all._

import io.constellationnetwork.node.shared.logger.{ConsensusLogger, LogContext}
import io.constellationnetwork.schema.peer.PeerId

import org.typelevel.log4cats.SelfAwareStructuredLogger

/** Simple consensus logger that writes to Slf4j.
  *
  * Collecting-phase methods are intentionally no-ops to avoid log spam. The round monitor in ConsensusRoundRunner provides periodic summary
  * logging with declaration counts, missing peers, and stall detection instead.
  */
object Slf4jConsensusLogger {

  def make[F[_]: Async](
    logger: SelfAwareStructuredLogger[F],
    ctxRef: Ref[F, LogContext]
  ): ConsensusLogger[F] =
    new ConsensusLogger[F] {

      def collectingFacilities(fs: List[PeerId]): F[Unit] = Applicative[F].unit
      def collectingProposals(fs: List[PeerId]): F[Unit] = Applicative[F].unit
      def collectingSignatures(fs: List[PeerId]): F[Unit] = Applicative[F].unit

      def roundFinished(fs: List[PeerId]): F[Unit] =
        ctxRef.get.flatMap { ctx =>
          val ordStr = ctx.ordinal.fold("unknown")(_.value.value.toString)
          logger.info(s"[CONSENSUS] Round finished ordinal=$ordStr facilitators=${fs.size}")
        }
    }
}
