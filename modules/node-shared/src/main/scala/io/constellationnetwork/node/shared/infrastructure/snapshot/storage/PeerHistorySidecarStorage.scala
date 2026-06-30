package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.Async
import cats.syntax.all._

import io.constellationnetwork.schema.{ConsensusOperationalState, SnapshotOrdinal}

import fs2.io.file.{Files, Flags, Path}
import fs2.{Stream, text}
import io.circe.parser.decode
import io.circe.syntax._
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Node-local sidecar storage for `ConsensusOperationalState` (alpha.94 cold-restart fix).
  *
  * Background: every signed snapshot's `peerHistory` field is field-packed at PROPOSAL time -- i.e. before `Outcome[N]` exists -- so the
  * persisted value is `pack(Outcome[N-1])`. On rollback to snapshot N, `RollbackLoader` reads this field and seeds `state.lastOutcome` with
  * a value that is by-design one round stale. Pre-v19 this was below the chronic-classifier floor (10-30 observations) and harmless.
  * Post-v19 the same fields drive `eligibleFacilitators` / `removalPenalty` / `readmissionCountdown` computations directly, so a one-round
  * stale seed produces a different facilitator set at startup vs the live cluster -- which then surfaces as
  * `facilitator_set_mismatch_revalidate` loops in the consensus advancer.
  *
  * This sidecar is the post-finalization companion to the snapshot file: after `Outcome[N]` becomes the new `lastOutcome` on a node, we
  * persist the corresponding `ConsensusOperationalState` (i.e. `Outcome[N].toOperationalState`) under `<base>/<ordinal>.meta`. On a future
  * rollback to ordinal `N`, `RollbackLoader` reads the sidecar first and only falls back to `snapshot.value.peerHistory` (the stale value)
  * if the sidecar is absent or malformed.
  *
  * Trust model: this sidecar is node-local. It is NOT signed and NOT gossiped; only the writing node trusts its own sidecar on rollback. If
  * a snapshot directory is copied between machines, sidecars may not be transferred -- in that case the read falls through to the existing
  * peerHistory field and behavior matches pre-alpha.94. There is no cross-node sidecar comparison.
  *
  * Patterned after `CombinedSnapshotCheckpointFileSystemStorage`'s ETag sidecar -- same swallow-on-error policy, same orphan-tolerant read,
  * same compact ASCII filename. JSON payload rather than key=value because `ConsensusOperationalState` has too many fields for a
  * flat-string format; the existing circe codec on the case class is the canonical encoder.
  */
trait PeerHistorySidecarStorage[F[_]] {

  /** Best-effort write. Errors are logged and swallowed: a failed sidecar must not abort whatever consensus / snapshot work is happening on
    * the caller path. Worst case is the next cold-restart falls back to `snapshot.peerHistory` (pre-alpha.94 behavior).
    */
  def write(ordinal: SnapshotOrdinal, state: ConsensusOperationalState): F[Unit]

  /** Returns `None` when:
    *   - the sidecar file is absent (legacy snapshots written before alpha.94, or snapshots copied without their sidecars),
    *   - the file is structurally defective (partial write / disk corruption / hand-edited).
    *
    * On any defect, the WARN is emitted and the caller treats the result as "no sidecar" -- falling back to `snapshot.peerHistory`, which
    * is the existing one-round-stale behavior that pre-alpha.94 nodes relied on. We never fabricate a value.
    */
  def read(ordinal: SnapshotOrdinal): F[Option[ConsensusOperationalState]]

  /** Best-effort removal. Used by retention / eviction sweeps that drop snapshots above a rollback target. */
  def delete(ordinal: SnapshotOrdinal): F[Unit]
}

object PeerHistorySidecarStorage {

  // Co-located filenames mirror the ETag sidecar -- `.peerHistory.meta` is dotted so that the
  // base-storage's `_.toLongOption` directory filter (used to list snapshot ordinal files) silently
  // ignores these. The double-dot prefix prevents accidental collision with any future numeric
  // sidecar suffix.
  private[storage] val sidecarSuffix: String = ".peerHistory.meta"

  def make[F[_]: Async](base: Path): F[PeerHistorySidecarStorage[F]] = {
    implicit val files: Files[F] = Files.forAsync[F]
    files.createDirectories(base).as(new Impl[F](base))
  }

  private final class Impl[F[_]: Async](base: Path)(implicit F: Files[F]) extends PeerHistorySidecarStorage[F] {

    private val logger = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

    private def pathFor(ordinal: SnapshotOrdinal): Path =
      base / s"${ordinal.value.value}$sidecarSuffix"

    def write(ordinal: SnapshotOrdinal, state: ConsensusOperationalState): F[Unit] = {
      val target = pathFor(ordinal)
      val payload = state.asJson.noSpaces.getBytes("UTF-8")
      val ensureParent = target.parent.fold(Async[F].unit)(p => Files[F].createDirectories(p))
      val writeFile = Stream.emits(payload).through(Files[F].writeAll(target)).compile.drain
      (ensureParent >> writeFile).handleErrorWith { t =>
        logger.warn(t)(
          s"Failed to write peerHistory sidecar for ordinal ${ordinal.value.value}; rollback to this ordinal will fall back to snapshot.peerHistory (one-round stale)"
        )
      }
    }

    def read(ordinal: SnapshotOrdinal): F[Option[ConsensusOperationalState]] = {
      val target = pathFor(ordinal)
      Files[F].exists(target).flatMap {
        case false => none[ConsensusOperationalState].pure[F]
        case true =>
          Files[F]
            .readAll(target, 65536, Flags.Read)
            .through(text.utf8.decode)
            .compile
            .string
            .attempt
            .flatMap {
              case Right(content) =>
                decode[ConsensusOperationalState](content) match {
                  case Right(state) => state.some.pure[F]
                  case Left(err) =>
                    logger
                      .warn(
                        s"peerHistory sidecar for ordinal ${ordinal.value.value} failed JSON decode (${err.getMessage}); treating as miss"
                      )
                      .as(none[ConsensusOperationalState])
                }
              case Left(t) =>
                logger
                  .warn(t)(s"Failed to read peerHistory sidecar for ordinal ${ordinal.value.value}; treating as miss")
                  .as(none[ConsensusOperationalState])
            }
      }
    }

    def delete(ordinal: SnapshotOrdinal): F[Unit] = {
      val target = pathFor(ordinal)
      Files[F].deleteIfExists(target).void.handleErrorWith { t =>
        logger.warn(t)(
          s"Failed to delete peerHistory sidecar for ordinal ${ordinal.value.value}; orphan tolerated by read-time exists check"
        )
      }
    }
  }
}
