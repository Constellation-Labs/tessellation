package io.constellationnetwork.currency.l0.snapshot.services

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._
import cats.{Monad, MonadThrow}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.{JsonSerializer, SizeCalculator}
import io.constellationnetwork.node.shared.config.types.SnapshotSizeConfig
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.DataApplicationSnapshotAcceptanceManager
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait StateChannelSnapshotService[F[_]] {
  // Returns whether the snapshot was persisted to storage, so the caller can gate finalize-time
  // work (mempool clearing) on a confirmed persist of the winning artifact.
  def persist(
    signedArtifact: Signed[CurrencySnapshotArtifact],
    context: CurrencySnapshotContext,
    maybeParentDataApplication: Option[DataApplicationPart],
    parentGlobalSnapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[Boolean]

  /** Last critical finalization step: enqueue the exact persisted snapshot binary.
    *
    * Keeping this separate from [[persist]] lets the advancer complete idempotent state/mempool/application work before enqueue. No
    * fallible critical work may follow this call, so a delivered binary is not replayed merely because telemetry failed afterward.
    */
  def enqueueBinary(binaryHashed: Hashed[StateChannelSnapshotBinary], currencySnapshotOrdinal: SnapshotOrdinal): F[Unit]
  def createGenesisBinary(snapshot: Signed[CurrencySnapshot])(implicit hasher: Hasher[F]): F[Signed[StateChannelSnapshotBinary]]
  def createBinaryValue(
    snapshot: Signed[CurrencySnapshotArtifact],
    lastSnapshotBinaryHash: Hash,
    globalSnapshotOrdinal: SnapshotOrdinal,
    stakingAddress: Option[Address]
  ): F[StateChannelSnapshotBinary]
  def createBinary(
    snapshot: Signed[CurrencySnapshotArtifact],
    lastSnapshotBinaryHash: Hash,
    maybeGlobalSnapshotOrdinal: Option[SnapshotOrdinal],
    stakingAddress: Option[Address]
  )(
    implicit hasher: Hasher[F]
  ): F[Signed[StateChannelSnapshotBinary]]
}

object StateChannelSnapshotService {

  private[services] def loadStakedBalance[F[_]: MonadThrow](
    ordinal: SnapshotOrdinal,
    address: Address,
    getExact: SnapshotOrdinal => F[Option[GlobalSnapshotInfo]]
  ): F[Balance] =
    getExact(ordinal)
      .flatMap(
        _.liftTo[F](
          new IllegalStateException(
            s"Global snapshot context ordinal=${ordinal.show} is unavailable for deterministic state-channel fee calculation"
          )
        )
      )
      .map(_.balances.getOrElse(address, Balance.empty))

  /** Sequence finalize-time effects only after the accepted snapshot is present in storage.
    *
    * This seam is intentionally small and generic so the fail-closed ordering is directly testable: a rejected/conflicting prepend must not
    * mutate the data application or enqueue a state-channel binary for an artifact this node did not persist.
    */
  private[services] def continueAfterPersist[F[_]: Monad](
    persisted: Boolean,
    onPersisted: F[Unit],
    onRejected: F[Unit]
  ): F[Boolean] =
    if (persisted) onPersisted.as(true) else onRejected.as(false)

  def make[F[_]: Async: JsonSerializer: SecurityProvider](
    keyPair: KeyPair,
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
    dataApplicationSnapshotAcceptanceManager: Option[DataApplicationSnapshotAcceptanceManager[F]],
    stateChannelBinarySender: StateChannelBinarySender[F],
    feeCalculator: FeeCalculator[F],
    snapshotSizeConfig: SnapshotSizeConfig
  ): StateChannelSnapshotService[F] =
    new StateChannelSnapshotService[F] {
      private val logger = Slf4jLogger.getLogger

      private val feeCalculationDelay: NonNegLong = 10L

      private def calculateFee(
        lastHash: Hash,
        bytes: Array[Byte],
        signatureCount: Int,
        maybeStakingAddress: Option[Address],
        maybeGlobalSnapshotOrdinal: Option[SnapshotOrdinal]
      ): F[SnapshotFee] = {
        val staked = maybeStakingAddress.fold(Balance.empty.pure[F]) { address =>
          maybeGlobalSnapshotOrdinal match {
            case Some(ordinal) =>
              loadStakedBalance(ordinal, address, requested => lastGlobalSnapshotStorage.getCombined(requested).map(_.map(_._2)))
            case None =>
              lastGlobalSnapshotStorage.getCombined
                .map(_.flatMap { case (_, state) => state.balances.get(address) }.getOrElse(Balance.empty))
          }
        }

        staked.flatMap { staked =>
          JsonSerializer[F]
            .serialize(
              Signed(
                StateChannelSnapshotBinary(lastHash, bytes, SnapshotFee(NonNegLong.MaxValue)),
                NonEmptySet.one(SignatureProof(Id(Hex("")), Signature(Hex(""))))
              )
            )
            .map(_.length)
            .flatMap { noSigsBytesSize =>
              val bytesSize = noSigsBytesSize + signatureCount * snapshotSizeConfig.singleSignatureSizeInBytes
              val sizeKb = SizeCalculator.toKilobytes(bytesSize)

              feeCalculator.calculateRecommendedFee(maybeGlobalSnapshotOrdinal, feeCalculationDelay)(staked, sizeKb)
            }
        }
      }

      def createGenesisBinary(snapshot: Signed[CurrencySnapshot])(implicit hasher: Hasher[F]): F[Signed[StateChannelSnapshotBinary]] =
        for {
          bytes <- JsonSerializer[F].serialize(snapshot)
          fee <- calculateFee(Hash.empty, bytes, snapshot.proofs.length, None, None)
          binary <- StateChannelSnapshotBinary(Hash.empty, bytes, fee).sign(keyPair)
        } yield binary

      def createBinary(
        snapshot: Signed[CurrencySnapshotArtifact],
        lastSnapshotBinaryHash: Hash,
        maybeGlobalSnapshotOrdinal: Option[SnapshotOrdinal],
        stakingAddress: Option[Address]
      )(
        implicit hasher: Hasher[F]
      ): F[Signed[StateChannelSnapshotBinary]] =
        for {
          bytes <- JsonSerializer[F].serialize(snapshot)
          fee <- calculateFee(lastSnapshotBinaryHash, bytes, snapshot.proofs.length, stakingAddress, maybeGlobalSnapshotOrdinal)
          binary <- StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes, fee).sign(keyPair)
        } yield binary

      def createBinaryValue(
        snapshot: Signed[CurrencySnapshotArtifact],
        lastSnapshotBinaryHash: Hash,
        globalSnapshotOrdinal: SnapshotOrdinal,
        stakingAddress: Option[Address]
      ): F[StateChannelSnapshotBinary] =
        for {
          bytes <- JsonSerializer[F].serialize(snapshot)
          fee <- calculateFee(lastSnapshotBinaryHash, bytes, snapshot.proofs.length, stakingAddress, globalSnapshotOrdinal.some)
        } yield StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes, fee)

      def persist(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        context: CurrencySnapshotContext,
        maybeParentDataApplication: Option[DataApplicationPart],
        parentGlobalSnapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Boolean] = for {
        persisted <- snapshotStorage.prepend(signedArtifact, context.snapshotInfo)
        // Parent inputs are captured from the transition's immutable lastOutcome by the caller.
        // Retained replay after N is already current must still consume N against parent N-1.
        accepted = dataApplicationSnapshotAcceptanceManager.traverse_(
          _.consumeSignedMajorityArtifact(
            maybeParentDataApplication,
            signedArtifact,
            parentGlobalSnapshotOrdinal
          )
        )
        rejected = logger.error(
          s"Cannot save CurrencySnapshot ordinal=${signedArtifact.ordinal} for metagraph identifier=${context.address} into the storage."
        )
        result <- StateChannelSnapshotService.continueAfterPersist(persisted, accepted, rejected)
      } yield result

      def enqueueBinary(binaryHashed: Hashed[StateChannelSnapshotBinary], currencySnapshotOrdinal: SnapshotOrdinal): F[Unit] =
        lastGlobalSnapshotStorage.get.flatMap { lastGlobalSnapshot =>
          val lastGlobalSnapshotSigners = lastGlobalSnapshot.map(_.signed.proofs.map(_.id.toPeerId))
          stateChannelBinarySender.enqueue(binaryHashed, currencySnapshotOrdinal, lastGlobalSnapshotSigners)
        }

    }
}
