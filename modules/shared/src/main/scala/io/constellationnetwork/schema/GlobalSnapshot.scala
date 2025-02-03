package io.constellationnetwork.schema

import cats.data.NonEmptyList
import cats.effect.kernel.Sync
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendAction
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.SnapshotVersion
import io.constellationnetwork.schema.snapshot.{FullSnapshot, IncrementalSnapshot}
import io.constellationnetwork.schema.swap.AllowSpendBlock
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
case class GlobalIncrementalSnapshot(
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
  stateProof: GlobalSnapshotStateProof,
  allowSpendBlocks: Option[SortedSet[Signed[AllowSpendBlock]]],
  spendActions: Option[SortedMap[Address, List[SpendAction]]],
  updateNodeParameters: Option[SortedMap[Id, Signed[UpdateNodeParameters]]],
  version: SnapshotVersion = SnapshotVersion("0.0.1")
) extends IncrementalSnapshot[GlobalSnapshotStateProof]

object GlobalIncrementalSnapshot {
  def fromGlobalSnapshot[F[_]: Sync: Hasher](snapshot: GlobalSnapshot): F[GlobalIncrementalSnapshot] =
    snapshot.info.stateProof(snapshot.ordinal).map { stateProof =>
      GlobalIncrementalSnapshot(
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
        stateProof,
        Some(SortedSet.empty),
        Some(SortedMap.empty),
        snapshot.info.updateNodeParameters.map(_.map { case (k, v) => (k, v._1) })
      )
    }
}

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
      epochProgress,
      nextFacilitators,
      tips,
      stateProof.toGlobalSnapshotStateProof,
      Some(SortedSet.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
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
case class GlobalSnapshot(
  ordinal: SnapshotOrdinal,
  height: Height,
  subHeight: SubHeight,
  lastSnapshotHash: Hash,
  blocks: SortedSet[BlockAsActiveTip],
  stateChannelSnapshots: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
  rewards: SortedSet[RewardTransaction],
  epochProgress: EpochProgress,
  nextFacilitators: NonEmptyList[PeerId],
  info: GlobalSnapshotInfoV1,
  tips: SnapshotTips
) extends FullSnapshot[GlobalSnapshotStateProof, GlobalSnapshotInfoV1] {}

object GlobalSnapshot {

  def mkGenesis(balances: Map[Address, Balance], startingEpochProgress: EpochProgress): GlobalSnapshot =
    GlobalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Coinbase.hash,
      SortedSet.empty[BlockAsActiveTip],
      SortedMap.empty,
      SortedSet.empty,
      startingEpochProgress,
      nextFacilitators,
      GlobalSnapshotInfoV1(SortedMap.empty, SortedMap.empty, SortedMap.from(balances)),
      SnapshotTips(
        SortedSet.empty[DeprecatedTip],
        mkActiveTips(8)
      )
    )

  def mkFirstIncrementalSnapshot[F[_]: Sync: Hasher](
    genesis: Hashed[GlobalSnapshot]
  ): F[GlobalIncrementalSnapshot] =
    genesis.info.stateProof(genesis.ordinal).map { stateProof =>
      GlobalIncrementalSnapshot(
        genesis.ordinal.next,
        genesis.height,
        genesis.subHeight.next,
        genesis.hash,
        SortedSet.empty,
        SortedMap.empty,
        SortedSet.empty,
        genesis.epochProgress.next,
        nextFacilitators,
        genesis.tips,
        stateProof,
        Some(SortedSet.empty),
        Some(SortedMap.empty),
        Some(SortedMap.empty)
      )
    }

  val nextFacilitators: NonEmptyList[PeerId] =
    NonEmptyList
      .of(
        "e0c1ee6ec43510f0e16d2969a7a7c074a5c8cdb477c074fe9c32a9aad8cbc8ff1dff60bb81923e0db437d2686a9b65b86c403e6a21fa32b6acc4e61be4d70925"
      )
      .map(s => PeerId(Hex(s)))

  private def mkActiveTips(n: PosInt): SortedSet[ActiveTip] =
    List
      .range(0, n.value)
      .map { i =>
        ActiveTip(BlockReference(Height.MinValue, ProofsHash(s"%064d".format(i))), 0L, SnapshotOrdinal.MinValue)
      }
      .toSortedSet

}
