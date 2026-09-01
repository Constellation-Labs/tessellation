package io.constellationnetwork.currency.l0.snapshot.storage

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Mutex
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSentGlobalSnapshotSyncStorage.RequiredRecoveryRefresh
import io.constellationnetwork.node.shared.infrastructure.storage.CrashSafeAtomicFileWriter
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import fs2.io.file.{Files, Flags, Path}
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** Durable outbox for the exact Currency snapshot binary that carries a rollback-recovery sync refresh.
  *
  * Local Currency consensus is not terminal success: Global L0 must include this exact hash before a later Currency snapshot may safely
  * build on it. The ordinary binary tracker is deliberately process-local, so this one-shot predecessor is retained separately across
  * restarts and is re-enqueued until canonical Global L0 confirmation.
  */
trait RecoverySyncPublicationStorage[F[_]] {
  import RecoverySyncPublicationStorage.Publication

  def get: F[Option[Publication]]

  /** Persist a non-publishable intent before the Currency artifact is written locally.
    *
    * Repeating the same transition is idempotent; replacing a still-live different publication fails closed. The sender restores only an
    * intent whose exact Currency artifact has subsequently been committed.
    */
  def prepare(
    required: RequiredRecoveryRefresh,
    binary: Hashed[StateChannelSnapshotBinary],
    currencyArtifact: Hashed[CurrencyIncrementalSnapshot]
  ): F[Publication]

  /** Mark the prepared exact binary publishable immediately after its Currency artifact is durably present. */
  def markLocallyCommitted(binaryHash: Hash): F[Publication]

  /** Remove an uncommitted intent after a definitively rejected local persist. Never removes a committed publication. */
  def abortPrepared(binaryHash: Hash): F[Unit]

  /** Resolve a process death between intent creation and local-commit marking against durable Currency snapshot storage. */
  def reconcilePrepared(
    getCurrencySnapshot: SnapshotOrdinal => F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  ): F[Option[Publication]]

  /** Remove the durable outbox entry only after its exact binary hash appears in a canonical Global snapshot. */
  def confirm(confirmedHashes: Set[Hash]): F[Option[Publication]]

  /** Stop resending once the deterministic retained-window deadline has passed, while preserving the receipt for diagnosis/recovery. */
  def expireAt(globalParent: SnapshotOrdinal): F[Option[Publication]]

  /** Explicit canonical-history replacement authority. A controlled rollback or validated download supersedes any prior recovery
    * publication, which must not be reposted or block the adopted lineage. Ordinary process restart never calls this.
    */
  def discardForCanonicalReplacement: F[Option[Publication]]
}

object RecoverySyncPublicationStorage {
  private val FileName = "pending.json"

  @derive(encoder, decoder, eqv)
  final case class Publication(
    refresh: io.constellationnetwork.security.signature.Signed[
      io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSnapshotSync
    ],
    mode: String,
    validThroughGlobalParent: SnapshotOrdinal,
    binary: io.constellationnetwork.security.signature.Signed[StateChannelSnapshotBinary],
    binaryHash: Hash,
    proofsHash: ProofsHash,
    currencySnapshotOrdinal: SnapshotOrdinal,
    currencyArtifactHash: Hash,
    currencyArtifactProofsHash: ProofsHash,
    locallyCommitted: Boolean,
    expired: Boolean
  )

  final case class PublicationConflict(existing: Hash, attempted: Hash)
      extends IllegalStateException(
        s"A different recovery binary is already pending Global L0 confirmation: existing=$existing attempted=$attempted"
      )

  final case class CorruptPublication(expected: Hash, derived: Hash)
      extends IllegalStateException(s"Recovery publication hash mismatch on disk: expected=$expected derived=$derived")

  final case class PublicationNotPrepared(attempted: Hash)
      extends IllegalStateException(s"Recovery publication was not prepared for binary hash=$attempted")

  final case class LocalCurrencyArtifactMismatch(ordinal: SnapshotOrdinal, expected: Hash, actual: Hash)
      extends IllegalStateException(
        s"Recovery publication local Currency artifact mismatch at ordinal=$ordinal expected=$expected actual=$actual"
      )

  def make[F[_]: Async: Files: JsonSerializer](
    base: Path
  )(implicit hasher: io.constellationnetwork.security.Hasher[F]): F[RecoverySyncPublicationStorage[F]] = {
    val logger = Slf4jLogger.getLoggerFromName[F]("RecoverySyncPublicationStorage")
    val target = base / FileName

    def load: F[Option[Publication]] =
      Files[F]
        .exists(target)
        .ifM(
          Files[F]
            .readAll(target, 64 * 1024, Flags.Read)
            .compile
            .to(Array)
            .flatMap(JsonSerializer[F].deserialize[Publication])
            .flatMap(_.liftTo[F])
            .flatMap { publication =>
              publication.binary.toHashed[F].flatMap { derived =>
                if (derived.hash === publication.binaryHash && derived.proofsHash === publication.proofsHash)
                  publication.some.pure[F]
                else CorruptPublication(publication.binaryHash, derived.hash).raiseError[F, Option[Publication]]
              }
            },
          none[Publication].pure[F]
        )

    for {
      writer <- CrashSafeAtomicFileWriter.make[F](base)
      initial <- load
      state <- Ref.of[F, Option[Publication]](initial)
      mutex <- Mutex[F]
    } yield
      new RecoverySyncPublicationStorage[F] {

        def get: F[Option[Publication]] = state.get

        private def persist(publication: Publication): F[Unit] =
          JsonSerializer[F].serialize(publication).flatMap(writer.write(FileName, _)) >> state.set(publication.some)

        def prepare(
          required: RequiredRecoveryRefresh,
          binary: Hashed[StateChannelSnapshotBinary],
          currencyArtifact: Hashed[CurrencyIncrementalSnapshot]
        ): F[Publication] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              val publication = Publication(
                required.value,
                required.mode.metricLabel,
                required.validThroughGlobalParent,
                binary.signed,
                binary.hash,
                binary.proofsHash,
                currencyArtifact.ordinal,
                currencyArtifact.hash,
                currencyArtifact.proofsHash,
                locallyCommitted = false,
                expired = false
              )

              state.get.flatMap {
                case Some(existing) if existing.binaryHash === publication.binaryHash => existing.pure[F]
                case Some(existing) if !existing.expired =>
                  PublicationConflict(existing.binaryHash, publication.binaryHash).raiseError[F, Publication]
                case _ =>
                  persist(publication) >>
                    logger
                      .warn(
                        s"RECOVERY_SYNC_PUBLICATION_PREPARED mode=${publication.mode} currencyOrdinal=${currencyArtifact.ordinal} " +
                          s"binaryHash=${publication.binaryHash} validThrough=${publication.validThroughGlobalParent}"
                      )
                      .as(publication)
              }
            }
          }

        def markLocallyCommitted(binaryHash: Hash): F[Publication] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap {
                case Some(publication) if publication.binaryHash === binaryHash && publication.locallyCommitted =>
                  publication.pure[F]
                case Some(publication) if publication.binaryHash === binaryHash =>
                  val committed = publication.copy(locallyCommitted = true)
                  persist(committed) >>
                    logger
                      .warn(
                        s"RECOVERY_SYNC_PUBLICATION_LOCAL_COMMITTED mode=${committed.mode} " +
                          s"currencyOrdinal=${committed.currencySnapshotOrdinal} binaryHash=${committed.binaryHash}"
                      )
                      .as(committed)
                case _ => PublicationNotPrepared(binaryHash).raiseError[F, Publication]
              }
            }
          }

        def abortPrepared(binaryHash: Hash): F[Unit] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap {
                case Some(publication) if publication.binaryHash === binaryHash && !publication.locallyCommitted =>
                  writer.delete(FileName) >> state.set(none)
                case _ => Async[F].unit
              }
            }
          }

        def reconcilePrepared(
          getCurrencySnapshot: SnapshotOrdinal => F[Option[Hashed[CurrencyIncrementalSnapshot]]]
        ): F[Option[Publication]] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap {
                case None => none[Publication].pure[F]
                case current @ Some(publication) if publication.locallyCommitted =>
                  (current: Option[Publication]).pure[F]
                case Some(publication) =>
                  getCurrencySnapshot(publication.currencySnapshotOrdinal).flatMap {
                    case Some(snapshot)
                        if snapshot.hash === publication.currencyArtifactHash &&
                          snapshot.proofsHash === publication.currencyArtifactProofsHash =>
                      val committed = publication.copy(locallyCommitted = true)
                      persist(committed) >>
                        logger
                          .warn(
                            s"RECOVERY_SYNC_PUBLICATION_RECONCILED currencyOrdinal=${committed.currencySnapshotOrdinal} " +
                              s"binaryHash=${committed.binaryHash} outcome=local_commit_recovered"
                          )
                          .as(committed.some)
                    case None =>
                      writer.delete(FileName) >> state.set(none) >>
                        logger
                          .warn(
                            s"RECOVERY_SYNC_PUBLICATION_RECONCILED currencyOrdinal=${publication.currencySnapshotOrdinal} " +
                              s"binaryHash=${publication.binaryHash} outcome=uncommitted_intent_discarded"
                          )
                          .as(none[Publication])
                    case Some(snapshot) =>
                      LocalCurrencyArtifactMismatch(
                        publication.currencySnapshotOrdinal,
                        publication.currencyArtifactHash,
                        snapshot.hash
                      ).raiseError[F, Option[Publication]]
                  }
              }
            }
          }

        def confirm(confirmedHashes: Set[Hash]): F[Option[Publication]] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap {
                case Some(publication) if publication.locallyCommitted && confirmedHashes.contains(publication.binaryHash) =>
                  writer.delete(FileName) >> state.set(none) >> publication.some.pure[F]
                case _ => none[Publication].pure[F]
              }
            }
          }

        def expireAt(globalParent: SnapshotOrdinal): F[Option[Publication]] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap {
                case Some(publication)
                    if publication.locallyCommitted && !publication.expired && globalParent > publication.validThroughGlobalParent =>
                  val expired = publication.copy(expired = true)
                  persist(expired) >> expired.some.pure[F]
                case _ => none[Publication].pure[F]
              }
            }
          }

        def discardForCanonicalReplacement: F[Option[Publication]] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap {
                case current @ Some(publication) =>
                  writer.delete(FileName) >> state.set(none) >>
                    logger
                      .warn(
                        s"RECOVERY_SYNC_PUBLICATION_DISCARDED_FOR_CANONICAL_REPLACEMENT mode=${publication.mode} " +
                          s"currencyOrdinal=${publication.currencySnapshotOrdinal} binaryHash=${publication.binaryHash}"
                      )
                      .as(current)
                case None => none[Publication].pure[F]
              }
            }
          }
      }
  }
}
