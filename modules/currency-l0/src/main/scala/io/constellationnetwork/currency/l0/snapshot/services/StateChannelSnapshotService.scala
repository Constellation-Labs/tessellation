package io.constellationnetwork.currency.l0.snapshot.services

import java.security.KeyPair

import cats.Applicative
import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer, SizeCalculator}
import io.constellationnetwork.node.shared.config.types.SnapshotSizeConfig
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.snapshot.DataApplicationSnapshotAcceptanceManager
import io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotArtifact
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
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
  def consume(
    signedArtifact: Signed[CurrencySnapshotArtifact],
    binaryHashed: Hashed[StateChannelSnapshotBinary],
    context: CurrencySnapshotContext
  )(implicit hasher: Hasher[F]): F[Unit]
  def createGenesisBinary(snapshot: Signed[CurrencySnapshot])(implicit hasher: Hasher[F]): F[Signed[StateChannelSnapshotBinary]]
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
  def make[F[_]: Async: JsonSerializer: SecurityProvider](
    keyPair: KeyPair,
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
    jsonBrotliBinarySerializer: JsonBrotliBinarySerializer[F],
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
      ): F[SnapshotFee] =
        lastGlobalSnapshotStorage.getCombined
          .map(_.flatMap { case (_, state) => maybeStakingAddress.flatMap(state.balances.get) }.getOrElse(Balance.empty))
          .flatMap { staked =>
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

      def createGenesisBinary(snapshot: Signed[CurrencySnapshot])(implicit hasher: Hasher[F]): F[Signed[StateChannelSnapshotBinary]] =
        for {
          bytes <- jsonBrotliBinarySerializer.serialize(snapshot)
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
          bytes <- jsonBrotliBinarySerializer.serialize(snapshot)
          fee <- calculateFee(lastSnapshotBinaryHash, bytes, snapshot.proofs.length, stakingAddress, maybeGlobalSnapshotOrdinal)
          binary <- StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes, fee).sign(keyPair)
        } yield binary

      def consume(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        binaryHashed: Hashed[StateChannelSnapshotBinary],
        context: CurrencySnapshotContext
      )(implicit hasher: Hasher[F]): F[Unit] = for {
        _ <- dataApplicationSnapshotAcceptanceManager.traverse { manager =>
          snapshotStorage.head.map { lastSnapshot =>
            lastSnapshot.flatMap { case (value, _) => value.dataApplication }
          }.flatMap(manager.consumeSignedMajorityArtifact(_, signedArtifact))
        }
        _ <- snapshotStorage
          .prepend(signedArtifact, context.snapshotInfo)
          .ifM(
            Applicative[F].unit,
            logger.error(
              s"Cannot save CurrencySnapshot ordinal=${signedArtifact.ordinal} for metagraph identifier=${context.address} into the storage."
            )
          )
        _ <- lastGlobalSnapshotStorage.deleteOlderThanSynchronized()
        _ <- stateChannelBinarySender.process(binaryHashed)
      } yield ()

    }
}
