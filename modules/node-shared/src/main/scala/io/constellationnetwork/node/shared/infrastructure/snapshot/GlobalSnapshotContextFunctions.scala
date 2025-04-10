package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.Parallel
import cats.data.Validated
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.merkletree.StateProofValidator
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake.UpdateDelegatedStake
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelValidationType}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.types.all.NonNegLong

abstract class GlobalSnapshotContextFunctions[F[_]] extends SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]

object GlobalSnapshotContextFunctions {
  def make[F[_]: Async: Parallel: HasherSelector](snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F]) =
    new GlobalSnapshotContextFunctions[F] {
      def createContext(
        context: GlobalSnapshotInfo,
        lastArtifact: Signed[GlobalIncrementalSnapshot],
        signedArtifact: Signed[GlobalIncrementalSnapshot],
        lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] = for {
        lastActiveTips <- HasherSelector[F].forOrdinal(lastArtifact.ordinal)(implicit hasher => lastArtifact.activeTips)
        lastDeprecatedTips = lastArtifact.tips.deprecated

        blocksForAcceptance = signedArtifact.blocks.toList.map(_.block)
        allowSpendBlocksForAcceptance = signedArtifact.allowSpendBlocks.map(_.toList).getOrElse(List.empty)
        tokenLockBlocksForAcceptance = signedArtifact.tokenLockBlocks.map(_.toList).getOrElse(List.empty)

        scEvents = signedArtifact.stateChannelSnapshots.toList.flatMap {
          case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).toList
        }

        unpEventsForAcceptance = signedArtifact.updateNodeParameters
          .getOrElse(SortedMap.empty[Id, Signed[UpdateNodeParameters]])
          .values
          .toList

        cdsEventsForAcceptance = signedArtifact.activeDelegatedStakes
          .getOrElse(SortedMap.empty[Address, List[Signed[UpdateDelegatedStake.Create]]])
          .values
          .toList
          .flatten

        wdsEventsForAcceptance = signedArtifact.delegatedStakesWithdrawals
          .getOrElse(SortedMap.empty[Address, List[Signed[UpdateDelegatedStake.Withdraw]]])
          .values
          .toList
          .flatten

        cncEventsForAcceptance = signedArtifact.activeNodeCollaterals
          .getOrElse(SortedMap.empty[Address, List[Signed[UpdateNodeCollateral.Create]]])
          .values
          .toList
          .flatten

        wncEventsForAcceptance = signedArtifact.nodeCollateralWithdrawals
          .getOrElse(SortedMap.empty[Address, List[Signed[UpdateNodeCollateral.Withdraw]]])
          .values
          .toList
          .flatten

        delegatedRewardsDist = new DelegatedRewardsDistributor[F] {
          override def calculateTotalRewardsToMint(epochProgress: epoch.EpochProgress): F[Amount] =
            Amount(NonNegLong.unsafeFrom(100L)).pure[F]

          override def calculateWithdrawalRewardTransactions(withdrawingBalances: Map[Address, Amount]): F[SortedSet[RewardTransaction]] =
            SortedSet.empty[RewardTransaction].pure[F]

          override def calculateDelegatorRewards(
            activeDelegatedStakes: SortedMap[Address, List[delegatedStake.DelegatedStakeRecord]],
            nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
            epochProgress: epoch.EpochProgress,
            totalAmount: Amount
          ): F[Map[Address, Map[Id, Amount]]] =
            Map.empty[Address, Map[Id, Amount]].pure[F]

          override def calculateNodeOperatorRewards(
            delegatorRewards: Map[Address, Map[Id, Amount]],
            nodeParametersMap: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)],
            nodesInConsensus: SortedSet[Id],
            epochProgress: epoch.EpochProgress,
            totalRewards: Amount
          ): F[SortedSet[RewardTransaction]] =
            SortedSet.empty[RewardTransaction].pure[F]
        }

        (
          acceptanceResult,
          _,
          _,
          _,
          _,
          _,
          returnedSCEvents,
          acceptedRewardTxs,
          snapshotInfo,
          _,
          _,
          _,
          _
        ) <-
          snapshotAcceptanceManager.accept(
            signedArtifact.ordinal,
            signedArtifact.epochProgress,
            blocksForAcceptance,
            allowSpendBlocksForAcceptance,
            tokenLockBlocksForAcceptance,
            scEvents,
            unpEventsForAcceptance,
            cdsEventsForAcceptance,
            wdsEventsForAcceptance,
            cncEventsForAcceptance,
            wncEventsForAcceptance,
            context,
            lastActiveTips,
            lastDeprecatedTips,
            delegatedRewardsDist,
            StateChannelValidationType.Historical,
            lastGlobalSnapshots,
            getGlobalSnapshotByOrdinal
          )

        _ <- CannotApplyBlocksError(acceptanceResult.notAccepted.map { case (_, reason) => reason })
          .raiseError[F, Unit]
          .whenA(acceptanceResult.notAccepted.nonEmpty)
        _ <- CannotApplyStateChannelsError(returnedSCEvents).raiseError[F, Unit].whenA(returnedSCEvents.nonEmpty)
        diffRewards = acceptedRewardTxs -- signedArtifact.rewards
        _ <- CannotApplyRewardsError(diffRewards).raiseError[F, Unit].whenA(diffRewards.nonEmpty)
        hashedArtifact <- HasherSelector[F].forOrdinal(signedArtifact.ordinal)(implicit hasher => signedArtifact.toHashed)
        calculatedStateProof <- HasherSelector[F].forOrdinal(signedArtifact.ordinal) { implicit hasher =>
          hasher.getLogic(signedArtifact.ordinal) match {
            case JsonHash => snapshotInfo.stateProof(signedArtifact.ordinal)
            case KryoHash => GlobalSnapshotInfoV2.fromGlobalSnapshotInfo(snapshotInfo).stateProof(signedArtifact.ordinal)
          }
        }
        validation <- StateProofValidator.validate(hashedArtifact, calculatedStateProof)
        _ = validation match {
          case Validated.Valid(_)   => Async[F].unit
          case Validated.Invalid(e) => e.raiseError[F, Unit]
        }

      } yield snapshotInfo
    }

  @derive(eqv, show)
  case class CannotApplyBlocksError(reasons: List[BlockNotAcceptedReason]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot ${reasons.show}"
  }

  @derive(eqv)
  case class CannotApplyStateChannelsError(returnedStateChannels: Set[StateChannelOutput]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot because of returned StateChannels for addresses: ${returnedStateChannels.map(_.address).show}"
  }

  @derive(eqv, show)
  case class CannotApplyRewardsError(notAcceptedRewards: SortedSet[RewardTransaction]) extends NoStackTrace {

    override def getMessage: String =
      s"Cannot build global snapshot because of not accepted rewards: ${notAcceptedRewards.show}"
  }
}
