package io.constellationnetwork.currency.l0.snapshot.storage

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Mutex
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency.CurrencyIncrementalSnapshot
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.json.JsonSerializer
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

/** Crash-safe local outbox for every finalized Currency state-channel binary.
  *
  * The binary contains the exact randomized Currency artifact proof envelope and cannot be reconstructed after a process stop from the
  * public Currency artifact alone. A finalization therefore prepares an entry before artifact persistence, marks it publishable only after
  * the exact artifact is durable, and removes it only when GL0 confirms that binary (or a later linked binary) on the canonical chain.
  *
  * This is local operational durability, not consensus authority or a public schema.
  */
trait StateChannelBinaryOutboxStorage[F[_]] {
  import StateChannelBinaryOutboxStorage.{Entry, Stats}

  def getCommitted(excluding: Set[Hash], limit: Int): F[List[Entry]]

  def stats: F[Stats]

  def prepare(
    binary: Hashed[StateChannelSnapshotBinary],
    currencyArtifact: Hashed[CurrencyIncrementalSnapshot]
  ): F[Entry]

  def markLocallyCommitted(binaryHash: Hash): F[Entry]

  def abortPrepared(binaryHash: Hash): F[Unit]

  /** Resolve a crash between prepare and commit.
    *
    * A committed receipt is itself the crash-safe proof that exact artifact persistence completed before the receipt was marked. It remains
    * authoritative after ordinary snapshot-info retention prunes the corresponding local context. Only an uncommitted prepared entry
    * requires exact artifact/state-proof readback before promotion.
    */
  def reconcilePrepared(
    getCurrencySnapshot: SnapshotOrdinal => F[Option[Hashed[CurrencyIncrementalSnapshot]]]
  ): F[List[Entry]]

  /** Confirmation of a descendant confirms its linked predecessors, matching the sender's FIFO chain invariant.
    */
  def confirm(confirmedHashes: Set[Hash]): F[List[Entry]]

  /** Reconcile through the canonical Currency ordinal/hash carried by validated GL0 state. This closes confirmations that aged out of the
    * incremental LastN window.
    */
  def confirmCanonicalTip(currencyOrdinal: SnapshotOrdinal, binaryHash: Hash): F[List[Entry]]

  /** Explicit canonical replacement authority.
    *
    * Both controlled rollback and validator download replace local Currency history. A downloaded public artifact does not authenticate an
    * unconfirmed state-channel binary envelope, so retaining a local suffix would let node-local randomized proof bytes influence what is
    * republished. The safe synchronous rule is to discard the redundant local publication copy before installing the replacement. Other
    * live facilitators retain the same finalized binary; a coordinated restart's rollback lead starts from the GL0-confirmed binary
    * instead.
    */
  def discardAllForCanonicalReplacement: F[Unit]
}

object StateChannelBinaryOutboxStorage {

  /** Local-file protocol discriminator. Revalidating a stored binary imports the exact JSON hashing/serialization implementation as a
    * durability invariant. Future encoder/hash evolution must add a historical verifier for this value (or require a proven-empty outbox at
    * upgrade); it must never reinterpret V1 with the new codec.
    */
  val CurrentEncodingVersion: String = "state-channel-binary-json-v1"
  val DefaultMaxEntries: Int = 4096
  val DefaultMaxSerializedBytes: Long = 128L * 1024L * 1024L

  final case class Stats(
    pendingCount: Int,
    serializedBytes: Long,
    oldestOrdinal: Option[SnapshotOrdinal],
    newestOrdinal: Option[SnapshotOrdinal]
  )

  @derive(encoder, decoder, eqv)
  final case class Entry(
    encodingVersion: String,
    binary: io.constellationnetwork.security.signature.Signed[StateChannelSnapshotBinary],
    binaryHash: Hash,
    proofsHash: ProofsHash,
    currencySnapshotOrdinal: SnapshotOrdinal,
    currencyArtifactHash: Hash,
    currencyArtifactProofsHash: ProofsHash,
    locallyCommitted: Boolean
  )

  final case class EntryConflict(ordinal: SnapshotOrdinal, existing: Hash, attempted: Hash)
      extends IllegalStateException(
        s"A different Currency binary is already prepared at ordinal=$ordinal: existing=$existing attempted=$attempted"
      )

  final case class CorruptEntry(ordinal: SnapshotOrdinal, expected: Hash, derived: Hash)
      extends IllegalStateException(
        s"Currency binary outbox hash mismatch at ordinal=$ordinal: expected=$expected derived=$derived"
      )

  final case class UnsupportedEncodingVersion(ordinal: SnapshotOrdinal, version: String)
      extends IllegalStateException(
        s"Unsupported Currency binary outbox encoding at ordinal=$ordinal version=$version"
      )

  final case class EntryNotPrepared(attempted: Hash)
      extends IllegalStateException(s"Currency binary outbox entry was not prepared for hash=$attempted")

  final case class CurrencyArtifactMismatch(ordinal: SnapshotOrdinal, expected: Hash, actual: Hash)
      extends IllegalStateException(
        s"Currency binary outbox artifact mismatch at ordinal=$ordinal expected=$expected actual=$actual"
      )

  final case class CanonicalTipMismatch(ordinal: SnapshotOrdinal, expected: Hash, actual: Hash)
      extends IllegalStateException(
        s"Currency binary outbox conflicts with canonical GL0 tip at ordinal=$ordinal expected=$expected actual=$actual"
      )

  final case class CapacityExceeded(
    attemptedOrdinal: Option[SnapshotOrdinal],
    pendingCount: Int,
    serializedBytes: Long,
    maxEntries: Int,
    maxSerializedBytes: Long
  ) extends IllegalStateException(
        s"Currency binary outbox capacity exceeded: attemptedOrdinal=$attemptedOrdinal pendingCount=$pendingCount/$maxEntries " +
          s"serializedBytes=$serializedBytes/$maxSerializedBytes"
      )

  private def fileName(ordinal: SnapshotOrdinal): String = s"${ordinal.value.value}.json"

  def make[F[_]: Async: Files: JsonSerializer](
    base: Path,
    maxEntries: Int = DefaultMaxEntries,
    maxSerializedBytes: Long = DefaultMaxSerializedBytes
  )(
    implicit hasher: io.constellationnetwork.security.Hasher[F]
  ): F[StateChannelBinaryOutboxStorage[F]] = {
    val logger = Slf4jLogger.getLoggerFromName[F]("StateChannelBinaryOutboxStorage")

    def validate(entry: Entry): F[Entry] =
      if (entry.encodingVersion =!= CurrentEncodingVersion)
        UnsupportedEncodingVersion(entry.currencySnapshotOrdinal, entry.encodingVersion).raiseError[F, Entry]
      else
        entry.binary.toHashed[F].flatMap { derived =>
          if (derived.hash === entry.binaryHash && derived.proofsHash === entry.proofsHash) entry.pure[F]
          else CorruptEntry(entry.currencySnapshotOrdinal, entry.binaryHash, derived.hash).raiseError[F, Entry]
        }

    def load: F[(Map[SnapshotOrdinal, Entry], Map[SnapshotOrdinal, Long])] =
      Files[F]
        .list(base)
        .filter(path => path.extName === ".json")
        .compile
        .toList
        .flatMap { paths =>
          paths.traverse(path => Files[F].size(path).map(path -> _)).flatMap { sizedPaths =>
            val totalBytes = sizedPaths.iterator.map(_._2).sum
            Async[F].raiseWhen(paths.size > maxEntries || totalBytes > maxSerializedBytes)(
              CapacityExceeded(none, paths.size, totalBytes, maxEntries, maxSerializedBytes)
            ) >>
              sizedPaths.traverse {
                case (path, size) =>
                  Files[F]
                    .readAll(path, 64 * 1024, Flags.Read)
                    .compile
                    .to(Array)
                    .flatMap(JsonSerializer[F].deserialize[Entry])
                    .flatMap(_.liftTo[F])
                    .flatMap(validate)
                    .map(entry => entry -> size)
              }
          }
        }
        .flatMap { loaded =>
          val entries = loaded.map(_._1)
          val grouped = entries.groupBy(_.currencySnapshotOrdinal)
          grouped.collectFirst { case (ordinal, duplicates) if duplicates.size =!= 1 => ordinal } match {
            case Some(ordinal) =>
              new IllegalStateException(s"Duplicate Currency binary outbox entries at ordinal=$ordinal")
                .raiseError[F, (Map[SnapshotOrdinal, Entry], Map[SnapshotOrdinal, Long])]
            case None =>
              (
                entries.map(entry => entry.currencySnapshotOrdinal -> entry).toMap,
                loaded.map { case (entry, size) => entry.currencySnapshotOrdinal -> size }.toMap
              ).pure[F]
          }
        }

    for {
      writer <- CrashSafeAtomicFileWriter.make[F](base)
      (initial, initialSizes) <- load
      state <- Ref.of[F, Map[SnapshotOrdinal, Entry]](initial)
      sizes <- Ref.of[F, Map[SnapshotOrdinal, Long]](initialSizes)
      mutex <- Mutex[F]
    } yield
      new StateChannelBinaryOutboxStorage[F] {

        private def write(entry: Entry): F[Long] =
          JsonSerializer[F]
            .serialize(entry)
            .flatMap(bytes => writer.write(fileName(entry.currencySnapshotOrdinal), bytes).as(bytes.length.toLong))

        private def replace(entry: Entry): F[Unit] =
          write(entry).flatMap { size =>
            state.update(_.updated(entry.currencySnapshotOrdinal, entry)) >>
              sizes.update(_.updated(entry.currencySnapshotOrdinal, size))
          }

        private def delete(entry: Entry): F[Unit] =
          writer.delete(fileName(entry.currencySnapshotOrdinal)).void >>
            state.update(_ - entry.currencySnapshotOrdinal) >>
            sizes.update(_ - entry.currencySnapshotOrdinal)

        def getCommitted(excluding: Set[Hash], limit: Int): F[List[Entry]] =
          state.get.map(
            _.values
              .filter(entry => entry.locallyCommitted && !excluding.contains(entry.binaryHash))
              .toList
              .sortBy(_.currencySnapshotOrdinal)
              .take(math.max(0, limit))
          )

        def stats: F[Stats] =
          (state.get, sizes.get).mapN { (entries, currentSizes) =>
            val ordinals = entries.keys.toList.sorted
            Stats(entries.size, currentSizes.valuesIterator.sum, ordinals.headOption, ordinals.lastOption)
          }

        def prepare(
          binary: Hashed[StateChannelSnapshotBinary],
          currencyArtifact: Hashed[CurrencyIncrementalSnapshot]
        ): F[Entry] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              val entry = Entry(
                CurrentEncodingVersion,
                binary.signed,
                binary.hash,
                binary.proofsHash,
                currencyArtifact.ordinal,
                currencyArtifact.hash,
                currencyArtifact.proofsHash,
                locallyCommitted = false
              )

              state.get.flatMap(_.get(entry.currencySnapshotOrdinal) match {
                case Some(existing)
                    if existing.binaryHash === entry.binaryHash &&
                      existing.currencyArtifactHash === entry.currencyArtifactHash &&
                      existing.currencyArtifactProofsHash === entry.currencyArtifactProofsHash =>
                  existing.pure[F]
                case Some(existing) =>
                  EntryConflict(entry.currencySnapshotOrdinal, existing.binaryHash, entry.binaryHash).raiseError[F, Entry]
                case None =>
                  JsonSerializer[F]
                    .serialize(entry)
                    .flatMap { bytes =>
                      sizes.get.flatMap { currentSizes =>
                        val nextCount = currentSizes.size + 1
                        val nextBytes = currentSizes.valuesIterator.sum + bytes.length.toLong
                        Async[F].raiseWhen(nextCount > maxEntries || nextBytes > maxSerializedBytes)(
                          CapacityExceeded(
                            entry.currencySnapshotOrdinal.some,
                            nextCount,
                            nextBytes,
                            maxEntries,
                            maxSerializedBytes
                          )
                        ) >>
                          writer.write(fileName(entry.currencySnapshotOrdinal), bytes) >>
                          state.update(_.updated(entry.currencySnapshotOrdinal, entry)) >>
                          sizes.update(_.updated(entry.currencySnapshotOrdinal, bytes.length.toLong))
                      }
                    }
                    .as(entry)
              })
            }
          }

        def markLocallyCommitted(binaryHash: Hash): F[Entry] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap { entries =>
                entries.values.find(_.binaryHash === binaryHash) match {
                  case Some(entry) if entry.locallyCommitted => entry.pure[F]
                  case Some(entry) =>
                    val committed = entry.copy(locallyCommitted = true)
                    replace(committed).as(committed)
                  case None => EntryNotPrepared(binaryHash).raiseError[F, Entry]
                }
              }
            }
          }

        def abortPrepared(binaryHash: Hash): F[Unit] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap { entries =>
                entries.values.find(_.binaryHash === binaryHash) match {
                  case Some(entry) if !entry.locallyCommitted => delete(entry)
                  case _                                      => Async[F].unit
                }
              }
            }
          }

        def reconcilePrepared(
          getCurrencySnapshot: SnapshotOrdinal => F[Option[Hashed[CurrencyIncrementalSnapshot]]]
        ): F[List[Entry]] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get
                .flatMap(_.values.toList.sortBy(_.currencySnapshotOrdinal).traverse { entry =>
                  getCurrencySnapshot(entry.currencySnapshotOrdinal).flatMap {
                    case None if entry.locallyCommitted =>
                      // Snapshot-info retention may legitimately remove the local readback
                      // long before GL0 confirms a publication. The committed receipt was
                      // written only after exact artifact persistence completed.
                      entry.some.pure[F]
                    case Some(snapshot)
                        if entry.locallyCommitted &&
                          snapshot.hash === entry.currencyArtifactHash &&
                          snapshot.proofsHash === entry.currencyArtifactProofsHash =>
                      entry.some.pure[F]
                    case Some(snapshot) if entry.locallyCommitted =>
                      CurrencyArtifactMismatch(entry.currencySnapshotOrdinal, entry.currencyArtifactHash, snapshot.hash)
                        .raiseError[F, Option[Entry]]
                    case None => delete(entry).as(none[Entry])
                    case Some(snapshot)
                        if snapshot.hash === entry.currencyArtifactHash &&
                          snapshot.proofsHash === entry.currencyArtifactProofsHash =>
                      val committed = entry.copy(locallyCommitted = true)
                      replace(committed).as(committed.some)
                    case Some(_) =>
                      // Prepared is not publication authority. This is the normal recovery
                      // from a crash after artifact persistence rejected a competing value
                      // but before abortPrepared could erase the provisional receipt.
                      delete(entry).as(none[Entry])
                  }
                })
                .map(_.flatten)
            }
          }

        def confirm(confirmedHashes: Set[Hash]): F[List[Entry]] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap { entries =>
                val ordered = entries.values.filter(_.locallyCommitted).toList.sortBy(_.currencySnapshotOrdinal)
                val highestConfirmedIndex = ordered.lastIndexWhere(entry => confirmedHashes.contains(entry.binaryHash))
                val confirmed = if (highestConfirmedIndex < 0) List.empty else ordered.take(highestConfirmedIndex + 1)
                confirmed.traverse_(delete).as(confirmed)
              }
            }
          }

        def confirmCanonicalTip(currencyOrdinal: SnapshotOrdinal, binaryHash: Hash): F[List[Entry]] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap { entries =>
                entries.get(currencyOrdinal) match {
                  case Some(entry) if entry.binaryHash =!= binaryHash =>
                    CanonicalTipMismatch(currencyOrdinal, entry.binaryHash, binaryHash).raiseError[F, List[Entry]]
                  case _ =>
                    val ordered = entries.values.toList.sortBy(_.currencySnapshotOrdinal)
                    val firstPending = ordered.find(_.currencySnapshotOrdinal > currencyOrdinal)
                    val missingCanonicalBridge = firstPending.exists(_.currencySnapshotOrdinal > currencyOrdinal.next)
                    val pendingIsAttached = firstPending.forall(entry =>
                      entry.currencySnapshotOrdinal === currencyOrdinal.next && entry.binary.value.lastSnapshotHash === binaryHash
                    )
                    val firstPendingHash = firstPending.fold(binaryHash)(_.binary.value.lastSnapshotHash)

                    // A downloaded validator intentionally discards its local publication copies. It can therefore have a valid pending
                    // binary N+1 while its local GL0 view still confirms only N-1. The missing binary N may already be queued at GL0 and
                    // will close the gap in a later Global snapshot. Keep the suffix pending until the canonical tip reaches N; then the
                    // ordinary direct-parent or same-ordinal hash check below can prove it. A direct conflicting successor still fails.
                    if (missingCanonicalBridge)
                      logger
                        .info(
                          s"Waiting for canonical Currency bridge: canonicalTipOrdinal=${currencyOrdinal.show} " +
                            s"firstPendingOrdinal=${firstPending.fold("none")(_.currencySnapshotOrdinal.show)}"
                        )
                        .as(List.empty[Entry])
                    else
                      Async[F].raiseUnless(pendingIsAttached)(
                        CanonicalTipMismatch(
                          firstPending.fold(currencyOrdinal.next)(_.currencySnapshotOrdinal),
                          binaryHash,
                          firstPendingHash
                        )
                      ) >> {
                        val confirmed = ordered
                          .filter(entry =>
                            entry.locallyCommitted &&
                              (entry.currencySnapshotOrdinal < currencyOrdinal ||
                                (entry.currencySnapshotOrdinal === currencyOrdinal && entry.binaryHash === binaryHash))
                          )
                        confirmed.traverse_(delete).as(confirmed)
                      }
                }
              }
            }
          }

        def discardAllForCanonicalReplacement: F[Unit] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap(_.values.toList.traverse_(delete))
            }
          }
      }
  }
}
