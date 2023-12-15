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
import org.tessellation.dag.l0.domain.snapshot.programs.GlobalSnapshotEventCutter
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.block.processing._
import org.tessellation.node.shared.domain.fork.ForkInfo
import org.tessellation.node.shared.domain.gossip.Gossip
import org.tessellation.node.shared.domain.rewards.Rewards
import org.tessellation.node.shared.domain.snapshot.storage.SnapshotStorage
import org.tessellation.node.shared.infrastructure.consensus.trigger.EventTrigger
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.snapshot.{GlobalSnapshotAcceptanceManager, GlobalSnapshotStateChannelEventsProcessor}
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Amount
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{Hasher, KeyPairGenerator, SecurityProvider}
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import org.tessellation.syntax.sortedCollection._

import eu.timepit.refined.auto._
import io.circe.Encoder
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotConsensusFunctionsSuite extends MutableIOSuite with Checkers {

  type Res = (Supervisor[IO], KryoSerializer[IO], Hasher[IO], SecurityProvider[IO], Metrics[IO])

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
    signedGenesis <- Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair)

    lastArtifact <- GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value)
    signedLastArtifact <- Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair)
  } yield (signedLastArtifact, signedGenesis)

  def sharedResource: Resource[IO, Res] = for {
    supervisor <- Supervisor[IO]
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar ++ dagL0KryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonHashSerializer[IO]) <- JsonHashSerializer.forSync[IO].asResource
    h = Hasher.forSync[IO]
    metrics <- Metrics.forAsync[IO](Seq.empty)
  } yield (supervisor, ks, h, sp, metrics)

  val gss: SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {

      override def prepend(snapshot: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Boolean] = ???

      override def head: IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???

      override def headSnapshot: IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def get(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???

      override def getHash(ordinal: SnapshotOrdinal): F[Option[Hash]] = ???

    }

  val bam: BlockAcceptanceManager[IO] = new BlockAcceptanceManager[IO] {

    override def acceptBlocksIteratively(
      blocks: List[Signed[Block]],
      context: BlockAcceptanceContext[IO]
    ): IO[BlockAcceptanceResult] =
      BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        List.empty,
        List.empty
      ).pure[IO]

    override def acceptBlock(
      block: Signed[Block],
      context: BlockAcceptanceContext[IO]
    ): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] = ???

  }

  val scProcessor: GlobalSnapshotStateChannelEventsProcessor[IO] = new GlobalSnapshotStateChannelEventsProcessor[IO] {
    def process(
      ordinal: SnapshotOrdinal,
      lastGlobalSnapshotInfo: GlobalSnapshotInfo,
      events: List[StateChannelEvent],
      validationType: StateChannelValidationType
    ): IO[
      (
        SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
        SortedMap[Address, CurrencySnapshotWithState],
        Set[StateChannelEvent]
      )
    ] = IO(
      (events.groupByNel(_.address).view.mapValues(_.map(_.snapshotBinary)).toSortedMap, SortedMap.empty, Set.empty)
    )

    def processCurrencySnapshots(
      lastGlobalSnapshotInfo: GlobalSnapshotContext,
      events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]]
    ): IO[SortedMap[Address, NonEmptyList[CurrencySnapshotWithState]]] = ???
  }

  val collateral: Amount = Amount.empty

  val rewards: Rewards[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotEvent] =
    (_, _, _, _, _, _) => IO(SortedSet.empty)

  def mkGlobalSnapshotConsensusFunctions(gossip: Gossip[F])(
    implicit ks: KryoSerializer[IO],
    sp: SecurityProvider[IO],
    h: Hasher[IO],
    m: Metrics[IO]
  ): GlobalSnapshotConsensusFunctions[IO] = {
    val snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[IO] =
      GlobalSnapshotAcceptanceManager.make[IO](bam, scProcessor, collateral)

    GlobalSnapshotConsensusFunctions
      .make[IO](
        gss,
        snapshotAcceptanceManager,
        collateral,
        rewards,
        gossip,
        GlobalSnapshotEventCutter.make[IO](20_000_000)
      )
  }

  def getTestData(
    implicit sp: SecurityProvider[F],
    kryo: KryoSerializer[F],
    h: Hasher[IO],
    m: Metrics[F]
  ): IO[(GlobalSnapshotConsensusFunctions[IO], Set[PeerId], Signed[GlobalSnapshotArtifact], Signed[GlobalSnapshot], StateChannelEvent)] =
    for {
      gossip <- Ref.of(List.empty[ForkInfo]).map(mkMockGossip)
      gscf = mkGlobalSnapshotConsensusFunctions(gossip)
      facilitators = Set.empty[PeerId]

      keyPair <- KeyPairGenerator.makeKeyPair[F]

      genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      signedGenesis <- Signed.forAsyncKryo[F, GlobalSnapshot](genesis, keyPair)

      lastArtifact <- GlobalIncrementalSnapshot.fromGlobalSnapshot(signedGenesis.value)
      signedLastArtifact <- Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](lastArtifact, keyPair)

      scEvent <- mkStateChannelEvent()
    } yield (gscf, facilitators, signedLastArtifact, signedGenesis, scEvent)

  test("validateArtifact - returns artifact for correct data") { res =>
    implicit val (_, ks, h, sp, m) = res

    for {
      (gscf, facilitators, signedLastArtifact, signedGenesis, scEvent) <- getTestData

      (artifact, _, _) <- gscf.createProposalArtifact(
        SnapshotOrdinal.MinValue,
        signedLastArtifact,
        signedGenesis.value.info,
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
    implicit val (_, ks, h, sp, m) = res

    for {
      (gscf, facilitators, signedLastArtifact, signedGenesis, scEvent) <- getTestData

      (artifact, _, _) <- gscf.createProposalArtifact(
        SnapshotOrdinal.MinValue,
        signedLastArtifact,
        signedGenesis.value.info,
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
    implicit val (_, ks, h, sp, m) = res

    for {
      gossiped <- Ref.of(List.empty[ForkInfo])
      mockGossip = mkMockGossip(gossiped)

      gscf = mkGlobalSnapshotConsensusFunctions(mockGossip)
      (signedLastArtifact, _) <- mkSignedArtifacts()

      _ <- gscf.gossipForkInfo(mockGossip, signedLastArtifact)

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
    signedSC <- forAsyncKryo(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

}
