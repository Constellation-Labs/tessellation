package org.tessellation.merkletree

import cats.MonadThrow
import cats.data.Validated
import cats.kernel.Eq
import cats.syntax.eq._
import cats.syntax.functor._

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.{IncrementalSnapshot, SnapshotInfo, StateProof}
import org.tessellation.security.{Hashed, hash}

import derevo.cats.{eqv, show}
import derevo.derive

object StateProofValidator {

  def validate[F[_]: MonadThrow: KryoSerializer, P <: StateProof: Eq, A <: IncrementalSnapshot[_, P]](
    snapshot: Hashed[A],
    si: SnapshotInfo[P]
  ): F[Validated[StateBroken, Unit]] = si.stateProof.map(validate(snapshot, _))

  def validate[P <: StateProof: Eq, A <: IncrementalSnapshot[_, P]](
    snapshot: Hashed[A],
    stateProof: P
  ): Validated[StateBroken, Unit] =
    Validated.cond(stateProof === snapshot.signed.value.stateProof, (), StateBroken(snapshot.ordinal, snapshot.hash))

  @derive(eqv, show)
  case class StateBroken(snapshotOrdinal: SnapshotOrdinal, snapshotHash: hash.Hash)

  type StateValidationErrorOrUnit = Validated[StateBroken, Unit]
}
