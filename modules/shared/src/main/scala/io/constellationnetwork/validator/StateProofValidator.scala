package io.constellationnetwork.validator

import cats.data.Validated
import cats.data.Validated.Invalid
import cats.effect.Async
import cats.kernel.Eq
import cats.syntax.all._
import cats.{Parallel, Show}

import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.snapshot.{IncrementalSnapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.stateproof.StateProofBuilder
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.mpt.producer.StatefulMerklePatriciaProducer
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive
import io.circe.Encoder
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

/** State proof validator trait.
  *
  * Use factory methods to create instances:
  *   - `StateProofValidator.forGlobal` for GlobalSnapshotInfo
  *   - `StateProofValidator.forCurrency` for CurrencySnapshotInfo
  */
trait StateProofValidator[F[_], I <: SnapshotInfo[P], P <: StateProof] {
  def validate[A <: IncrementalSnapshot[P]: Encoder](snapshot: Signed[A], info: I)(
    implicit hasher: Hasher[F]
  ): F[Validated[StateProofValidator.StateBroken, Unit]]

  def validate[A <: IncrementalSnapshot[P]](snapshot: Hashed[A], info: I)(
    implicit hasher: Hasher[F]
  ): F[Validated[StateProofValidator.StateBroken, Unit]]
}

object StateProofValidator {

  /** Create a StateProofValidator for GlobalSnapshotInfo.
    *
    * @param producer
    *   Optional MPT producer for efficient proof building from pre-built trie
    */
  def forGlobal[F[_]: Async: Parallel: JsonSerializer](
    producer: Option[StatefulMerklePatriciaProducer[F]] = None
  )(implicit selector: GlobalStateProofSelector): StateProofValidator[F, GlobalSnapshotInfo, GlobalSnapshotStateProof] =
    make(GlobalSnapshotInfo.stateProofBuilder(producer))

  /** Create a StateProofValidator for CurrencySnapshotInfo. */
  def forCurrency[F[_]: Async: Parallel: JsonSerializer](
    implicit selector: CurrencyStateProofSelector
  ): StateProofValidator[F, CurrencySnapshotInfo, CurrencySnapshotStateProof] =
    make(CurrencySnapshotInfo.stateProofBuilder[F])

  /** Create a StateProofValidator from a custom StateProofBuilder. */
  def make[F[_]: Async: Parallel: JsonSerializer, I <: SnapshotInfo[P], P <: StateProof: Eq](
    builder: StateProofBuilder[F, I, P]
  ): StateProofValidator[F, I, P] =
    new StateProofValidator[F, I, P] {
      def validate[A <: IncrementalSnapshot[P]: Encoder](snapshot: Signed[A], info: I)(
        implicit hasher: Hasher[F]
      ): F[Validated[StateBroken, Unit]] =
        (snapshot.toHashed, builder.buildProof(info, snapshot.ordinal)).flatMapN {
          case (hashed, proof) =>
            validateProof(hashed, proof)
        }

      def validate[A <: IncrementalSnapshot[P]](snapshot: Hashed[A], info: I)(
        implicit hasher: Hasher[F]
      ): F[Validated[StateBroken, Unit]] =
        builder.buildProof(info, snapshot.ordinal).flatMap(validateProof(snapshot, _))
    }

  /** Validate a pre-computed state proof against the snapshot. */
  def validateProof[F[_]: Async, P <: StateProof: Eq, A <: IncrementalSnapshot[P]](
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
