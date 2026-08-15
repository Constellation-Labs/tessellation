package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.effect.Async

import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalIncrementalSnapshotV1}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, JsonHash, KryoHash}

/** One typed implementation of the historical Global L0 artifact-hash rule.
  *
  * The Kryo epoch committed the V1 projection; the JSON epoch commits the current artifact. Callers still choose whether they need the
  * ordinal-selected historical hasher or the current consensus hasher, but cannot accidentally hash the modern case class with Kryo.
  */
object GlobalSnapshotArtifactHasher {

  def hash[F[_]: Async](artifact: GlobalIncrementalSnapshot)(implicit hasher: Hasher[F]): F[Hash] =
    hasher.getLogic(artifact.ordinal) match {
      case JsonHash => hasher.hash(artifact)
      case KryoHash => hasher.hash(GlobalIncrementalSnapshotV1.fromGlobalIncrementalSnapshot(artifact))
    }

  def toHashed[F[_]: Async](artifact: Signed[GlobalIncrementalSnapshot])(implicit hasher: Hasher[F]): F[Hashed[GlobalIncrementalSnapshot]] =
    for {
      artifactHash <- hash[F](artifact.value)
      proofsHash <- artifact.proofsHash[F]
    } yield Hashed(artifact, artifactHash, proofsHash)
}
