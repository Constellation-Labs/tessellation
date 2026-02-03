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
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
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

  /** Create a StateProofValidator that always rebuilds from info (safe but slow).
    *
    * Use this when you don't have an MptStore or need guaranteed correctness regardless of store state.
    */
  def forGlobal[F[_]: Async: Parallel: JsonSerializer](
    implicit selector: GlobalStateProofSelector
  ): StateProofValidator[F, GlobalSnapshotInfo, GlobalSnapshotStateProof] =
    make(GlobalSnapshotInfo.stateProofBuilder[F])

  /** Create an optimistic StateProofValidator that uses the MptStore when it's at the correct ordinal.
    *
    * Fast path: If the store's currentOrdinal matches the snapshot being validated, extracts root directly (O(1)). Safe path: Otherwise,
    * rebuilds from info (O(n log n)).
    *
    * Use this for validators that maintain incremental state.
    */
  def forGlobal[F[_]: Async: Parallel: JsonSerializer](
    mptStore: MptStore[F, GlobalStateKey]
  )(implicit selector: GlobalStateProofSelector): StateProofValidator[F, GlobalSnapshotInfo, GlobalSnapshotStateProof] =
    make(GlobalSnapshotInfo.stateProofBuilderWithStore(mptStore))

  /** Create a StateProofValidator for GlobalSnapshotInfo.
    *
    * @param producer
    *   Ignored. This parameter exists only for API compatibility and will be removed in a future version.
    */
  @deprecated("Producer parameter is ignored. Use forGlobal() or forGlobal(mptStore) instead.", "v4.0.0")
  def forGlobal[F[_]: Async: Parallel: JsonSerializer](
    producer: Option[StatefulMerklePatriciaProducer[F]]
  )(implicit selector: GlobalStateProofSelector): StateProofValidator[F, GlobalSnapshotInfo, GlobalSnapshotStateProof] =
    forGlobal[F]

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
