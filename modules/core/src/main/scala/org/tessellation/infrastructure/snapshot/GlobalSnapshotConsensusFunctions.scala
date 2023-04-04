import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._

import org.tessellation.domain.rewards.Rewards
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.RewardTransaction
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.transaction.DAGTransaction
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.Mainnet
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.sdk.domain.snapshot.storage.SnapshotStorage
import org.tessellation.sdk.infrastructure.consensus.trigger.{ConsensusTrigger, EventTrigger, TimeTrigger}
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput
import org.tessellation.merkletree.syntax._

import eu.timepit.refined.auto._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import eu.timepit.refined.types.numeric
import scala.collection.immutable.SortedMap
import scala.collection.immutable.SortedSet
import org.tessellation.sdk.infrastructure.snapshot.GlobalSnapshotStateChannelEventsProcessor
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensusFunctions
import org.tessellation.sdk.infrastructure.snapshot.ArtifactMismatch
import cats.data.NonEmptyList
import org.tessellation.statechannel.StateChannelSnapshotBinary
import org.tessellation.merkletree.Proof

abstract class GlobalSnapshotConsensusFunctions[F[_]: Async: SecurityProvider]
    extends SnapshotConsensusFunctions[
      F,
      DAGTransaction,
      DAGBlock,
      GlobalSnapshotStateProof,
      GlobalSnapshotEvent,
      GlobalSnapshotArtifact,
      GlobalSnapshotContext,
      ConsensusTrigger
    ] {}

object GlobalSnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider: Metrics](
    blockAcceptanceManager: BlockAcceptanceManager[F, DAGTransaction, DAGBlock],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount,
    rewards: Rewards[F],
    environment: AppEnvironment
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(GlobalSnapshotConsensusFunctions.getClass)

    def getRequiredCollateral: Amount = collateral

    override def validateArtifact(
      lastSignedArtifact: Signed[GlobalIncrementalSnapshot],
      lastContext: GlobalSnapshotInfo,
      trigger: ConsensusTrigger
    )(
      artifact: GlobalIncrementalSnapshot
    ): F[Either[InvalidArtifact, GlobalIncrementalSnapshot]] = {
      val dagEvents = artifact.blocks.unsorted.map(_.block.asRight[StateChannelOutput])
      val scEvents = artifact.stateChannelSnapshots.toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _).asLeft[DAGEvent]).toList
      }
      val events = dagEvents ++ scEvents

      def recreatedArtifact: F[GlobalIncrementalSnapshot] =
        createProposalArtifact(lastSignedArtifact.ordinal, lastSignedArtifact, lastContext, trigger, events)
          .map(_._1)

      recreatedArtifact
        .map(_ === artifact)
        .ifF(
          artifact.asRight[InvalidArtifact],
          ArtifactMismatch.asLeft[GlobalIncrementalSnapshot]
        )
    }

    def extractEvents(artifact: Artifact) = {
      val dagEvents = artifact.blocks.unsorted.map(_.block.asRight[StateChannelOutput])
      val scEvents = artifact.stateChannelSnapshots.toList.flatMap {
        case (address, stateChannelBinaries) => stateChannelBinaries.map(StateChannelOutput(address, _).asLeft[DAGEvent]).toList
      }
      dagEvents ++ scEvents
    }

    def extractTrigger(lastArtifact: Artifact, artifact: Artifact) =
      lastArtifact

    def createProposalArtifact(
      lastKey: GlobalSnapshotKey,
      lastArtifact: Signed[GlobalSnapshotArtifact],
      snapshotContext: GlobalSnapshotContext,
      trigger: ConsensusTrigger,
      events: Set[GlobalSnapshotEvent]
    ): F[(GlobalSnapshotArtifact, GlobalSnapshotContext, Set[GlobalSnapshotEvent])] = {
      val (scEvents: List[StateChannelEvent], dagEvents: List[DAGEvent]) = events.filter { event =>
        if (environment == Mainnet) event.isRight else true
      }.toList.partitionMap(identity)

      val blocksForAcceptance = dagEvents
        .filter(_.height > lastArtifact.height)

      for {
        lastArtifactHash <- lastArtifact.value.hashF
        currentOrdinal = lastArtifact.ordinal.next
        currentEpochProgress = trigger match {
          case EventTrigger => lastArtifact.epochProgress
          case TimeTrigger  => lastArtifact.epochProgress.next
        }

        lastActiveTips <- lastArtifact.activeTips
        lastDeprecatedTips = lastArtifact.tips.deprecated

        facilitators = lastArtifact.proofs.map(_.id)
        transactions = lastArtifact.value.blocks.flatMap(_.block.transactions.toSortedSet).map(_.value)

        rewardTxsForAcceptance <- rewards.feeDistribution(lastArtifact.ordinal, transactions, facilitators).flatMap { feeRewardTxs =>
          trigger match {
            case EventTrigger => feeRewardTxs.pure[F]
            case TimeTrigger  => rewards.mintedDistribution(lastArtifact.epochProgress, facilitators).map(_ ++ feeRewardTxs)
          }
        }

        (acceptanceResult, scSnapshots, returnedSCEvents, acceptedRewardTxs, snapshotInfo) <- accept(
          blocksForAcceptance,
          scEvents,
          rewardTxsForAcceptance,
          snapshotContext,
          lastActiveTips,
          lastDeprecatedTips
        )
        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastArtifact, deprecated, remainedActive, accepted)

        returnedDAGEvents = getReturnedDAGEvents(acceptanceResult)
        stateProof <- snapshotInfo.stateProof

        globalSnapshot = GlobalIncrementalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastArtifactHash,
          accepted,
          scSnapshots,
          acceptedRewardTxs,
          currentEpochProgress,
          GlobalSnapshot.nextFacilitators,
          SnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          ),
          stateProof
        )
        returnedEvents = returnedSCEvents.map(_.asLeft[DAGEvent]).union(returnedDAGEvents)
      } yield (globalSnapshot, snapshotInfo, returnedEvents)
    }

    private def accept(
      blocksForAcceptance: List[Signed[DAGBlock]],
      scEvents: List[StateChannelOutput],
      rewards: SortedSet[RewardTransaction],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ): F[
      (
        BlockAcceptanceResult[DAGBlock],
        SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        Set[StateChannelOutput],
        SortedSet[RewardTransaction],
        GlobalSnapshotInfo
      )
    ] = for {
      acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips)

      (scSnapshots, currencySnapshots, returnedSCEvents) <- stateChannelEventsProcessor.process(lastSnapshotContext, scEvents)
      sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
        .map(_.toMap)
      updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
      updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots

      transactionsRefs = lastSnapshotContext.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs

      (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
        lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances,
        rewards
      )

      maybeMerkleTree <- updatedLastCurrencySnapshots.merkleTree[F]
      updatedLastCurrencySnapshotProofs <- maybeMerkleTree.traverse { merkleTree =>
        updatedLastCurrencySnapshots.toList.traverse {
          case (address, state) =>
            (address, state).hashF
              .map(merkleTree.findPath(_))
              .map(_.get) // NOTE: path must exists, otherwise it's an unexpected state and should throw
              .map((address, _))
        }
      }.map(_.map(SortedMap.from(_)).getOrElse(SortedMap.empty[Address, Proof]))

    } yield
      (
        acceptanceResult,
        scSnapshots,
        returnedSCEvents,
        acceptedRewardTxs,
        GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          transactionsRefs,
          updatedBalancesByRewards,
          updatedLastCurrencySnapshots,
          updatedLastCurrencySnapshotProofs
        )
      )

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[DAGBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip]
    ) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context)
    }

    private def acceptRewardTxs(
      balances: SortedMap[Address, Balance],
      txs: SortedSet[RewardTransaction]
    ): (SortedMap[Address, Balance], SortedSet[RewardTransaction]) =
      txs.foldLeft((balances, SortedSet.empty[RewardTransaction])) { (acc, tx) =>
        val (updatedBalances, acceptedTxs) = acc

        updatedBalances
          .getOrElse(tx.destination, Balance.empty)
          .plus(tx.amount)
          .map(balance => (updatedBalances.updated(tx.destination, balance), acceptedTxs + tx))
          .getOrElse(acc)
      }

    def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, numeric.NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

    private def getReturnedDAGEvents(
      acceptanceResult: BlockAcceptanceResult[DAGBlock]
    ): Set[GlobalSnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.asRight[StateChannelEvent].some
        case _                                  => none
      }.toSet

  }

}
