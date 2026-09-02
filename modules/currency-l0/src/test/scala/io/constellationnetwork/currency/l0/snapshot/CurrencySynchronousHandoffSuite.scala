package io.constellationnetwork.currency.l0.snapshot

import cats.Eq
import cats.data.{Kleisli, NonEmptySet, StateT}
import cats.effect._
import cats.effect.std.Supervisor
import cats.effect.testkit.TestControl
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.currency.l0.snapshot.synchronous._
import io.constellationnetwork.currency.l0.snapshot.synchronous.message.{
  ConsensusWithdrawPeerDeclaration,
  GetConsensusOutcomeRequest,
  RegistrationResponse
}
import io.constellationnetwork.ext.cats.syntax.next._
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.{ClusterStorage => ClusterStorageImpl}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.node.shared.infrastructure.node.{NodeStorage => NodeStorageImpl}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer.{Peer, PeerId, Responsive}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{PosInt, PosLong}
import io.circe.Encoder
import monocle.Lens
import weaver.SimpleIOSuite

/** Effectful coverage for the private continuation handoff. Pure corroboration arithmetic is tested separately; this suite pins the
  * production manager's mutation boundary.
  */
object CurrencySynchronousHandoffSuite extends SimpleIOSuite {

  private final case class TestOutcome(
    key: SnapshotOrdinal,
    artifact: Signed[String],
    context: String,
    trigger: ConsensusTrigger,
    authority: Set[PeerId]
  )

  private implicit val outcomeEq: Eq[TestOutcome] = Eq.fromUniversalEquals
  private implicit val outcomeKey: Lens[TestOutcome, SnapshotOrdinal] =
    Lens[TestOutcome, SnapshotOrdinal](_.key)(key => _.copy(key = key))
  private implicit val outcomeArtifact: Lens[TestOutcome, Signed[String]] =
    Lens[TestOutcome, Signed[String]](_.artifact)(artifact => _.copy(artifact = artifact))
  private implicit val outcomeContext: Lens[TestOutcome, String] =
    Lens[TestOutcome, String](_.context)(context => _.copy(context = context))
  private implicit val outcomeTrigger: Lens[TestOutcome, ConsensusTrigger] =
    Lens[TestOutcome, ConsensusTrigger](_.trigger)(trigger => _.copy(trigger = trigger))
  private implicit val metrics: Metrics[IO] = NoOpMetrics.make

  private type Storage = ConsensusStorage[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String]

  private val config = ConsensusConfig(
    timeTriggerInterval = 1.hour,
    declarationTimeout = 1.hour,
    declarationRangeLimit = 3L,
    lockDuration = 1.hour,
    eventCutter = EventCutterConfig(PosInt(1024), PosInt(1024))
  )

  private val host: Host = Host.fromString("127.0.0.1").get
  private val generation = Generation(PosLong.unsafeFrom(1L))

  private def peerId(n: Int): PeerId = PeerId(Hex(f"$n%064x"))

  private def peer(n: Int): Peer =
    Peer(
      peerId(n),
      host,
      Port.fromInt(9000 + n).get,
      Port.fromInt(10000 + n).get,
      ClusterSessionToken(generation),
      SessionToken(generation),
      NodeState.Ready,
      Responsive,
      Hash.empty
    )

  private def signedArtifact(signers: List[PeerId]): Signed[String] = {
    val proofs = signers.zipWithIndex.map {
      case (id, index) =>
        SignatureProof(PeerId._Id.get(id), Signature(Hex(f"${index + 1}%064x")))
    }
    Signed("artifact", NonEmptySet.fromSetUnsafe(SortedSet.from(proofs)))
  }

  private val gossip: Gossip[IO] = new Gossip[IO] {
    def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
    def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = IO.unit
    def spreadDirect[A: TypeTag: Encoder](rumorContent: A, targets: Set[PeerId]): IO[Unit] = IO.unit
    def setDirectPushFn(fn: Gossip.DirectPushFn[IO]): IO[Unit] = IO.unit
  }

  private def stateCreator(storage: Storage): ConsensusStateCreator[IO, SnapshotOrdinal, String, String, String, TestOutcome, String] =
    new ConsensusStateCreator[IO, SnapshotOrdinal, String, String, String, TestOutcome, String] {
      def tryFacilitateConsensus(
        key: SnapshotOrdinal,
        lastOutcome: TestOutcome,
        maybeTrigger: Option[ConsensusTrigger],
        resources: ConsensusResources[String, String]
      ): IO[StateCreateResult] =
        storage.runRetainedEffect(key) >>
          storage
            .condModifyStateWithEffect(key) {
              case None =>
                Clock[IO].monotonic.map { createdAt =>
                  val state = ConsensusState(
                    key,
                    lastOutcome,
                    Facilitators(lastOutcome.authority.toList.sorted),
                    "collecting",
                    createdAt,
                    spreadAckKinds = Set.empty[String]
                  )
                  (state.some, state.some, IO.unit).some
                }
              case Some(_) =>
                none[(Option[ConsensusState[SnapshotOrdinal, String, TestOutcome, String]], StateCreateResult, IO[Unit])]
                  .pure[IO]
            }
            .map(_.flatten)
            .flatTap(_ => storage.runRetainedEffect(key))
    }

  private val stateUpdater: ConsensusStateUpdater[IO, SnapshotOrdinal, String, String, String, TestOutcome, String] =
    new ConsensusStateUpdater[IO, SnapshotOrdinal, String, String, String, TestOutcome, String] {
      def tryUpdateConsensus(
        key: SnapshotOrdinal,
        resources: ConsensusResources[String, String]
      ): IO[StateUpdateResult] =
        none[(ConsensusState[SnapshotOrdinal, String, TestOutcome, String], ConsensusState[SnapshotOrdinal, String, TestOutcome, String])]
          .pure[IO]

      def tryLockConsensus(
        key: SnapshotOrdinal,
        referenceState: ConsensusState[SnapshotOrdinal, String, TestOutcome, String]
      ): IO[StateUpdateResult] =
        none[(ConsensusState[SnapshotOrdinal, String, TestOutcome, String], ConsensusState[SnapshotOrdinal, String, TestOutcome, String])]
          .pure[IO]

      def trySpreadAck(
        key: SnapshotOrdinal,
        ackKind: String,
        resources: ConsensusResources[String, String]
      ): IO[StateUpdateResult] =
        none[(ConsensusState[SnapshotOrdinal, String, TestOutcome, String], ConsensusState[SnapshotOrdinal, String, TestOutcome, String])]
          .pure[IO]
    }

  private val stateAdvancer: ConsensusStateAdvancer[IO, SnapshotOrdinal, String, String, String, TestOutcome, String] =
    new ConsensusStateAdvancer[IO, SnapshotOrdinal, String, String, String, TestOutcome, String] {
      def getConsensusOutcome(
        state: ConsensusState[SnapshotOrdinal, String, TestOutcome, String]
      ): Option[(Previous[SnapshotOrdinal], TestOutcome)] = none

      def advanceStatus(
        resources: ConsensusResources[String, String]
      ): StateT[IO, ConsensusState[SnapshotOrdinal, String, TestOutcome, String], IO[Unit]] =
        StateT.inspect(_ => IO.unit)
    }

  private val consensusOps: ConsensusOps[String, String] = new ConsensusOps[String, String] {
    def collectedKinds(status: String): Set[String] = Set.empty
    def maybeCollectingKind(status: String): Option[String] = none
    def kindGetter: String => PeerDeclarations => Option[synchronous.declaration.PeerDeclaration] = _ => _ => none
  }

  private def client(responses: Map[PeerId, Option[TestOutcome]]): ConsensusClient[IO, SnapshotOrdinal, TestOutcome] =
    new ConsensusClient[IO, SnapshotOrdinal, TestOutcome] {
      def getRegistration = Kleisli(_ => RegistrationResponse[SnapshotOrdinal](none).pure[IO])
      def getLatestConsensusOutcome = Kleisli(_ => none[TestOutcome].pure[IO])
      def getSpecificConsensusOutcome(request: GetConsensusOutcomeRequest[SnapshotOrdinal]) =
        Kleisli(context => responses.getOrElse(context.id, none[TestOutcome]).pure[IO])
    }

  private final case class Harness(
    storage: Storage,
    nodeStorage: io.constellationnetwork.node.shared.domain.node.NodeStorage[IO],
    manager: ConsensusManager[IO, SnapshotOrdinal, String, String, String, TestOutcome, String]
  )

  private def makeHarness(
    responsive: List[Peer],
    responses: Map[PeerId, Option[TestOutcome]],
    validateObservedOutcome: (TestOutcome, SnapshotOrdinal, Signed[String], String) => IO[Boolean]
  )(
    implicit supervisor: Supervisor[IO]
  ): IO[Harness] =
    for {
      storage <- ConsensusStorage.make[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String](config)
      nodeStorage <- NodeStorageImpl.make[IO]
      _ <- nodeStorage.setNodeState(NodeState.Observing)
      clusterStorage <- ClusterStorageImpl.make[IO](
        ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"),
        responsive.map(p => p.id -> p).toMap
      )
      remover = new ConsensusStateRemover[IO, SnapshotOrdinal, Unit, String, String, String, TestOutcome, String](storage, gossip) {
        protected def getWithdrawalDeclaration(
          key: SnapshotOrdinal,
          maybeState: Option[ConsensusState[SnapshotOrdinal, String, TestOutcome, String]]
        ): ConsensusWithdrawPeerDeclaration[SnapshotOrdinal, String] = ConsensusWithdrawPeerDeclaration(key, "kind")
      }
      manager <- ConsensusManager.make[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String](
        peerId(99),
        config,
        storage,
        stateCreator(storage),
        stateUpdater,
        stateAdvancer,
        remover,
        consensusOps,
        nodeStorage,
        clusterStorage,
        client(responses),
        validateObservedOutcome,
        _.authority.contains(peerId(99)),
        _.authority,
        _ => IO.unit
      )
    } yield Harness(storage, nodeStorage, manager)

  private def waitForNodeState(
    nodeStorage: io.constellationnetwork.node.shared.domain.node.NodeStorage[IO],
    expected: NodeState,
    attemptsRemaining: Int = 60
  ): IO[Unit] =
    nodeStorage.getNodeState.flatMap { current =>
      if (current === expected) IO.unit
      else if (attemptsRemaining <= 0)
        new IllegalStateException(s"Node did not reach $expected; current=$current").raiseError[IO, Unit]
      else IO.sleep(1.second) >> waitForNodeState(nodeStorage, expected, attemptsRemaining - 1)
    }

  test("a legitimate four-artifact-signer to two-binary-signer handoff installs exactly once") {
    Supervisor[IO].use { implicit supervisor =>
      val proofSigners = (1 to 4).toList.map(peerId)
      val artifact = signedArtifact(proofSigners)
      val selected = TestOutcome(
        SnapshotOrdinal.unsafeApply(10L),
        artifact,
        "context",
        TimeTrigger,
        Set(peerId(1), peerId(2))
      )
      val responsive = List(peer(1), peer(2))

      for {
        harness <- makeHarness(
          responsive,
          Map(peerId(1) -> selected.some, peerId(2) -> selected.some),
          (outcome, key, publicArtifact, publicContext) =>
            (outcome === selected && key === selected.key && publicArtifact === artifact && publicContext === selected.context).pure[IO]
        )
        beforeCount <- Ref.of[IO, Int](0)
        afterCount <- Ref.of[IO, Int](0)
        installedSignal <- Deferred[IO, Unit]
        _ <- harness.manager.startFacilitatingAfterDownload(selected.key, artifact, selected.context)(
          _ => beforeCount.update(_ + 1),
          afterCount.update(_ + 1) >> installedSignal.complete(()).void
        )
        _ <- installedSignal.get.timeout(5.seconds)
        before <- beforeCount.get
        after <- afterCount.get
        installed <- harness.storage.getLastConsensusOutcome
        nextState <- harness.storage.getState(selected.key.next)
        nodeState <- harness.nodeStorage.getNodeState
      } yield
        expect.all(
          before === 1,
          after === 1,
          installed.contains(selected),
          nextState.exists(_.lastOutcome === selected),
          nodeState === NodeState.WaitingForReady
        )
    }
  }

  test("invalid private outcomes exhaust the bounded observation budget without mutating authority") {
    TestControl.executeEmbed {
      Supervisor[IO].use { implicit supervisor =>
        val proofSigners = (1 to 4).toList.map(peerId)
        val artifact = signedArtifact(proofSigners)
        val served = TestOutcome(
          SnapshotOrdinal.unsafeApply(10L),
          artifact,
          "context",
          TimeTrigger,
          Set(peerId(1), peerId(2))
        )

        for {
          harness <- makeHarness(
            List(peer(1), peer(2)),
            Map(peerId(1) -> served.some, peerId(2) -> served.some),
            (_, _, _, _) => false.pure[IO]
          )
          beforeCount <- Ref.of[IO, Int](0)
          afterCount <- Ref.of[IO, Int](0)
          _ <- harness.manager.startFacilitatingAfterDownload(served.key, artifact, served.context)(
            _ => beforeCount.update(_ + 1),
            afterCount.update(_ + 1)
          )
          _ <- waitForNodeState(harness.nodeStorage, NodeState.WaitingForDownload)
          before <- beforeCount.get
          after <- afterCount.get
          installed <- harness.storage.getLastConsensusOutcome
          nextState <- harness.storage.getState(served.key.next)
          nodeState <- harness.nodeStorage.getNodeState
        } yield
          expect.all(
            before === 0,
            after === 0,
            installed.isEmpty,
            nextState.isEmpty,
            nodeState === NodeState.WaitingForDownload
          )
      }
    }
  }
}
