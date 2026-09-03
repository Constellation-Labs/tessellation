package io.constellationnetwork.currency.l0.snapshot

import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.l0.snapshot.storage.CurrencyFeeContextReceiptStorage
import io.constellationnetwork.currency.l0.snapshot.storage.CurrencyFeeContextReceiptStorage.CurrencyFeeContextReceipt
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSyncGlobalSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.consensus.CertifiedLineageEvidenceV1
import io.constellationnetwork.schema.currencyMessage.fetchStakingAddress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class CurrencySnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      CurrencySnapshotEvent,
      CurrencySnapshotArtifact,
      CurrencySnapshotContext,
      ConsensusTrigger
    ] {
  def createProposalArtifactWithDisposition(
    lastKey: SnapshotOrdinal,
    lastArtifact: Signed[CurrencySnapshotArtifact],
    lastContext: CurrencySnapshotContext,
    lastArtifactHasher: Hasher[F],
    trigger: ConsensusTrigger,
    events: Set[CurrencySnapshotEvent],
    facilitators: Set[PeerId],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
    peerHistory: Option[ConsensusOperationalState] = None
  )(implicit hasher: Hasher[F]): F[CurrencySnapshotConsensusFunctions.ProposalArtifactResult]
}

object CurrencySnapshotConsensusFunctions {

  final case class MissingGlobalSyncViewForFeeContext(currencyOrdinal: SnapshotOrdinal) extends ConsensusFunctions.InvalidArtifact {
    override def getMessage: String =
      s"Currency proposal at ordinal=${currencyOrdinal.show} does not contain the Global L0 view required for fee calculation"
  }

  final case class ExactGlobalFeeContextUnavailable(ordinal: SnapshotOrdinal) extends ConsensusFunctions.InvalidArtifact {
    override def getMessage: String = s"Exact Global L0 fee context is unavailable at ordinal=${ordinal.show}"
  }

  final case class ExactGlobalFeeContextHashMismatch(ordinal: SnapshotOrdinal, expected: Hash, actual: Hash) extends NoStackTrace {
    override def getMessage: String =
      s"Exact Global L0 fee context hash mismatch at ordinal=${ordinal.show}: expected=${expected.show} actual=${actual.show}"
  }

  /** Capture the small fee input while the proposal's exact Global context is locally available.
    *
    * The durable receipt is keyed by Currency ordinal and artifact hash. It does not retain a full Global snapshot in heap and it cannot be
    * displaced by unrelated proposal accesses.
    */
  private[snapshot] def captureFeeContextReceipt[F[_]: Async](
    currencyOrdinal: SnapshotOrdinal,
    currencyArtifactHash: Hash,
    globalSyncView: Option[GlobalSyncView],
    stakingAddress: Option[Address],
    resolve: SnapshotOrdinal => F[Option[(Hash, Balance)]],
    putDurably: CurrencyFeeContextReceipt => F[Unit]
  ): F[Unit] =
    globalSyncView
      .liftTo[F](MissingGlobalSyncViewForFeeContext(currencyOrdinal))
      .flatMap { view =>
        resolve(view.ordinal)
          .flatMap(_.liftTo[F](ExactGlobalFeeContextUnavailable(view.ordinal)))
          .flatMap {
            case (actual, stakingBalance) =>
              ExactGlobalFeeContextHashMismatch(view.ordinal, view.hash, actual)
                .raiseError[F, Unit]
                .whenA(actual =!= view.hash) >>
                putDurably(
                  CurrencyFeeContextReceipt(
                    CurrencyFeeContextReceiptStorage.CurrentEncodingVersion,
                    currencyOrdinal,
                    currencyArtifactHash,
                    view,
                    stakingAddress,
                    stakingBalance
                  )
                )
          }
      }

  /** A temporarily unavailable exact Global context invalidates only the proposal being checked. A same-ordinal hash conflict remains a
    * hard failure because it can indicate a fork or corrupted local authority.
    */
  private[snapshot] def validateFeeContextCapture[F[_]: Async](capture: F[Unit]): F[Either[ConsensusFunctions.InvalidArtifact, Unit]] =
    capture.attempt.flatMap {
      case Right(_) => ().asRight[ConsensusFunctions.InvalidArtifact].pure[F]
      case Left(error: MissingGlobalSyncViewForFeeContext) =>
        (error: ConsensusFunctions.InvalidArtifact).asLeft[Unit].pure[F]
      case Left(error: ExactGlobalFeeContextUnavailable) =>
        (error: ConsensusFunctions.InvalidArtifact).asLeft[Unit].pure[F]
      case Left(error) => error.raiseError[F, Either[ConsensusFunctions.InvalidArtifact, Unit]]
    }

  final case class ProposalArtifactResult(
    artifact: CurrencySnapshotArtifact,
    context: CurrencySnapshotContext,
    awaitingEvents: Set[CurrencySnapshotEvent],
    rejectedEvents: Set[CurrencySnapshotEvent]
  )

  def make[F[_]: Async: SecurityProvider](
    collateral: Amount,
    rewards: Option[Rewards[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
    currencySnapshotCreator: CurrencySnapshotCreator[F],
    currencySnapshotValidator: CurrencySnapshotValidator[F],
    maybeCustomArtifacts: Option[Signed[CurrencyIncrementalSnapshot] => Option[SortedSet[SharedArtifact]]],
    lastGlobalSnapshotStorage: LastSyncGlobalSnapshotStorage[F],
    feeContextReceiptStorage: CurrencyFeeContextReceiptStorage[F]
  ): CurrencySnapshotConsensusFunctions[F] = new CurrencySnapshotConsensusFunctions[F] {
    val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F]("CurrencySnapshotConsensusFunctions")

    private def captureFeeContextReceipt(
      artifact: CurrencySnapshotArtifact,
      lastContext: CurrencySnapshotContext
    )(implicit hasher: Hasher[F]): F[Unit] =
      for {
        artifactHash <- artifact.hash
        stakingAddress = fetchStakingAddress(lastContext.snapshotInfo)
        _ <- CurrencySnapshotConsensusFunctions.captureFeeContextReceipt(
          artifact.ordinal,
          artifactHash,
          artifact.globalSyncView,
          stakingAddress,
          ordinal =>
            lastGlobalSnapshotStorage
              .getCombined(ordinal)
              .flatMap(
                _.traverse {
                  case (snapshot, info) =>
                    snapshot.hash.map(_ -> stakingAddress.flatMap(info.balances.get).getOrElse(Balance.empty))
                }
              ),
          receipt => feeContextReceiptStorage.putDurably(receipt).void
        )
      } yield ()

    override def triggerPredicate(event: CurrencySnapshotEvent): Boolean = event match {
      case GlobalSnapshotSyncEvent(_) => false // NOTE: Sync events should not trigger consensus to avoid infinite loop
      case _                          => true
    }

    def getRequiredCollateral: Amount = collateral

    def getBalance(context: CurrencySnapshotContext, address: Address): F[Balance] =
      context.snapshotInfo.balances.getOrElse(address, Balance.empty).pure[F]

    def validateArtifact(
      lastSignedArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      trigger: ConsensusTrigger,
      artifact: CurrencySnapshotArtifact,
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      peerHistory: Option[ConsensusOperationalState] = None,
      // The generic GL0-facing interface still exposes certified lineage. The
      // synchronous Currency adapter deliberately ignores it and never places
      // certified evidence in a Currency artifact.
      _certifiedLineage: Option[CertifiedLineageEvidenceV1] = None
    )(implicit hasher: Hasher[F]): F[Either[ConsensusFunctions.InvalidArtifact, (CurrencySnapshotArtifact, CurrencySnapshotContext)]] =
      currencySnapshotValidator
        .validateSnapshot(
          lastSignedArtifact,
          lastContext,
          artifact,
          facilitators,
          getGlobalSnapshotByOrdinal,
          peerHistory,
          historicalDependencyResolution = false
        )
        .flatMap {
          case Invalid(errors) =>
            logger
              .warn(s"Failed when validating currency artifact. Errors: ${errors.toList}")
              .as(CurrencyArtifactMismatch(errors.toList).asLeft)
          case Valid(validated) =>
            CurrencySnapshotConsensusFunctions
              .validateFeeContextCapture(captureFeeContextReceipt(artifact, lastContext))
              .map(_.map(_ => validated))
        }

    def createProposalArtifact(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      lastArtifactHasher: Hasher[F],
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      peerHistory: Option[ConsensusOperationalState] = None,
      _certifiedLineage: Option[CertifiedLineageEvidenceV1] = None
    )(implicit hasher: Hasher[F]): F[(CurrencySnapshotArtifact, CurrencySnapshotContext, Set[CurrencySnapshotEvent])] =
      createProposalArtifactWithDisposition(
        lastKey,
        lastArtifact,
        lastContext,
        lastArtifactHasher,
        trigger,
        events,
        facilitators,
        getGlobalSnapshotByOrdinal,
        peerHistory
      ).map(result => (result.artifact, result.context, result.awaitingEvents ++ result.rejectedEvents))

    def createProposalArtifactWithDisposition(
      lastKey: SnapshotOrdinal,
      lastArtifact: Signed[CurrencySnapshotArtifact],
      lastContext: CurrencySnapshotContext,
      lastArtifactHasher: Hasher[F],
      trigger: ConsensusTrigger,
      events: Set[CurrencySnapshotEvent],
      facilitators: Set[PeerId],
      getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]],
      peerHistory: Option[ConsensusOperationalState] = None
    )(implicit hasher: Hasher[F]): F[ProposalArtifactResult] = {
      val blocksForAcceptance: Set[CurrencySnapshotEvent] = events.filter {
        case BlockEvent(currencyBlock) => currencyBlock.height > lastArtifact.height
        case _                         => true
      }

      currencySnapshotCreator
        .createProposalArtifact(
          lastKey,
          lastArtifact,
          lastContext,
          lastArtifactHasher,
          trigger,
          blocksForAcceptance,
          rewards,
          facilitators,
          None,
          None,
          getGlobalSnapshotByOrdinal,
          shouldPerformMetagraphSpecificValidations = true,
          maybeCustomArtifacts,
          peerHistory,
          historicalDependencyResolution = false
        )
        .flatTap(created => captureFeeContextReceipt(created.artifact, lastContext))
        .map(created => ProposalArtifactResult(created.artifact, created.context, created.awaitingEvents, created.rejectedEvents))
    }
  }
}
