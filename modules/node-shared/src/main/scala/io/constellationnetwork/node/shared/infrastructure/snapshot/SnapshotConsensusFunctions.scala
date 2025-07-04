package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.node.shared.domain.block.processing.{BlockAcceptanceResult, deprecationThreshold}
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions.InvalidArtifact
import io.constellationnetwork.node.shared.domain.fork.ForkInfo
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.snapshot.Snapshot
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}
import io.constellationnetwork.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Encoder

case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends NoStackTrace
case object NoTipsRemaining extends NoStackTrace
case class GlobalArtifactMismatch(expected: GlobalIncrementalSnapshot, found: GlobalIncrementalSnapshot) extends InvalidArtifact
case class CurrencyArtifactMismatch(errors: List[CurrencySnapshotValidationError]) extends InvalidArtifact

abstract class SnapshotConsensusFunctions[
  F[_]: Async: SecurityProvider,
  Event,
  Artifact <: Snapshot,
  Context,
  Trigger <: ConsensusTrigger
](implicit ordering: Ordering[BlockAsActiveTip])
    extends ConsensusFunctions[F, Event, SnapshotOrdinal, Artifact, Context] {

  def getRequiredCollateral: Amount

  def getBalances(context: Context): SortedMap[Address, Balance]

  def triggerPredicate(event: Event): Boolean = true

  def facilitatorFilter(lastSignedArtifact: Signed[Artifact], lastContext: Context, peerId: peer.PeerId): F[Boolean] =
    peerId.toAddress[F].map { address =>
      getBalances(lastContext).getOrElse(address, Balance.empty).satisfiesCollateral(getRequiredCollateral)
    }

  def validateArtifact(
    lastSignedArtifact: Signed[Artifact],
    lastContext: Context,
    trigger: ConsensusTrigger,
    artifact: Artifact,
    facilitators: Set[PeerId],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[Either[InvalidArtifact, (Artifact, Context)]]

  protected def getUpdatedTips(
    lastActive: SortedSet[ActiveTip],
    lastDeprecated: SortedSet[DeprecatedTip],
    acceptanceResult: BlockAcceptanceResult,
    currentOrdinal: SnapshotOrdinal
  ): (SortedSet[DeprecatedTip], SortedSet[ActiveTip], SortedSet[BlockAsActiveTip]) = {
    val usagesUpdate = acceptanceResult.contextUpdate.parentUsages
    val accepted =
      acceptanceResult.accepted.map { case (block, usages) => BlockAsActiveTip(block, usages) }.toSortedSet
    val (remainedActive, newlyDeprecated) = lastActive.partitionMap { at =>
      val maybeUpdatedUsage = usagesUpdate.get(at.block)
      Either.cond(
        maybeUpdatedUsage.exists(_ >= deprecationThreshold),
        DeprecatedTip(at.block, currentOrdinal),
        maybeUpdatedUsage.map(uc => at.copy(usageCount = uc)).getOrElse(at)
      )
    }.bimap(_.toSortedSet, _.toSortedSet)
    val lowestActiveIntroducedAt = remainedActive.toList.map(_.introducedAt).minimumOption.getOrElse(currentOrdinal)
    val remainedDeprecated = lastDeprecated.filter(_.deprecatedAt > lowestActiveIntroducedAt)

    (remainedDeprecated | newlyDeprecated, remainedActive, accepted)
  }

  protected def getTipsUsages(
    lastActive: Set[ActiveTip],
    lastDeprecated: Set[DeprecatedTip]
  ): Map[BlockReference, NonNegLong] = {
    val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
    val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

    activeTipsUsages ++ deprecatedTipsUsages
  }

  protected def getHeightAndSubHeight(
    lastGS: Artifact,
    deprecated: Set[DeprecatedTip],
    remainedActive: Set[ActiveTip],
    accepted: Set[BlockAsActiveTip]
  ): F[(Height, SubHeight)] = {
    val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
      .map(_.block.height)).toList

    for {
      height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

      _ <-
        if (height < lastGS.height)
          InvalidHeight(lastGS.height, height).raiseError
        else
          Applicative[F].unit

      subHeight = if (height === lastGS.height) lastGS.subHeight.next else SubHeight.MinValue
    } yield (height, subHeight)
  }

}

object SnapshotConsensusFunctions {
  def gossipForkInfo[F[_]: MonadThrow: Hasher, Artifact <: Snapshot: Encoder](
    gossip: Gossip[F],
    signed: Signed[Artifact]
  ): F[Unit] =
    signed.hash.flatMap(h => gossip.spread(ForkInfo(signed.value.ordinal, h)))
}
