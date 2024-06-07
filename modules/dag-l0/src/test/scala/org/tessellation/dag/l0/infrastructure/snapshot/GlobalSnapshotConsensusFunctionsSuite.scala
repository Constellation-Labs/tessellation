package org.tessellation.dag.l0.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.effect.std.Supervisor
import cats.effect.{IO, Ref, Resource}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.list._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.dag.l0.dagL0KryoRegistrar
import org.tessellation.dag.l0.domain.snapshot.programs.{GlobalSnapshotEventCutter, SnapshotBinaryFeeCalculator}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.node.shared.domain.fork.ForkInfo
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.domain.statechannel.StateChannelAcceptanceResult
import org.tessellation.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import org.tessellation.node.shared.infrastructure.consensus.trigger.EventTrigger
import org.tessellation.node.shared.infrastructure.snapshot.{
  GlobalSnapshotAcceptanceManager,
  GlobalSnapshotStateChannelEventsProcessor,
  SnapshotConsensusFunctions
}
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.peer.PeerId
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncHasher
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Encoder
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotConsensusFunctionsSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def mkMockGossip[B](spreadRef: Ref[IO, List[B]]): Gossip[IO] =
    new Gossip[IO] {
      override def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] =
        spreadRef.update(rumorContent.asInstanceOf[B] :: _)

      override def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] =
        IO.raiseError(new Exception("spreadCommon: Unexpected call"))
    }

  def mkSignedArtifacts()(
    implicit sp: SecurityProvider[IO],
    h: Hasher[IO]
  ): IO[(Signed[GlobalSnapshotArtifact], Signed[GlobalSnapshot])] = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]

    genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
    signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)

    lastArtifact <- GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value)
    signedLastArtifact <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair)
  } yield (signedLastArtifact, signedGenesis)

  def sharedResource: Resource[IO, Res] = for {
    supervisor <- Supervisor[IO]
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar ++ dagL0KryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (supervisor, ks, j, h, sp)

  val bam: BlockAcceptanceManager[IO] = new BlockAcceptanceManager[IO] {

    override def acceptBlocksIteratively(
      blocks: List[Signed[Block]],
      context: BlockAcceptanceContext[IO],
      ordinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]): IO[BlockAcceptanceResult] =
      BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        List.empty,
        List.empty
      ).pure[IO]

    override def acceptBlock(
      block: Signed[Block],
      context: BlockAcceptanceContext[IO],
      ordinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] = ???

  }

  val scProcessor: GlobalSnapshotStateChannelEventsProcessor[IO] = new GlobalSnapshotStateChannelEventsProcessor[IO] {
    def process(
      ordinal: SnapshotOrdinal,
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent],
      validationType: StateChannelValidationType
    )(implicit hasher: Hasher[F]): IO[StateChannelAcceptanceResult] = IO(
      StateChannelAcceptanceResult(
        events.groupByNel(_.address).view.mapValues(_.map(_.snapshotBinary)).toSortedMap,
        SortedMap.empty,
        Set.empty,
        Map.empty
      )
    )

    def processCurrencySnapshots(
      snapshotOrdinal: SnapshotOrdinal,
      lastGlobalSnapshotInfo: GlobalSnapshotContext,
      events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]]
    )(implicit hasher: Hasher[F]): IO[
      SortedMap[Address, (NonEmptyList[(Signed[StateChannelSnapshotBinary], Option[CurrencySnapshotWithState])], Map[Address, Balance])]
    ] = ???
  }

  val collateral: Amount = Amount.empty

  val rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] =
    (_, _, _, _, _, _) => IO(SortedSet.empty)

  def mkGlobalSnapshotConsensusFunctions(
    implicit ks: KryoSerializer[IO],
    j: JsonSerializer[IO],
    sp: SecurityProvider[IO],
    h: Hasher[IO]
  ): GlobalSnapshotConsensusFunctions[IO] = {
    implicit val hs = HasherSelector.forSyncAlwaysCurrent(h)

    val snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[IO] =
      GlobalSnapshotAcceptanceManager.make[IO](bam, scProcessor, collateral)

    val feeCalculator = new SnapshotBinaryFeeCalculator[IO] {
      override def calculateFee(
        event: StateChannelEvent,
        info: GlobalSnapshotContext,
        ordinal: SnapshotOrdinal
      ): IO[NonNegLong] =
        event.snapshotBinary.value.fee.value.pure[IO]
    }
    GlobalSnapshotConsensusFunctions
      .make[IO](
        snapshotAcceptanceManager,
        collateral,
        rewards,
        GlobalSnapshotEventCutter.make[IO](20_000_000, feeCalculator)
      )
  }

  def getTestData(
    implicit sp: SecurityProvider[F],
    kryo: KryoSerializer[F],
    j: JsonSerializer[F],
    h: Hasher[IO]
  ): IO[(GlobalSnapshotConsensusFunctions[IO], Set[PeerId], Signed[GlobalSnapshotArtifact], Signed[GlobalSnapshot], StateChannelEvent)] =
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[F]

      gscf = mkGlobalSnapshotConsensusFunctions
      facilitators = Set.empty[PeerId]

      genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      signedGenesis <- Signed.forAsyncHasher[F, GlobalSnapshot](genesis, keyPair)

      lastArtifact <- GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value)
      signedLastArtifact <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair)

      scEvent <- mkStateChannelEvent()
    } yield (gscf, facilitators, signedLastArtifact, signedGenesis, scEvent)

  test("validateArtifact - returns artifact for correct data") { res =>
    implicit val (_, ks, j, h, sp) = res

    for {
      (gscf, facilitators, signedLastArtifact, signedGenesis, scEvent) <- getTestData

      (artifact, _, _) <- gscf.createProposalArtifact(
        SnapshotOrdinal.MinValue,
        signedLastArtifact,
        signedGenesis.value.info,
        h,
        EventTrigger,
        Set(scEvent.asLeft[DAGEvent]),
        facilitators
      )
      result <- gscf.validateArtifact(
        signedLastArtifact,
        signedGenesis.value.info,
        EventTrigger,
        artifact,
        facilitators
      )

      expected = Right(NonEmptyList.one(scEvent.snapshotBinary))
      actual = result.map(_._1.stateChannelSnapshots(scEvent.address))
      expectation = expect.same(true, result.isRight) && expect.same(expected, actual)
    } yield expectation
  }

  test("validateArtifact - returns invalid artifact error for incorrect data") { res =>
    implicit val (_, ks, j, h, sp) = res

    for {
      (gscf, facilitators, signedLastArtifact, signedGenesis, scEvent) <- getTestData

      (artifact, _, _) <- gscf.createProposalArtifact(
        SnapshotOrdinal.MinValue,
        signedLastArtifact,
        signedGenesis.value.info,
        h,
        EventTrigger,
        Set(scEvent.asLeft[DAGEvent]),
        facilitators
      )
      result <- gscf.validateArtifact(
        signedLastArtifact,
        signedGenesis.value.info,
        EventTrigger,
        artifact.copy(ordinal = artifact.ordinal.next),
        facilitators
      )
    } yield expect.same(true, result.isLeft)
  }

  test("gossip signed artifacts") { res =>
    implicit val (_, _, j, h, sp) = res

    for {
      gossiped <- Ref.of(List.empty[ForkInfo])
      mockGossip = mkMockGossip(gossiped)

      (signedLastArtifact, _) <- mkSignedArtifacts()

      _ <- SnapshotConsensusFunctions.gossipForkInfo(mockGossip, signedLastArtifact)

      expected <- h
        .hash(signedLastArtifact)
        .map { h =>
          List(ForkInfo(signedLastArtifact.value.ordinal, h))
        }
        .handleError(_ => List.empty)
      actual <- gossiped.get
    } yield expect.eql(expected, actual)
  }

  def mkStateChannelEvent()(implicit S: SecurityProvider[IO], H: Hasher[IO]): IO[StateChannelEvent] = for {
    keyPair <- KeyPairGenerator.makeKeyPair[IO]
    binary = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)
    signedSC <- forAsyncHasher(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
