package org.tessellation.sdk.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.{Applicative, Eq}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.sdk.domain.block.processing.{BlockAcceptanceResult, deprecationThreshold}
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.domain.consensus.ConsensusFunctions.InvalidArtifact
import org.tessellation.sdk.domain.fork.ForkInfo
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.infrastructure.consensus.trigger.ConsensusTrigger
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends NoStackTrace
case object NoTipsRemaining extends NoStackTrace
case object ArtifactMismatch extends InvalidArtifact

abstract class SnapshotConsensusFunctions[
  F[_]: Async: SecurityProvider: KryoSerializer,
  Event,
  Artifact <: Snapshot: Eq,
  Context,
  Trigger <: ConsensusTrigger
](implicit ordering: Ordering[BlockAsActiveTip])
    extends ConsensusFunctions[F, Event, SnapshotOrdinal, Artifact, Context] {

  def gossipForkInfo(gossip: Gossip[F], signed: Signed[Artifact]): F[Unit] =
    signed.hash.liftTo[F].flatMap(h => gossip.spread(ForkInfo(signed.value.ordinal, h)))

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
    facilitators: Set[PeerId]
  ): F[Either[InvalidArtifact, (Artifact, Context)]]

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
