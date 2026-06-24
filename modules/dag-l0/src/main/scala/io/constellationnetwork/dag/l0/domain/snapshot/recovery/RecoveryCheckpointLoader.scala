package io.constellationnetwork.dag.l0.domain.snapshot.recovery

import cats.effect.Async
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import fs2.io.file.{Files, Path}
import fs2.text
import io.circe.parser.decode
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Loads and verifies the optional seedlist-signed recovery checkpoint at node startup.
  *
  * Fail-fast: a configured-but-invalid checkpoint (unreadable, undecodable, wrong network, insufficient or
  * non-seedlist signatures) aborts startup -- a fork anchor must never be silently ignored. Returns None
  * only when no checkpoint is configured (the gate is then inert and recovery falls back to L1 signature
  * validation plus source corroboration).
  */
object RecoveryCheckpointLoader {

  case object CheckpointConfiguredWithoutSeedlist extends NoStackTrace {
    override def getMessage: String =
      "recovery checkpoint is configured but no seedlist is present; the checkpoint cannot be verified"
  }

  case class CheckpointFileUnreadable(path: String, reason: String) extends NoStackTrace {
    override def getMessage: String = s"recovery checkpoint file '$path' could not be read or parsed: $reason"
  }

  def load[F[_]: Async: SecurityProvider: Files](
    checkpointPath: Option[String],
    seedlist: Option[Set[PeerId]],
    expectedNetwork: String,
    signedValidator: SignedValidator[F]
  )(implicit hasher: Hasher[F]): F[Option[RecoveryCheckpoint]] = {
    val logger = Slf4jLogger.getLogger[F]

    checkpointPath.flatTraverse { rawPath =>
      seedlist match {
        case None => CheckpointConfiguredWithoutSeedlist.raiseError[F, Option[RecoveryCheckpoint]]
        case Some(peers) =>
          for {
            content <- Files[F]
              .readAll(Path(rawPath))
              .through(text.utf8.decode)
              .compile
              .string
              .adaptError { case e => CheckpointFileUnreadable(rawPath, e.getMessage) }
            signed <- decode[Signed[RecoveryCheckpoint]](content)
              .leftMap(e => CheckpointFileUnreadable(rawPath, e.getMessage): Throwable)
              .liftTo[F]
            verified <- RecoveryCheckpoint.verify(signedValidator, peers, expectedNetwork, signed)
            checkpoint <- verified.liftTo[F]
            _ <- logger.info(
              s"[RecoveryCheckpoint] loaded and verified: ordinal=${checkpoint.ordinal.show} " +
                s"hash=${checkpoint.snapshotHash.show.take(8)} network=${checkpoint.network} signers=${signed.proofs.length}"
            )
          } yield checkpoint.some
      }
    }
  }
}
