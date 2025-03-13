package io.constellationnetwork.merkletree

import cats.Show
import cats.data.Validated
import cats.data.Validated.Invalid
import cats.effect.{Async, Sync}
import cats.kernel.Eq
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.snapshot.{IncrementalSnapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

object StateProofValidator {

  def validate[F[_]: Async: Hasher, P <: StateProof: Eq, A <: IncrementalSnapshot[P]: Encoder](
    snapshot: Signed[A],
    snapshotInfo: SnapshotInfo[P]
  ): F[Validated[StateBroken, Unit]] =
    (snapshot.toHashed, snapshotInfo.stateProof(snapshot.ordinal)).flatMapN {
      case (hashedSnapshot, stateProof) =>
        validate(hashedSnapshot, stateProof)
    }

  def validate[F[_]: Sync: Hasher, P <: StateProof: Eq, A <: IncrementalSnapshot[P]](
    snapshot: Hashed[A],
    snapshotInfo: SnapshotInfo[P]
  ): F[Validated[StateBroken, Unit]] =
    snapshotInfo.stateProof(snapshot.ordinal).flatMap(validate(snapshot, _))

  def validate[F[_]: Sync, P <: StateProof: Eq, A <: IncrementalSnapshot[P]](
    snapshot: Hashed[A],
    stateProof: P
  ): F[Validated[StateBroken, Unit]] = {

    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]
    val expectedStateProof = snapshot.signed.value.stateProof

    val result = Validated.cond(
      stateProof === expectedStateProof,
      (),
      StateBroken(snapshot.ordinal, snapshot.hash)
    )

    result match {
      case Invalid(_) =>
        logger
          .error(
            s"StateProof Broken at ordinal ${snapshot.ordinal}. " +
              s"Expected: $expectedStateProof, Found: $stateProof"
          )
          .as(result)
      case valid => valid.pure[F]
    }
  }

  @derive(eqv, show)
  case class StateBroken(snapshotOrdinal: SnapshotOrdinal, snapshotHash: Hash) extends NoStackTrace {
    implicit val hashShow: Show[Hash] = Hash.shortShow
    override val getMessage = s"State broken for ${snapshotOrdinal.show}, ${snapshotHash.show}"
  }
}
