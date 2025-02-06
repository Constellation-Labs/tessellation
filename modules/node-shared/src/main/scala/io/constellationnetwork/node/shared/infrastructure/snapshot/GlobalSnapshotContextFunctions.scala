package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.Validated
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.merkletree.StateProofValidator
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.snapshot.SnapshotContextFunctions
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelValidationType}

import derevo.cats.{eqv, show}
import derevo.derive

abstract class GlobalSnapshotContextFunctions[F[_]] extends SnapshotContextFunctions[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]

object GlobalSnapshotContextFunctions {
  def make[F[_]: Async: HasherSelector](snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[F]) =
    new GlobalSnapshotContextFunctions[F] {
      def createContext(
        context: GlobalSnapshotInfo,
        lastArtifact: Signed[GlobalIncrementalSnapshot],
        signedArtifact: Signed[GlobalIncrementalSnapshot],
        lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
      )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo] = for {
        lastActiveTips <- HasherSelector[F].forOrdinal(lastArtifact.ordinal)(implicit hasher => lastArtifact.activeTips)
        lastDeprecatedTips = lastArtifact.tips.deprecated

        blocksForAcceptance = signedArtifact.blocks.toList.map(_.block)
        allowSpendBlocksForAcceptance = signedArtifact.allowSpendBlocks.map(_.toList).getOrElse(List.empty)

        scEvents = signedArtifact.stateChannelSnapshots.toList.flatMap {
          case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _)).toList
        }
        (acceptanceResult, allowSpendBlockAcceptanceResult, scSnapshots, returnedSCEvents, acceptedRewardTxs, snapshotInfo, _, _) <-
          snapshotAcceptanceManager.accept(
            signedArtifact.ordinal,
            signedArtifact.epochProgress,
            blocksForAcceptance,
            allowSpendBlocksForAcceptance,
            scEvents,
            context,
            lastActiveTips,
            lastDeprecatedTips,
            _ => signedArtifact.rewards.pure[F],
            StateChannelValidationType.Historical,
            lastGlobalSnapshots
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
        _ <- StateProofValidator.validate(hashedArtifact, calculatedStateProof) match {
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
