package io.constellationnetwork.schema

import cats.Parallel
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.merkletree.syntax.SortedMapOpsImpl
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, SpendAction}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake.UpdateDelegatedStake
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.nodeCollateral.UpdateNodeCollateral
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.SnapshotVersion
import io.constellationnetwork.schema.snapshot.{FullSnapshot, IncrementalSnapshot}
import io.constellationnetwork.schema.swap.AllowSpendBlock
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary
import io.constellationnetwork.syntax.sortedCollection._

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt

@derive(eqv, show, encoder, decoder)
case class GlobalIncrementalSnapshotV1(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  rewards: SortedSet[RewardTransaction],
  epochProgress: EpochProgress,
  nextFacilitators: NonEmptyList[PeerId],
  tips: SnapshotTips,
  stateProof: GlobalSnapshotStateProofV1,
  version: SnapshotVersion = SnapshotVersion("0.0.1")
) extends IncrementalSnapshot[GlobalSnapshotStateProofV1] {
  def toGlobalIncrementalSnapshot: GlobalIncrementalSnapshot =
    GlobalIncrementalSnapshot(
      ordinal,
      height,
      subHeight,
      lastSnapshotHash,
      blocks,
      stateChannelSnapshots,
      rewards,
      Some(SortedMap.empty),
      epochProgress,
      nextFacilitators,
      tips,
      stateProof.toGlobalSnapshotStateProof,
      Some(SortedSet.empty),
      Some(SortedSet.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedSet.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      None,
      version
    )
}

object GlobalIncrementalSnapshotV1 {
  def fromGlobalIncrementalSnapshot(snapshot: GlobalIncrementalSnapshot): GlobalIncrementalSnapshotV1 =
    GlobalIncrementalSnapshotV1(
      snapshot.ordinal,
      snapshot.height,
      snapshot.subHeight,
      snapshot.lastSnapshotHash,
      snapshot.blocks,
      snapshot.stateChannelSnapshots,
      snapshot.rewards,
      snapshot.epochProgress,
      snapshot.nextFacilitators,
      snapshot.tips,
      GlobalSnapshotStateProofV1.fromGlobalSnapshotStateProof(snapshot.stateProof),
      snapshot.version
    )
}

@derive(eqv, show, encoder, decoder)
case class GlobalIncrementalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  rewards: SortedSet[RewardTransaction],
  delegateRewards: Option[SortedMap[PeerId, SortedMap[Address, Amount]]],
  epochProgress: EpochProgress,
  nextFacilitators: NonEmptyList[PeerId],
  tips: SnapshotTips,
  stateProof: GlobalSnapshotStateProof,
  allowSpendBlocks: Option[SortedSet[Signed[AllowSpendBlock]]],
  tokenLockBlocks: Option[SortedSet[Signed[TokenLockBlock]]],
  spendActions: Option[SortedMap[Address, List[SpendAction]]],
  updateNodeParameters: Option[SortedMap[Id, Signed[UpdateNodeParameters]]],
  artifacts: Option[SortedSet[SharedArtifact]],
  activeDelegatedStakes: Option[SortedMap[Address, List[Signed[UpdateDelegatedStake.Create]]]],
  delegatedStakesWithdrawals: Option[SortedMap[Address, List[Signed[UpdateDelegatedStake.Withdraw]]]],
  activeNodeCollaterals: Option[SortedMap[Address, List[Signed[UpdateNodeCollateral.Create]]]],
  nodeCollateralWithdrawals: Option[SortedMap[Address, List[Signed[UpdateNodeCollateral.Withdraw]]]],
  // v20: snapshot of the prev round's consensus-derived peer-behavior counters.
  // `None` for pre-v20 snapshots in storage and during conversions that have no
  // outcome to carry forward (V1 conversion, fromGlobalSnapshot at chain start).
  // Populated by the leader from `state.lastOutcome` and re-derived identically by
  // every validator -- determinism follows from the per-round outcome being
  // consensus-agreed.
  peerHistory: Option[ConsensusOperationalState] = None,
  version: SnapshotVersion = SnapshotVersion("0.0.1")
) extends IncrementalSnapshot[GlobalSnapshotStateProof]

object GlobalIncrementalSnapshot {
  def fromGlobalSnapshot[F[_]: Parallel: Async: Hasher: JsonSerializer](snapshot: GlobalSnapshot)(
    implicit stateProofSelector: StateProofSelector
  ): F[GlobalIncrementalSnapshot] = {
    val gsi = snapshot.info.toGlobalSnapshotInfo
    gsi.stateProof[F](snapshot.ordinal).map { stateProof =>
      GlobalIncrementalSnapshot(
        snapshot.ordinal,
        snapshot.height,
        snapshot.subHeight,
        snapshot.lastSnapshotHash,
        snapshot.blocks,
        snapshot.stateChannelSnapshots,
        snapshot.rewards,
        Some(SortedMap.empty),
        snapshot.epochProgress,
        snapshot.nextFacilitators,
        snapshot.tips,
        stateProof,
        Some(SortedSet.empty),
        Some(SortedSet.empty),
        Some(SortedMap.empty),
        gsi.updateNodeParameters.map(_.map { case (k, v) => (k, v._1) }),
        Some(SortedSet.empty),
        Some(SortedMap.empty),
        Some(SortedMap.empty),
        Some(SortedMap.empty),
        Some(SortedMap.empty),
        None
      )
    }
  }
}
