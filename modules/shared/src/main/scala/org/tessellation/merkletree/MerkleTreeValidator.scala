package org.tessellation.merkletree

import cats.data.Validated
import cats.effect.Async
import cats.effect.kernel.Sync
import cats.kernel.Eq
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.snapshot.{IncrementalSnapshot, SnapshotInfo, StateProof}
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, Hasher, hash}

import derevo.cats.{eqv, show}
import derevo.derive
import io.circe.Encoder

object StateProofValidator {

  def validate[F[_]: Async: Hasher, P <: StateProof: Eq, A <: IncrementalSnapshot[P]: Encoder](
    snapshot: Signed[A],
    si: SnapshotInfo[P]
  ): F[Validated[StateBroken, Unit]] = snapshot.toHashed.flatMap(hs => si.stateProof.map(validate(hs, _)))

  def validate[F[_]: Sync: Hasher, P <: StateProof: Eq, A <: IncrementalSnapshot[P]](
    snapshot: Hashed[A],
    si: SnapshotInfo[P]
  ): F[Validated[StateBroken, Unit]] = si.stateProof.map(validate(snapshot, _))

  def validate[P <: StateProof: Eq, A <: IncrementalSnapshot[P]](
    snapshot: Hashed[A],
    stateProof: P
  ): Validated[StateBroken, Unit] =
    Validated.cond(stateProof === snapshot.signed.value.stateProof, (), StateBroken(snapshot.ordinal, snapshot.hash))

  @derive(eqv, show)
  case class StateBroken(snapshotOrdinal: SnapshotOrdinal, snapshotHash: hash.Hash) extends NoStackTrace {
    implicit val hashShow = Hash.shortShow
    override val getMessage = s"State broken for ${snapshotOrdinal.show}, ${snapshotHash.show}"
  }
}
