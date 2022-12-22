package org.tessellation.infrastructure.snapshot

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Supervisor
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.list._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.block.processing._
import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.dag.snapshot.{GlobalSnapshot, _}
import org.tessellation.domain.rewards.Rewards
import org.tessellation.domain.snapshot.GlobalSnapshotStorage
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.key.ops.PublicKeyOps
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.security.signature.Signed.forAsyncKryo
import org.tessellation.schema.statechannels.{StateChannelOutput, StateChannelSnapshotBinary}
import org.tessellation.schema.transaction.{RewardTransaction, Transaction}
import org.tessellation.schema.{ID, SnapshotOrdinal}
import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.infrastructure.consensus.trigger.EventTrigger
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotConsensusFunctionsSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], SecurityProvider[IO], Metrics[IO])

  override def sharedResource: Resource[IO, Res] =
    Supervisor[IO].flatMap { supervisor =>
      KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sdkKryoRegistrar)).flatMap { ks =>
        SecurityProvider.forAsync[IO].flatMap { sp =>
          Metrics.forAsync[IO](Seq.empty).map((supervisor, ks, sp, _))
        }
      }
    }

  val gss: GlobalSnapshotStorage[IO] = new GlobalSnapshotStorage[IO] {

    override def prepend(snapshot: Signed[GlobalSnapshot]): IO[Boolean] = ???

    override def head: IO[Option[Signed[GlobalSnapshot]]] = ???

    override def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalSnapshot]]] = ???

    override def get(hash: Hash): IO[Option[Signed[GlobalSnapshot]]] = ???

  }

  val bam: BlockAcceptanceManager[IO] = new BlockAcceptanceManager[IO] {

    override def acceptBlocksIteratively(blocks: List[Signed[DAGBlock]], context: BlockAcceptanceContext[IO]): IO[BlockAcceptanceResult] =
      BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        List.empty,
        List.empty
      ).pure[IO]

    override def acceptBlock(
      block: Signed[DAGBlock],
      context: BlockAcceptanceContext[IO]
    ): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] = ???

  }

  val scProcessor = new GlobalSnapshotStateChannelEventsProcessor[IO] {
    def process(
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent]
    ): IO[(SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]], Set[GlobalSnapshotEvent])] = IO(
      (events.groupByNel(_.address).view.mapValues(_.map(_.snapshot)).toSortedMap, Set.empty)
    )
  }

  val collateral: Amount = Amount.empty

  val rewards: Rewards[IO] = new Rewards[IO] {

    override def mintedDistribution(
      epochProgress: EpochProgress,
      facilitators: NonEmptySet[ID.Id]
    ): IO[SortedSet[RewardTransaction]] = ???

    override def feeDistribution(
      snapshotOrdinal: SnapshotOrdinal,
      transactions: SortedSet[Transaction],
      facilitators: NonEmptySet[ID.Id]
    ): IO[SortedSet[RewardTransaction]] = IO(SortedSet.empty)

    override def getAmountByEpoch(epochProgress: EpochProgress, rewardsPerEpoch: SortedMap[EpochProgress, Amount]): Amount =
      ???

  }

  val env: AppEnvironment = AppEnvironment.Testnet

  test("validateArtifact - returns artifact for correct data") { res =>
    implicit val (_, ks, sp, m) = res

    val gscf = GlobalSnapshotConsensusFunctions.make(gss, bam, scProcessor, collateral, rewards, env)

    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
        mkStateChannelEvent().flatMap { scEvent =>
          gscf.createProposalArtifact(SnapshotOrdinal.MinValue, signedGenesis, EventTrigger, Set(scEvent.asLeft[DAGEvent])).flatMap {
            lastArtifact =>
              gscf.validateArtifact(signedGenesis, EventTrigger)(lastArtifact._1).map { result =>
                expect.same(result.isRight, true) && expect
                  .same(result.map(_.stateChannelSnapshots(scEvent.address)), Right(NonEmptyList.one(scEvent.snapshot)))
              }
          }
        }
      }
    }
  }

  test("validateArtifact - returns invalid artifact error for incorrect data") { res =>
    implicit val (_, ks, sp, m) = res

    val gscf = GlobalSnapshotConsensusFunctions.make(gss, bam, scProcessor, collateral, rewards, env)

    KeyPairGenerator.makeKeyPair[IO].flatMap { keyPair =>
      val genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
        mkStateChannelEvent().flatMap {
          case scEvent =>
            gscf.createProposalArtifact(SnapshotOrdinal.MinValue, signedGenesis, EventTrigger, Set(scEvent.asLeft[DAGEvent])).flatMap {
              lastArtifact =>
                gscf.validateArtifact(signedGenesis, EventTrigger)(lastArtifact._1.copy(ordinal = lastArtifact._1.ordinal.next)).map {
                  result =>
                    expect.same(result.isLeft, true)
                }
            }
        }
      }
    }
  }

  def mkStateChannelEvent()(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes)
    signedSC <- forAsyncKryo(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
