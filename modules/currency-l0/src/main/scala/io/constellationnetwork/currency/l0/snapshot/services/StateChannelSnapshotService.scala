package io.constellationnetwork.currency.l0.snapshot.services

import java.security.KeyPair

import cats.data.NonEmptySet
import cats.effect.Async
import cats.syntax.all._
import cats.{Monad, MonadThrow}

import io.constellationnetwork.currency.l0.snapshot.storage.CurrencyFeeContextReceiptStorage.{
  CurrencyFeeContextKey,
  CurrencyFeeContextReceipt,
  MissingCurrencyFeeContextReceipt
}
import io.constellationnetwork.currency.l0.snapshot.storage.{
  CurrencyFeeContextReceiptStorage,
  RecoverySyncPublicationStorage,
  StateChannelBinaryOutboxStorage
}
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.json.{JsonSerializer, SizeCalculator}
import io.constellationnetwork.node.shared.config.types.SnapshotSizeConfig
import io.constellationnetwork.node.shared.domain.snapshot.storage.{ExactSnapshotStorage, LastSyncGlobalSnapshotStorage, SnapshotStorage}
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.DataApplicationSnapshotAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage.{
  OrdinalLinkStatus,
  UnableToPersistSnapshot
}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{
  LastSentGlobalSnapshotSyncStorage,
  SnapshotInfoLocalFileSystemStorage,
  SnapshotLocalFileSystemStorage
}
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
  // Returns whether the snapshot was persisted to storage, so the caller can gate finalize-time
  // work (mempool clearing) on a confirmed persist of the winning artifact.
  def persist(
    signedArtifact: Signed[CurrencySnapshotArtifact],
    context: CurrencySnapshotContext,
    maybeParentDataApplication: Option[DataApplicationPart],
    parentGlobalSnapshotOrdinal: SnapshotOrdinal
  )(implicit hasher: Hasher[F]): F[Boolean]

  /** Write a non-publishable ordinary outbox intent before local snapshot persistence. A recovery refresh also prepares its stricter
    * deadline-bearing receipt.
    */
  def prepareBinaryPublication(
    signedArtifact: Signed[CurrencySnapshotArtifact],
    binaryHashed: Hashed[StateChannelSnapshotBinary]
  )(implicit hasher: Hasher[F]): F[Unit]

  /** Make the prepared binary publishable immediately after the exact Currency artifact is durable. */
  def commitBinaryPublication(
    binaryHash: Hash,
    signedArtifact: Signed[CurrencySnapshotArtifact],
    context: CurrencySnapshotInfo
  )(implicit hasher: Hasher[F]): F[Unit]

  /** Remove only a non-committed intent when local persistence definitively rejects the artifact. */
  def abortPreparedBinaryPublication(binaryHash: Hash): F[Unit]

  /** Last critical finalization step: enqueue the exact persisted snapshot binary.
    *
    * Keeping this separate from [[persist]] lets the advancer complete idempotent state/mempool/application work before enqueue. No
    * fallible critical work may follow this call, so a delivered binary is not replayed merely because telemetry failed afterward.
    */
  def enqueueBinary(binaryHashed: Hashed[StateChannelSnapshotBinary], currencySnapshotOrdinal: SnapshotOrdinal): F[Unit]
  def createGenesisBinary(snapshot: Signed[CurrencySnapshot])(implicit hasher: Hasher[F]): F[Signed[StateChannelSnapshotBinary]]
  def createBinary(
    snapshot: Signed[CurrencySnapshotArtifact],
    lastSnapshotBinaryHash: Hash,
    maybeGlobalSnapshotOrdinal: Option[SnapshotOrdinal],
    stakingAddress: Option[Address]
  )(
    implicit hasher: Hasher[F]
  ): F[Signed[StateChannelSnapshotBinary]]

  /** Construct the common unsigned binary value used by flat synchronous Currency consensus. Fee inputs come only from the durable local
    * receipt captured while the artifact's exact Global context was available and hash-checked. The receipt must match the selected
    * artifact, signed Global view, and staking address before its balance is used.
    */
  def createSynchronousBinaryValue(
    snapshot: Signed[CurrencySnapshotArtifact],
    lastSnapshotBinaryHash: Hash,
    stakingAddress: Option[Address]
  )(implicit hasher: Hasher[F]): F[StateChannelSnapshotBinary]

}

object StateChannelSnapshotService {

  final case class CurrencyFeeContextReceiptMismatch(key: CurrencyFeeContextKey)
      extends IllegalStateException(s"Currency fee-context receipt does not match selected artifact key=$key")

  private[services] def loadFeeContextBalance[F[_]: MonadThrow](
    key: CurrencyFeeContextKey,
    expectedGlobalSyncView: GlobalSyncView,
    expectedStakingAddress: Option[Address],
    get: CurrencyFeeContextKey => F[Option[CurrencyFeeContextReceipt]]
  ): F[Balance] =
    get(key)
      .flatMap(_.liftTo[F](MissingCurrencyFeeContextReceipt(key)))
      .flatMap { receipt =>
        CurrencyFeeContextReceiptMismatch(key)
          .raiseError[F, Unit]
          .whenA(
            receipt.key =!= key ||
              receipt.globalSyncView =!= expectedGlobalSyncView ||
              receipt.stakingAddress =!= expectedStakingAddress
          )
          .as(receipt.stakingBalance)
      }

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

  /** Commit publication receipts in the only order that preserves the recovery deadline guard.
    *
    * The ordinary outbox must become publishable last. Otherwise a crash after ordinary commit but before recovery read-back/commit can
    * publish the same binary without the recovery receipt's retained-window deadline. When no recovery refresh is armed, the ordinary
    * receipt is the sole publication authority.
    */
  private[services] def commitPreparedPublications[F[_]: Monad](
    recoveryRequired: Boolean,
    ensureRecoveryArtifactDurable: F[Unit],
    markRecoveryLocallyCommitted: F[Unit],
    markOrdinaryLocallyCommitted: F[Unit]
  ): F[Unit] =
    if (recoveryRequired)
      ensureRecoveryArtifactDurable >> markRecoveryLocallyCommitted >> markOrdinaryLocallyCommitted
    else markOrdinaryLocallyCommitted

  private[services] def commitPreparedPublicationsAndReleaseFeeContext[F[_]: Monad](
    recoveryRequired: Boolean,
    ensureRecoveryArtifactDurable: F[Unit],
    markRecoveryLocallyCommitted: F[Unit],
    markOrdinaryLocallyCommitted: F[Unit],
    releaseFeeContext: F[Unit]
  ): F[Unit] =
    commitPreparedPublications(
      recoveryRequired,
      ensureRecoveryArtifactDurable,
      markRecoveryLocallyCommitted,
      markOrdinaryLocallyCommitted
    ) >> releaseFeeContext

  def make[F[_]: Async: JsonSerializer: SecurityProvider: Metrics](
    keyPair: KeyPair,
    snapshotStorage: SnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    snapshotLocalFileSystemStorage: SnapshotLocalFileSystemStorage[F, CurrencyIncrementalSnapshot],
    snapshotInfoLocalFileSystemStorage: SnapshotInfoLocalFileSystemStorage[
      F,
      CurrencySnapshotStateProof,
      CurrencySnapshotInfo
    ],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
    dataApplicationSnapshotAcceptanceManager: Option[DataApplicationSnapshotAcceptanceManager[F]],
    stateChannelBinarySender: StateChannelBinarySender[F],
    lastSentGlobalSnapshotSyncStorage: LastSentGlobalSnapshotSyncStorage[F],
    recoverySyncPublicationStorage: RecoverySyncPublicationStorage[F],
    stateChannelBinaryOutboxStorage: StateChannelBinaryOutboxStorage[F],
    feeContextReceiptStorage: CurrencyFeeContextReceiptStorage[F],
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
          .flatMap(calculateFeeFromBalance(lastHash, bytes, signatureCount, maybeGlobalSnapshotOrdinal, _))

      private def calculateFeeFromBalance(
        lastHash: Hash,
        bytes: Array[Byte],
        signatureCount: Int,
        maybeGlobalSnapshotOrdinal: Option[SnapshotOrdinal],
        staked: Balance
      ): F[SnapshotFee] =
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

      def createSynchronousBinaryValue(
        snapshot: Signed[CurrencySnapshotArtifact],
        lastSnapshotBinaryHash: Hash,
        stakingAddress: Option[Address]
      )(implicit hasher: Hasher[F]): F[StateChannelSnapshotBinary] =
        for {
          globalSyncView <- snapshot.value.globalSyncView.liftTo[F](
            new IllegalStateException(
              s"Synchronous Currency artifact ordinal=${snapshot.ordinal} has no signed globalSyncView"
            )
          )
          artifactHash <- snapshot.value.hash
          stakingBalance <- StateChannelSnapshotService.loadFeeContextBalance(
            CurrencyFeeContextKey(snapshot.ordinal, artifactHash),
            globalSyncView,
            stakingAddress,
            feeContextReceiptStorage.get
          )
          bytes <- JsonSerializer[F].serialize(snapshot)
          fee <- calculateFeeFromBalance(
            lastSnapshotBinaryHash,
            bytes,
            snapshot.proofs.length,
            globalSyncView.ordinal.some,
            stakingBalance
          )
        } yield StateChannelSnapshotBinary(lastSnapshotBinaryHash, bytes, fee)

      def persist(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        context: CurrencySnapshotContext,
        maybeParentDataApplication: Option[DataApplicationPart],
        parentGlobalSnapshotOrdinal: SnapshotOrdinal
      )(implicit hasher: Hasher[F]): F[Boolean] = for {
        persisted <- ExactSnapshotStorage.prependExact(snapshotStorage, signedArtifact, context.snapshotInfo)
        // Parent inputs are captured from the transition's immutable lastOutcome by the caller.
        // Retained replay after N is already current must still consume N against parent N-1.
        accepted = dataApplicationSnapshotAcceptanceManager.traverse_(
          _.consumeSignedMajorityArtifact(
            maybeParentDataApplication,
            signedArtifact,
            parentGlobalSnapshotOrdinal
          )
        )
        rejected =
          logger.error(
            s"CurrencySnapshot exact artifact/context install failed ordinal=${signedArtifact.ordinal} metagraph=${context.address}; " +
              "binary publication is blocked and coordinated rollback is required."
          )
        result <- StateChannelSnapshotService.continueAfterPersist(persisted, accepted, rejected)
      } yield result

      def prepareBinaryPublication(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        binaryHashed: Hashed[StateChannelSnapshotBinary]
      )(implicit hasher: Hasher[F]): F[Unit] =
        signedArtifact.toHashed.flatMap { currencyArtifact =>
          stateChannelBinaryOutboxStorage
            .prepare(binaryHashed, currencyArtifact)
            .void
            .handleErrorWith {
              case error: StateChannelBinaryOutboxStorage.CapacityExceeded =>
                Metrics[F]
                  .incrementCounter(
                    "dag_currency_l0_binary_outbox_backpressure_total",
                    Seq(
                      Metrics.unsafeLabelName("reason") ->
                        (if (error.pendingCount > error.maxEntries) "count" else "bytes")
                    )
                  )
                  .attempt
                  .void >> error.raiseError[F, Unit]
              case error => error.raiseError[F, Unit]
            } >>
            lastSentGlobalSnapshotSyncStorage.getRequiredRecoveryRefresh.flatMap {
              case Some(required) =>
                recoverySyncPublicationStorage.prepare(required, binaryHashed, currencyArtifact).void
              case None => Async[F].unit
            }
        }

      private def ensureRecoveryArtifactDurable(
        signedArtifact: Signed[CurrencySnapshotArtifact],
        expectedInfo: CurrencySnapshotInfo
      )(implicit hasher: Hasher[F]): F[Unit] =
        signedArtifact.toHashed.flatMap { artifact =>
          def verify: F[OrdinalLinkStatus] =
            snapshotLocalFileSystemStorage.ensureOrdinalLink(artifact.hash, artifact.ordinal)

          def requireUsable(status: OrdinalLinkStatus): F[Unit] =
            if (!status.usable)
              Async[F].raiseError(
                new IllegalStateException(
                  s"Recovery Currency artifact is not durable in both snapshot indexes: " +
                    s"ordinal=${artifact.ordinal} hash=${artifact.hash} status=${status.label}"
                )
              )
            else
              List(
                snapshotLocalFileSystemStorage.read(artifact.hash),
                snapshotLocalFileSystemStorage.read(artifact.ordinal)
              ).sequence.flatMap { copies =>
                copies.traverse(_.traverse(_.toHashed)).flatMap { hashedCopies =>
                  val exactArtifact = hashedCopies.forall(
                    _.exists(value => value.hash === artifact.hash && value.proofsHash === artifact.proofsHash)
                  )

                  snapshotInfoLocalFileSystemStorage.read(artifact.ordinal).flatMap { persistedInfo =>
                    Async[F].raiseUnless(exactArtifact && persistedInfo.contains(expectedInfo))(
                      new IllegalStateException(
                        s"Recovery Currency artifact/context read-back mismatch: ordinal=${artifact.ordinal} " +
                          s"hash=${artifact.hash} proofsHash=${artifact.proofsHash}"
                      )
                    )
                  }
                }
              }

          verify.flatMap {
            case OrdinalLinkStatus.Missing =>
              snapshotLocalFileSystemStorage
                .write(signedArtifact)
                .handleErrorWith {
                  // A concurrent persistence/repair may have won after the initial check. The
                  // authoritative decision is the exact read-back below, never this exception.
                  case _: UnableToPersistSnapshot => Async[F].unit
                  case error                      => error.raiseError[F, Unit]
                } >> verify.flatMap(requireUsable)
            case status => requireUsable(status)
          }
        }

      def commitBinaryPublication(
        binaryHash: Hash,
        signedArtifact: Signed[CurrencySnapshotArtifact],
        context: CurrencySnapshotInfo
      )(implicit hasher: Hasher[F]): F[Unit] =
        lastSentGlobalSnapshotSyncStorage.getRequiredRecoveryRefresh.flatMap { requiredRecovery =>
          StateChannelSnapshotService.commitPreparedPublicationsAndReleaseFeeContext(
            recoveryRequired = requiredRecovery.nonEmpty,
            ensureRecoveryArtifactDurable = ensureRecoveryArtifactDurable(signedArtifact, context),
            markRecoveryLocallyCommitted = recoverySyncPublicationStorage.markLocallyCommitted(binaryHash).void,
            markOrdinaryLocallyCommitted = stateChannelBinaryOutboxStorage.markLocallyCommitted(binaryHash).void,
            releaseFeeContext = signedArtifact.value.hash.flatMap { artifactHash =>
              feeContextReceiptStorage.complete(CurrencyFeeContextKey(signedArtifact.ordinal, artifactHash))
            }
          )
        }

      def abortPreparedBinaryPublication(binaryHash: Hash): F[Unit] =
        stateChannelBinaryOutboxStorage.abortPrepared(binaryHash) >>
          recoverySyncPublicationStorage.abortPrepared(binaryHash)

      def enqueueBinary(binaryHashed: Hashed[StateChannelSnapshotBinary], currencySnapshotOrdinal: SnapshotOrdinal): F[Unit] =
        for {
          lastGlobalSnapshot <- lastGlobalSnapshotStorage.get
          lastGlobalSnapshotSigners = lastGlobalSnapshot.map(_.signed.proofs.map(_.id.toPeerId))
          _ <- stateChannelBinarySender.enqueue(binaryHashed, currencySnapshotOrdinal, lastGlobalSnapshotSigners)
        } yield ()

    }
}
