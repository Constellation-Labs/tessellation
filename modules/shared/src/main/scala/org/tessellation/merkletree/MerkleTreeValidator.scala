package org.tessellation.merkletree

import cats.MonadThrow
import cats.data.Validated
import cats.syntax.eq._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.{IncrementalSnapshot, SnapshotInfo}
import org.tessellation.security.{Hashed, hash}

import derevo.cats.{eqv, show}
import derevo.derive

object MerkleTreeValidator {

  def validate[F[_]: MonadThrow: KryoSerializer, A <: IncrementalSnapshot[_, _]](
    snapshot: Hashed[A],
    si: SnapshotInfo
  ): F[Validated[MerkleTreeBroken, Unit]] = si.stateProof.map(validate(snapshot, _))

  def validate[A <: IncrementalSnapshot[_, _]](
    snapshot: Hashed[A],
    merkleTree: MerkleTree
  ): Validated[MerkleTreeBroken, Unit] =
    Validated.cond(merkleTree === snapshot.signed.value.stateProof, (), MerkleTreeBroken(snapshot.ordinal, snapshot.hash))

  @derive(eqv, show)
  case class MerkleTreeBroken(snapshotOrdinal: SnapshotOrdinal, snapshotHash: hash.Hash)

  type MarkleTreeValidationErrorOrUnit = Validated[MerkleTreeBroken, Unit]
}
