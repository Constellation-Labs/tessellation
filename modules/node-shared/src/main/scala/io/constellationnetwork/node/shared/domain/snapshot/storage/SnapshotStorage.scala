package io.constellationnetwork.node.shared.domain.snapshot.storage

import cats.Eq
import cats.effect.MonadCancelThrow
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

trait SnapshotStorage[F[_], S <: Snapshot, State] {

  def prepend(snapshot: Signed[S], state: State)(implicit hasher: Hasher[F]): F[Boolean]

  def head: F[Option[(Signed[S], State)]]
  def headSnapshot: F[Option[Signed[S]]]

  def get(ordinal: SnapshotOrdinal): F[Option[Signed[S]]]
  def getHashed(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Option[Hashed[S]]]

  def get(hash: Hash): F[Option[Signed[S]]]
  def getHash(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[Option[Hash]]

  /** Reset head to the given snapshot for incremental recovery. Unlike prepend, this does not require sequential ordinals — it directly
    * sets the head.
    */
  def setHeadForRecovery(snapshot: Signed[S], state: State)(implicit hasher: Hasher[F]): F[Unit]

  /** Recovery-only exact durable replacement. Filesystem-backed stores override this to replace every persisted index before exposing the
    * head; the default preserves compatibility for in-memory and test stores.
    */
  def setHeadForRecoveryExact(snapshot: Signed[S], state: State)(implicit hasher: Hasher[F]): F[Unit] =
    setHeadForRecovery(snapshot, state)

  /** Execute suffix cleanup and exact anchor installation as one recovery critical section. Filesystem-backed stores override this so
    * ordinary enqueue/offload/cutoff fibers cannot recreate a stale successor between cleanup enumeration and anchor replacement.
    */
  def replaceCanonicalSuffixForRecovery(snapshot: Signed[S], state: State, cleanupSuffix: F[Unit])(
    implicit hasher: Hasher[F],
    F: MonadCancelThrow[F]
  ): F[Unit] =
    cleanupSuffix >> setHeadForRecoveryExact(snapshot, state)

}

object ExactSnapshotStorage {

  /** Exact install boundary for values whose downstream authority commits to the complete randomized signature envelope and context.
    *
    * The historical `prepend` contract intentionally treats the same snapshot-value hash as idempotent. Currency synchronous consensus is
    * stricter: its outer binary embeds one exact `Signed[CurrencyIncrementalSnapshot]`, and its private outcome carries the corresponding
    * context. A same-value/different-proofs or different-context head must therefore fail closed rather than report a successful install.
    */
  def prependExact[F[_]: MonadCancelThrow, S <: Snapshot: Eq, State: Eq](
    storage: SnapshotStorage[F, S, State],
    snapshot: Signed[S],
    state: State
  )(implicit hasher: Hasher[F]): F[Boolean] =
    storage.prepend(snapshot, state).flatMap { accepted =>
      if (!accepted) false.pure[F]
      else storage.head.map(exactHeadMatches(snapshot, state, _))
    }

  /** Install an already validated recovery authority exactly.
    *
    * Ordinary `prepend` intentionally treats an identical snapshot value as idempotent even when randomized signature bytes differ. That
    * behavior is correct for live finalization, where an envelope mismatch must fail closed. Rollback and download are different: their
    * inputs have already passed the complete recovery validation chain and are the canonical authority being installed. If the local head
    * is conflicting or retains a different proof envelope for the same value, reset it through the recovery-only storage primitive and
    * verify the exact readback.
    */
  def installExactForRecovery[F[_]: MonadCancelThrow, S <: Snapshot: Eq, State: Eq](
    storage: SnapshotStorage[F, S, State],
    snapshot: Signed[S],
    state: State
  )(implicit hasher: Hasher[F]): F[Boolean] =
    // Never short-circuit on the in-memory head. A retry can have an exact head
    // while stale future disk indexes remain from a crash during suffix cleanup.
    storage.setHeadForRecoveryExact(snapshot, state) >>
      storage.head.map(exactHeadMatches(snapshot, state, _))

  /** Validated rollback/download installation with suffix cleanup serialized against ordinary persistence. The operation is always
    * executed, even when the in-memory head already matches, so a partial previous attempt can converge idempotently.
    */
  def installCanonicalSuffixForRecovery[F[_]: MonadCancelThrow, S <: Snapshot: Eq, State: Eq](
    storage: SnapshotStorage[F, S, State],
    snapshot: Signed[S],
    state: State,
    cleanupSuffix: F[Unit]
  )(implicit hasher: Hasher[F]): F[Boolean] =
    storage.replaceCanonicalSuffixForRecovery(snapshot, state, cleanupSuffix) >>
      storage.head.map(exactHeadMatches(snapshot, state, _))

  private[storage] def exactHeadMatches[S: Eq, State: Eq](
    expectedSnapshot: Signed[S],
    expectedState: State,
    head: Option[(Signed[S], State)]
  ): Boolean =
    head.exists {
      case (storedSnapshot, storedState) =>
        storedSnapshot.value === expectedSnapshot.value &&
        storedSnapshot.proofs === expectedSnapshot.proofs &&
        storedState === expectedState
    }
}
