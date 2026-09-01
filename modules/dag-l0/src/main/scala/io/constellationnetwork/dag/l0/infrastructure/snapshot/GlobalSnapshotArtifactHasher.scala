package io.constellationnetwork.dag.l0.infrastructure.snapshot

import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalIncrementalSnapshotV1}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.{Hasher, JsonHash, KryoHash}

/** Typed names for the two existing Global L0 artifact-hash identities.
  *
  * A historical public parent link uses the V1 projection in the Kryo epoch and the current artifact in the JSON epoch. A live consensus
  * outcome always uses the current artifact hash. Keeping these names separate prevents a reconstructed outcome from accidentally using the
  * ordinal-selected parent-link identity. No new encoder or hash algorithm is introduced.
  */
object GlobalSnapshotArtifactHasher {

  def historicalHash[F[_]](artifact: GlobalIncrementalSnapshot)(implicit hasher: Hasher[F]): F[Hash] =
    hasher.getLogic(artifact.ordinal) match {
      case JsonHash => hasher.hash(artifact)
      case KryoHash => hasher.hash(GlobalIncrementalSnapshotV1.fromGlobalIncrementalSnapshot(artifact))
    }

  def currentHash[F[_]](artifact: GlobalIncrementalSnapshot)(implicit hasher: Hasher[F]): F[Hash] =
    hasher.hash(artifact)
}
