package io.constellationnetwork.schema.stateproof

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.{SnapshotInfo, StateProof}
import io.constellationnetwork.security.Hasher

/** Typeclass for building state proofs from snapshot info.
  *
  * This abstraction allows different proof strategies (legacy Merkle trees vs MPT) to be selected at runtime based on ordinal, without
  * requiring unused dependencies to be passed through the call stack.
  *
  * @tparam F
  *   Effect type
  * @tparam I
  *   Snapshot info type (e.g., GlobalSnapshotInfo, CurrencySnapshotInfo)
  * @tparam P
  *   State proof type (e.g., GlobalSnapshotStateProof, CurrencySnapshotStateProof)
  */
trait StateProofBuilder[F[_], I <: SnapshotInfo[P], P <: StateProof] {

  /** Build a state proof from snapshot info at the given ordinal.
    *
    * The ordinal is used to determine which proof format to use when the builder supports multiple formats (e.g., legacy vs MPT).
    */
  def buildProof(info: I, ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[P]
}

object StateProofBuilder {

  /** Create a StateProofBuilder instance from a function. */
  def instance[F[_], I <: SnapshotInfo[P], P <: StateProof](
    f: (I, SnapshotOrdinal, Hasher[F]) => F[P]
  ): StateProofBuilder[F, I, P] =
    new StateProofBuilder[F, I, P] {
      def buildProof(info: I, ordinal: SnapshotOrdinal)(implicit hasher: Hasher[F]): F[P] = f(info, ordinal, hasher)
    }
}
