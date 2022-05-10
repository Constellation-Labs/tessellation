package org.tessellation.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.functorFilter._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.{Applicative, Eval}

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.snapshot._
import org.tessellation.domain.snapshot.rewards.Rewards
import org.tessellation.domain.snapshot.{GlobalSnapshotStorage, TimeSnapshotTrigger, TipSnapshotTrigger}
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.SubHeight
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless.Typeable

trait GlobalSnapshotConsensusFunctions[F[_]]
    extends ConsensusFunctions[F, GlobalSnapshotEvent, GlobalSnapshotKey, GlobalSnapshotArtifact] {}

object GlobalSnapshotConsensusFunctions {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    globalSnapshotStorage: GlobalSnapshotStorage[F],
    heightInterval: NonNegLong
  ): GlobalSnapshotConsensusFunctions[F] = new GlobalSnapshotConsensusFunctions[F] {

    private val logger = Slf4jLogger.getLoggerFromClass(GlobalSnapshotConsensusFunctions.getClass)
    private val tipSnapshotTriggerTypable = Typeable[TipSnapshotTrigger]

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

      val heightLimit = lastGS.height.nextN(heightInterval)

      val blocksInRange: Set[Signed[DAGBlock]] =
        dagEvents
          .mapFilter[Signed[DAGBlock]](_.swap.toOption)
          .filter { signedBlock =>
            signedBlock.value.height <= heightLimit && signedBlock.value.height > lastGS.height
          }
          .toSet

      val transactionsInRange: Set[Signed[Transaction]] = blocksInRange.flatMap(_.transactions)

      val tipTriggersEvents = dagEvents.mapFilter(_.toOption).mapFilter(tipSnapshotTriggerTypable.cast)

      val returnedDAGEvents =
        tipTriggersEvents
          .filter(_.height >= heightLimit)
          .map(toEvent)
          .toSet

      for {
        lastGSHash <- lastGS.value.hashF
        (scSnapshots, returnedSCEvents) = processStateChannelEvents(lastGS.info, scEvents)

        sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
          .map(_.toMap)

        ordinal = lastGS.ordinal.next
        maybeTipTrigger = tipTriggersEvents.find(_.height === heightLimit)

        lastTxRefs <- transactionsInRange
          .groupBy(_.source)
          .mapFilter(_.maxByOption(_.ordinal))
          .toList
          .traverse {
            case (address, tx) =>
              TransactionReference.of(tx).map((address, _))
          }
          .map(_.toMap)

        balances <- Balance
          .applyTransactions(
            transactionsInRange.map(_.value),
            address => lastGS.info.balances.getOrElse(address, Balance.empty).pure[F]
          )
          .map(_.toMap)

        rewards = Rewards.calculateRewards(lastGS.proofs.map(_.id))

        globalSnapshot = GlobalSnapshot(
          ordinal,
          maybeTipTrigger.fold(lastGS.height)(_.height),
          maybeTipTrigger.fold(lastGS.subHeight.next)(_ => SubHeight.MinValue),
          lastGSHash,
          blocksInRange.map(BlockAsActiveTip(_, 0L)),
          scSnapshots,
          rewards,
          NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
          GlobalSnapshotInfo(
            lastGS.info.lastStateChannelSnapshotHashes ++ sCSnapshotHashes,
            (lastGS.info.lastTxRefs ++ lastTxRefs).groupMapReduce(_._1)(_._2) { case (_, updated) => updated },
            (lastGS.info.balances ++ balances).groupMapReduce(_._1)(_._2) { case (_, updated)     => updated }
          ),
          lastGS.tips
        )
        returnedEvents = returnedSCEvents.union(returnedDAGEvents)
      } yield (globalSnapshot, returnedEvents)
    }

    private def processStateChannelEvents(
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent]
    ): (Map[Address, NonEmptyList[StateChannelSnapshotBinary]], Set[GlobalSnapshotEvent]) = {
      val lshToSnapshot: Map[(Address, Hash), StateChannelEvent] = events.map { e =>
        (e.address, e.outputGist.lastSnapshotHash) -> e
      }.foldLeft(Map.empty[(Address, Hash), StateChannelEvent]) { (acc, entry) =>
        entry match {
          case (k, newEvent) =>
            acc.updatedWith(k) { maybeEvent =>
              maybeEvent
                .fold(newEvent) { event =>
                  if (Hash.fromBytes(event.outputBinary) < Hash.fromBytes(newEvent.outputBinary))
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
                    tail <- unfold(Hash.fromBytes(go.outputBinary))
                  } yield head :: tail
                }
                .getOrElse(Eval.now(List.empty))

            unfold(initLsh).value.toNel.map(
              nel =>
                address -> nel
                  .map(event => StateChannelSnapshotBinary(event.outputGist.lastSnapshotHash, event.outputBinary))
                  .reverse
            )
        }
        .toMap

      (result, Set.empty)
    }

  }
}
