package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptyList
import cats.data.Validated.Valid
import cats.effect.std.Supervisor
import cats.effect.{IO, Ref, Resource}
import cats.implicits.none
import cats.syntax.applicative._
import cats.syntax.list._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.dag.l0.dagL0KryoRegistrar
import io.constellationnetwork.dag.l0.domain.snapshot.programs.{
  GlobalSnapshotEventCutter,
  SnapshotBinaryFeeCalculator,
  UpdateNodeParametersCutter
}
import io.constellationnetwork.dag.l0.infrastructure.snapshot.event.{GlobalSnapshotEvent, StateChannelEvent}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.fork.ForkInfo
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.{UpdateNodeParametersAcceptanceManager, UpdateNodeParametersValidator}
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.node.shared.domain.swap.block._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot.{
  GlobalSnapshotAcceptanceManager,
  GlobalSnapshotStateChannelEventsProcessor,
  SnapshotConsensusFunctions
}
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.RewardFraction
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationErrorOr
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection._

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
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

  val asbam: AllowSpendBlockAcceptanceManager[IO] = new AllowSpendBlockAcceptanceManager[IO] {
    override def acceptBlock(
      block: Signed[swap.AllowSpendBlock],
      context: AllowSpendBlockAcceptanceContext[IO],
      snapshotOrdinal: SnapshotOrdinal
    )(implicit hasher: Hasher[IO]): IO[Either[AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate]] = ???

    override def acceptBlocksIteratively(
      blocks: List[Signed[swap.AllowSpendBlock]],
      context: AllowSpendBlockAcceptanceContext[IO],
      snapshotOrdinal: SnapshotOrdinal
    )(implicit hasher: Hasher[IO]): IO[AllowSpendBlockAcceptanceResult] =
      AllowSpendBlockAcceptanceResult(
        AllowSpendBlockAcceptanceContextUpdate.empty,
        List.empty,
        List.empty
      ).pure[IO]
  }

  val scProcessor: GlobalSnapshotStateChannelEventsProcessor[IO] = new GlobalSnapshotStateChannelEventsProcessor[IO] {
    def process(
      snapshotOrdinal: GlobalSnapshotKey,
      lastGlobalSnapshotInfo: GlobalSnapshotContext,
      events: List[StateChannelOutput],
      validationType: StateChannelValidationType,
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
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
      events: SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
    )(implicit hasher: Hasher[F]): IO[
      SortedMap[Address, (NonEmptyList[(Signed[StateChannelSnapshotBinary], Option[CurrencySnapshotWithState])], Map[Address, Balance])]
    ] = ???

  }

  def updateNodeParametersAcceptanceManager(implicit txHasher: Hasher[IO]) = {
    val signedValidator = new SignedValidator[IO] {
      override def validateSignatures[A: Encoder](signed: Signed[A])(implicit hasher: Hasher[IO]): IO[SignedValidationErrorOr[Signed[A]]] =
        IO.pure(Valid(signed))

      override def validateUniqueSigners[A: Encoder](signed: Signed[A]): SignedValidationErrorOr[Signed[A]] = ???

      override def validateMinSignatureCount[A: Encoder](signed: Signed[A], minSignatureCount: PosInt): SignedValidationErrorOr[Signed[A]] =
        ???

      override def validateMaxSignatureCount[A: Encoder](signed: Signed[A], maxSignatureCount: PosInt): SignedValidationErrorOr[Signed[A]] =
        ???

      override def isSignedBy[A: Encoder](signed: Signed[A], signerAddress: Address): IO[SignedValidationErrorOr[Signed[A]]] = ???

      override def isSignedExclusivelyBy[A: Encoder](signed: Signed[A], signerAddress: Address): IO[SignedValidationErrorOr[Signed[A]]] =
        ???

      override def validateSignaturesWithSeedlist[A <: AnyRef](
        seedlist: Option[Set[PeerId]],
        signed: Signed[A]
      ): SignedValidationErrorOr[Signed[A]] = ???

      override def validateSignedBySeedlistMajority[A](
        seedlist: Option[Set[PeerId]],
        signed: Signed[A]
      ): SignedValidationErrorOr[Signed[A]] = ???
    }
    val updateNodeParametersValidator =
      UpdateNodeParametersValidator.make(signedValidator, RewardFraction(5_000_000), RewardFraction(10_000_000))
    UpdateNodeParametersAcceptanceManager.make(updateNodeParametersValidator)
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

    val spendActionValidator = SpendActionValidator.make[IO]

    val snapshotAcceptanceManager: GlobalSnapshotAcceptanceManager[IO] =
      GlobalSnapshotAcceptanceManager
        .make[IO](bam, asbam, scProcessor, updateNodeParametersAcceptanceManager, spendActionValidator, collateral)

    val feeCalculator = new SnapshotBinaryFeeCalculator[IO] {
      override def calculateFee(
        event: StateChannelEvent,
        info: GlobalSnapshotContext,
        ordinal: SnapshotOrdinal
      ): IO[NonNegLong] =
        event.value.snapshotBinary.value.fee.value.pure[IO]
    }
    GlobalSnapshotConsensusFunctions
      .make[IO](
        snapshotAcceptanceManager,
        collateral,
        rewards,
        GlobalSnapshotEventCutter.make[IO](20_000_000, feeCalculator),
        UpdateNodeParametersCutter.make(100)
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
        Set(scEvent),
        facilitators,
        none
      )
      result <- gscf.validateArtifact(
        signedLastArtifact,
        signedGenesis.value.info,
        EventTrigger,
        artifact,
        facilitators,
        none
      )

      expected = Right(NonEmptyList.one(scEvent.value.snapshotBinary))
      actual = result.map(_._1.stateChannelSnapshots(scEvent.value.address))
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
        Set(scEvent),
        facilitators,
        none
      )
      result <- gscf.validateArtifact(
        signedLastArtifact,
        signedGenesis.value.info,
        EventTrigger,
        artifact.copy(ordinal = artifact.ordinal.next),
        facilitators,
        none
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
  } yield StateChannelEvent(StateChannelOutput(keyPair.getPublic.toAddress, signedSC))

}
