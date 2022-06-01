package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.{Applicative, Eval}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import org.tessellation.dag.block.processing._
import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot._
import org.tessellation.domain.snapshot._
import org.tessellation.domain.snapshot.rewards.Rewards
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotConsensusFunctions[F[_]]
    extends ConsensusFunctions[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact] {}

object GlobalSnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer](
    globalSnapshotStorage: GlobalSnapshotStorage[F],
    heightInterval: NonNegLong,
    blockAcceptanceManager: BlockAcceptanceManager[F]
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(GlobalSnapshotConsensusFunctions.getClass)

    def consumeSignedMajorityArtifact(signedArtifact: Signed[GlobalSnapshotArtifact]): F[Unit] =
      globalSnapshotStorage
        .prepend(signedArtifact)
        .ifM(Applicative[F].unit, logger.error("Cannot save GlobalSnapshot into the storage"))

    def triggerPredicate(
      last: (GlobalSnapshotKey, Signed[GlobalSnapshotArtifact]),
      event: GlobalSnapshotEvent
    ): Boolean = event.toOption.flatMap(_.toOption).fold(false) {
      case TipSnapshotTrigger(height) => last._2.value.height.nextN(heightInterval) === height
      case TimeSnapshotTrigger()      => true
    }

    def createProposalArtifact(
      last: (GlobalSnapshotKey, Signed[GlobalSnapshotArtifact]),
      events: Set[GlobalSnapshotEvent]
    ): F[(GlobalSnapshotArtifact, Set[GlobalSnapshotEvent])] = {
      val (_, lastGS) = last

      val scEvents = events.toList.mapFilter(_.swap.toOption)
      val dagEvents: Seq[DAGEvent] = events.toList.mapFilter(_.toOption)

      val blocksForAcceptance = dagEvents
        .mapFilter[Signed[DAGBlock]](_.swap.toOption)
        .filter(_.height > lastGS.height)
        .toList

      for {
        lastGSHash <- lastGS.value.hashF
        currentOrdinal = lastGS.ordinal.next
        (scSnapshots, returnedSCEvents) = processStateChannelEvents(lastGS.info, scEvents)
        sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
          .map(_.toMap)
        updatedLastStateChannelSnapshotHashes = lastGS.info.lastStateChannelSnapshotHashes ++ sCSnapshotHashes

        lastActiveTips <- lastGS.activeTips
        lastDeprecatedTips = lastGS.tips.deprecated

        tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
        context = BlockAcceptanceContext.fromStaticData(lastGS.info.balances, lastGS.info.lastTxRefs, tipUsages)
        acceptanceResult <- blockAcceptanceManager.acceptBlocks(blocksForAcceptance, context)

        (deprecated, remainedActive, accepted) = getUpdatedTips(
          lastActiveTips,
          lastDeprecatedTips,
          acceptanceResult,
          currentOrdinal
        )

        (height, subHeight) <- getHeightAndSubHeight(lastGS, deprecated, remainedActive, accepted)

        updatedLastTxRefs = lastGS.info.lastTxRefs ++ acceptanceResult.contextUpdate.lastTxRefs
        updatedBalances = lastGS.info.balances ++ acceptanceResult.contextUpdate.balances

        rewards = Rewards.calculateRewards(lastGS.proofs.map(_.id))

        returnedDAGEvents = getReturnedDAGEvents(acceptanceResult)

        globalSnapshot = GlobalSnapshot(
          currentOrdinal,
          height,
          subHeight,
          lastGSHash,
          accepted,
          scSnapshots,
          rewards,
          NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
          GlobalSnapshotInfo(
            updatedLastStateChannelSnapshotHashes,
            updatedLastTxRefs,
            updatedBalances
          ),
          GlobalSnapshotTips(
            deprecated = deprecated,
            remainedActive = remainedActive
          )
        )
        returnedEvents = returnedSCEvents.union(returnedDAGEvents)
      } yield (globalSnapshot, returnedEvents)
    }

    private def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

    private def getUpdatedTips(
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

    private def getHeightAndSubHeight(
      lastGS: GlobalSnapshot,
      deprecated: Set[DeprecatedTip],
      remainedActive: Set[ActiveTip],
      accepted: Set[BlockAsActiveTip]
    ): F[(Height, SubHeight)] = {
      val tipHeights = (deprecated.map(_.block.height) ++ remainedActive.map(_.block.height) ++ accepted
        .map(_.block.height)).toList

      for {
        height <- tipHeights.minimumOption.liftTo[F](NoTipsRemaining)

        _ <- if (height < lastGS.height)
          InvalidHeight(lastGS.height, height).raiseError
        else
          Applicative[F].unit

        subHeight = if (height === lastGS.height) lastGS.subHeight.next else SubHeight.MinValue
      } yield (height, subHeight)
    }

    private def getReturnedDAGEvents(
      acceptanceResult: BlockAcceptanceResult
    ): Set[GlobalSnapshotEvent] =
      acceptanceResult.notAccepted.mapFilter {
        case (signedBlock, _: BlockAwaitReason) => signedBlock.asLeft[SnapshotTrigger].asRight[StateChannelEvent].some
        case _                                  => none
      }.toSet

    case class InvalidHeight(lastHeight: Height, currentHeight: Height) extends NoStackTrace
    case object NoTipsRemaining extends NoStackTrace

    private def processStateChannelEvents(
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent]
    ): (SortedMap[Address, NonEmptyList[StateChannelSnapshotBinary]], Set[GlobalSnapshotEvent]) = {
      val lshToSnapshot: Map[(Address, Hash), StateChannelEvent] = events.map { e =>
        (e.address, e.snapshot.value.lastSnapshotHash) -> e
      }.foldLeft(Map.empty[(Address, Hash), StateChannelEvent]) { (acc, entry) =>
        entry match {
          case (k, newEvent) =>
            acc.updatedWith(k) { maybeEvent =>
              maybeEvent
                .fold(newEvent) { event =>
                  if (Hash.fromBytes(event.snapshot.content) < Hash.fromBytes(newEvent.snapshot.content))
                    event
                  else
                    newEvent
                }
                .some
            }
        }
      }

      val result = events
        .map(_.address)
        .distinct
        .mapFilter { address =>
          lastGlobalSnapshotInfo.lastStateChannelSnapshotHashes
            .get(address)
            .map(hash => address -> hash)
        }
        .mapFilter {
          case (address, initLsh) =>
            def unfold(lsh: Hash): Eval[List[StateChannelEvent]] =
              lshToSnapshot
                .get((address, lsh))
                .map { go =>
                  for {
                    head <- Eval.now(go)
                    tail <- unfold(Hash.fromBytes(go.snapshot.content))
                  } yield head :: tail
                }
                .getOrElse(Eval.now(List.empty))

            unfold(initLsh).value.toNel.map(
              nel =>
                address -> nel.map { event =>
                  StateChannelSnapshotBinary(event.snapshot.value.lastSnapshotHash, event.snapshot.content)

                }.reverse
            )
        }
        .toSortedMap

      (result, Set.empty)
    }

  }
}
