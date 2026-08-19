package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Eq
import cats.data.NonEmptySet
import cats.effect.kernel.Ref
import cats.effect.std.{Queue, Random, Supervisor}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.domain.node.{DownloadMode, NodeStorage}
import io.constellationnetwork.node.shared.infrastructure.consensus.engine._
import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.{ConsensusTrigger, TimeTrigger}
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.{NodeState, NodeStateTransition}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import fs2.Stream
import io.circe.Encoder
import monocle.Lens
import org.typelevel.log4cats.slf4j.Slf4jLogger
import weaver.SimpleIOSuite

object ConsensusFSMAttemptSafetySuite extends SimpleIOSuite {

  private final case class TestOutcome(
    key: SnapshotOrdinal,
    artifact: Signed[String],
    context: String,
    trigger: ConsensusTrigger
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

  private val inertHasher: Hasher[IO] = new Hasher[IO] {
    private val hash = Hash.fromBytes("fsm-attempt-safety".getBytes("UTF-8"))

    def hash[A: Encoder](data: A): IO[Hash] = hash.pure[IO]
    def hashBytes(bytes: Array[Byte]): IO[Hash] = hash.pure[IO]
    def compare[A: Encoder](data: A, expectedHash: Hash): IO[Boolean] = (expectedHash === hash).pure[IO]
    def getLogic(ordinal: SnapshotOrdinal): HashLogic = JsonHash
    def prefixedHash[A: Encoder](data: A, prefix: Array[Byte]): IO[Hash] = hash.pure[IO]
  }
  private implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(inertHasher)

  private val consensusConfig =
    ConsensusConfig(
      timeTriggerInterval = 10.seconds,
      declarationTimeout = 10.seconds,
      declarationRangeLimit = 100L,
      lockDuration = 10.seconds,
      eventCutter = EventCutterConfig(
        maxBinarySizeBytes = PosInt(1024),
        maxUpdateNodeParametersSize = PosInt(1024)
      )
    )

  private val self = PeerId(Hex("01" * 64))
  private val signedArtifact = Signed(
    "artifact",
    NonEmptySet.one(SignatureProof(Id(Hex("01")), Signature(Hex("00"))))
  )
  private val parentKey = SnapshotOrdinal.unsafeApply(7L)
  private val activeKey = SnapshotOrdinal.unsafeApply(8L)
  private val parentOutcome = TestOutcome(parentKey, signedArtifact, "context", TimeTrigger)

  private type Storage = ConsensusStorage[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String]
  private type Command = ConsensusCommand[SnapshotOrdinal, String, String, TestOutcome]
  private type Context = ConsensusEngineContext[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String]
  private type Runner = ConsensusRoundRunner[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String]
  private type FSM = ConsensusFSM[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String]

  private final case class Harness(
    fsm: FSM,
    storage: Storage,
    nodeState: Ref[IO, NodeState],
    running: Ref[IO, Boolean],
    queue: Queue[IO, Command],
    runCount: Ref[IO, Int],
    cleanupCount: Ref[IO, Int],
    finishCount: Ref[IO, Int],
    updateCount: Ref[IO, Int]
  )

  private def unused[A]: A = null.asInstanceOf[A]

  private def nodeStorage(state: Ref[IO, NodeState]): NodeStorage[IO] = new NodeStorage[IO] {
    def getNodeState: IO[NodeState] = state.get
    def setNodeState(nodeState: NodeState): IO[Unit] = state.set(nodeState)

    def tryModifyState[A](from: Set[NodeState], onStart: NodeState, onFinish: NodeState)(fn: => IO[A]): IO[A] =
      fn.flatTap(_ => state.set(onFinish))

    def tryModifyState(from: Set[NodeState], to: NodeState): IO[Unit] = state.set(to)

    def tryModifyStateGetResult(from: Set[NodeState], to: NodeState): IO[NodeStateTransition] =
      state.modify { current =>
        if (from.contains(current)) (to, NodeStateTransition.Success)
        else (current, NodeStateTransition.Failure)
      }

    def canJoinCluster: IO[Boolean] = true.pure[IO]
    def nodeStates: Stream[IO, NodeState] = Stream.eval(state.get)
    def setJoiningGracePeriod: IO[Unit] = IO.unit
    def clearJoiningGracePeriod: IO[Unit] = IO.unit
    def decrementJoiningGracePeriod: IO[Unit] = IO.unit
    def isInJoiningGracePeriod: IO[Boolean] = false.pure[IO]
    def setRecoveryDownload: IO[Unit] = IO.unit
    def clearRecoveryDownload: IO[Unit] = IO.unit
    def isRecoveryDownload: IO[Boolean] = false.pure[IO]
    def setFollowerCatchUpDownload: IO[Unit] = IO.unit
    def getDownloadMode: IO[DownloadMode] = DownloadMode.Full.pure[IO]
    def setValidatorMode: IO[Unit] = IO.unit
    def isValidatorMode: IO[Boolean] = false.pure[IO]
  }

  private def harness(initialNodeState: NodeState, initiallyRunning: Boolean): Resource[IO, Harness] =
    for {
      supervisor <- Supervisor[IO]
      random <- Resource.eval(Random.scalaUtilRandom[IO])
      queue <- Resource.eval(Queue.unbounded[IO, Command])
      pending <- Resource.eval(PendingTriggers.create[IO])
      firstRoundGate <- Resource.eval(FirstRoundStartGate.make[IO, SnapshotOrdinal](initiallyHeld = false))
      running <- Resource.eval(Ref.of[IO, Boolean](initiallyRunning))
      state <- Resource.eval(Ref.of[IO, NodeState](initialNodeState))
      storage <- Resource.eval(
        ConsensusStorage.make[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String](consensusConfig)
      )
      runCount <- Resource.eval(Ref.of[IO, Int](0))
      cleanupCount <- Resource.eval(Ref.of[IO, Int](0))
      finishCount <- Resource.eval(Ref.of[IO, Int](0))
      updateCount <- Resource.eval(Ref.of[IO, Int](0))
      roundFibers <- Resource.eval(Ref.of[IO, List[cats.effect.Fiber[IO, Throwable, Unit]]](Nil))
      cancelSignal <- Resource.eval(Ref.of[IO, Option[cats.effect.Deferred[IO, Unit]]](None))
      recovered <- Resource.eval(Ref.of[IO, Option[SnapshotOrdinal]](None))
      retriable <- Resource.eval(Ref.of[IO, (Option[SnapshotOrdinal], Int)]((None, 0)))
    } yield {
      implicit val randomIO: Random[IO] = random
      implicit val supervisorIO: Supervisor[IO] = supervisor

      val updater = new ConsensusStateUpdater[IO, SnapshotOrdinal, String, String, String, TestOutcome, String] {
        def tryUpdateConsensus(
          key: SnapshotOrdinal,
          resources: ConsensusResources[String, String]
        ): IO[StateUpdateResult] = updateCount.update(_ + 1).as(none)
      }

      val advancer = new ConsensusStateAdvancer[IO, SnapshotOrdinal, String, String, String, TestOutcome, String] {
        def getConsensusOutcome(
          state: ConsensusState[SnapshotOrdinal, String, TestOutcome, String]
        ): Option[(Previous[SnapshotOrdinal], TestOutcome)] = None

        def advanceStatus(
          resources: ConsensusResources[String, String]
        ): cats.data.StateT[IO, ConsensusState[SnapshotOrdinal, String, TestOutcome, String], IO[Unit]] =
          cats.data.StateT.pure[IO, ConsensusState[SnapshotOrdinal, String, TestOutcome, String], IO[Unit]](IO.unit)

        def synchronizeDownloadedOutcome(artifact: Signed[String], context: String): IO[Unit] = IO.unit
        def certifiedOutcomeAdoption(
          state: ConsensusState[SnapshotOrdinal, String, TestOutcome, String],
          candidate: TestOutcome
        ): IO[
          Either[
            String,
            CertifiedOutcomeAdoption[IO, ConsensusState[SnapshotOrdinal, String, TestOutcome, String]]
          ]
        ] = Left("certified_consensus_disabled_in_fixture").pure[IO]
        def afterConsensusOutcomeCommitted(outcome: TestOutcome): IO[Unit] = IO.unit
        protected def clusterStorage: ClusterStorage[IO] = unused[ClusterStorage[IO]]
        protected def config: ConsensusConfig = consensusConfig
      }

      val context: Context = ConsensusEngineContext[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String](
        selfId = self,
        queue = queue,
        isRoundRunning = running,
        pending = pending,
        firstRoundStartGate = firstRoundGate,
        plannedRecoveryCommittee = none.pure[IO],
        gossip = unused[Gossip[IO]],
        storage = storage,
        creator = unused[ConsensusStateCreator[IO, SnapshotOrdinal, String, String, String, TestOutcome, String]],
        updater = updater,
        advancer = advancer,
        remover = unused[ConsensusStateRemover[IO, SnapshotOrdinal, Unit, String, String, String, TestOutcome, String]],
        ops = unused[ConsensusOps[String, String]],
        nodeStorage = nodeStorage(state),
        clusterStorage = unused[ClusterStorage[IO]],
        logger = Slf4jLogger.getLogger[IO],
        config = consensusConfig,
        fns = unused[ConsensusFunctions[IO, Unit, SnapshotOrdinal, String, String]],
        consensusClient = unused[ConsensusClient[IO, SnapshotOrdinal, TestOutcome]],
        facilitatorSelector = unused[FacilitatorSelector],
        peerQualityTracker = unused[PeerQualityTracker[IO]],
        membershipPolicy = HealthDerivedMembershipPolicy.RetainSigningLeases,
        isInBootstrap = _ => false,
        lastSnapshotHashOf = _ => Hash.fromBytes("parent".getBytes("UTF-8")),
        probationPeersOf = _ => Set.empty,
        peerQualityOf = _ => Map.empty,
        lastOutcomeKeyOf = _.key,
        lastOutcomeEndTimeMsOf = _ => None,
        onOutcomeFinalized = _ => IO.unit,
        onOutcomeInitialized = _ => IO.unit,
        onOutcomePreInitialize = _ => IO.unit,
        onOutcomeSafetyInitialized = _ => IO.unit,
        onOutcomeRollbackInitialized = (_, _) => IO.unit,
        recoveredAtKeyRef = recovered,
        retriableAtSameKeyRef = retriable
      )

      val runner: Runner = new ConsensusRoundRunner[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String](
        context,
        unused[StallDetector[IO, Unit, SnapshotOrdinal, String, String, String, TestOutcome, String]],
        roundFibers,
        cancelSignal
      ) {
        override def cleanupRound: IO[Unit] = cleanupCount.update(_ + 1)
        override def runRound(trigger: Option[ConsensusTrigger]): IO[Unit] = runCount.update(_ + 1)
        override def afterConsensusFinish(majorityTrigger: ConsensusTrigger): IO[Unit] = finishCount.update(_ + 1)
      }

      Harness(
        new ConsensusFSM(context, runner, (_, _) => finishCount.update(_ + 1)),
        storage,
        state,
        running,
        queue,
        runCount,
        cleanupCount,
        finishCount,
        updateCount
      )
    }

  private def installState(
    storage: Storage,
    lastOutcome: TestOutcome = parentOutcome,
    key: SnapshotOrdinal = activeKey
  ): IO[Long] = {
    val state: ConsensusState[SnapshotOrdinal, String, TestOutcome, String] = ConsensusState(
      key = key,
      lastOutcome = lastOutcome,
      facilitators = Facilitators(List(self)),
      roundStartFacilitators = Facilitators(List(self)),
      status = "collecting",
      createdAt = 0.seconds,
      leader = self,
      entropy = Hash.fromBytes("entropy".getBytes("UTF-8"))
    )

    storage
      .condModifyState(key) {
        case None    => ((state.some, ())).some.pure[IO]
        case Some(_) => none.pure[IO]
      }
      .flatMap(_ => storage.getRoundAttemptId)
  }

  test("an attempt-stale soft-reset restart cannot mutate or complete a newer attempt even when its state is absent") {
    harness(NodeState.Ready, initiallyRunning = true).use { h =>
      for {
        _ <- h.storage.trySetInitialConsensusOutcome(parentOutcome)
        staleAttempt <- installState(h.storage)
        _ <- h.storage.condModifyState(activeKey) {
          case Some(_) => ((none[ConsensusState[SnapshotOrdinal, String, TestOutcome, String]], ())).some.pure[IO]
          case None    => none.pure[IO]
        }
        currentAttempt <- h.storage.getRoundAttemptId
        _ <- h.fsm.handle(ConsensusCommand.RestartAfterSoftReset(activeKey, staleAttempt))
        running <- h.running.get
        state <- h.storage.getState(activeKey)
        runs <- h.runCount.get
        cleanups <- h.cleanupCount.get
      } yield expect.all(currentAttempt > staleAttempt, running, state.isEmpty, runs == 0, cleanups == 0)
    }
  }

  List(NodeState.Observing, NodeState.WaitingForReady, NodeState.Ready).foreach { lifecycle =>
    test(s"a current soft-reset restart resumes consensus while lifecycle is $lifecycle") {
      harness(lifecycle, initiallyRunning = true).use { h =>
        for {
          _ <- h.storage.trySetInitialConsensusOutcome(parentOutcome)
          attempt <- h.storage.getRoundAttemptId
          _ <- h.fsm.handle(ConsensusCommand.RestartAfterSoftReset(activeKey, attempt))
          running <- h.running.get
          runs <- h.runCount.get
          cleanups <- h.cleanupCount.get
        } yield expect.all(running, runs == 1, cleanups == 1)
      }
    }
  }

  test("a current soft-reset restart releases Busy but does not start in WaitingForDownload") {
    harness(NodeState.WaitingForDownload, initiallyRunning = true).use { h =>
      for {
        _ <- h.storage.trySetInitialConsensusOutcome(parentOutcome)
        attempt <- h.storage.getRoundAttemptId
        _ <- h.fsm.handle(ConsensusCommand.RestartAfterSoftReset(activeKey, attempt))
        running <- h.running.get
        runs <- h.runCount.get
        cleanups <- h.cleanupCount.get
      } yield expect.all(!running, runs == 0, cleanups == 1)
    }
  }

  test("RoundCompleted releases only the exact current attempt") {
    harness(NodeState.Ready, initiallyRunning = true).use { h =>
      for {
        attempt <- installState(h.storage)
        _ <- h.fsm.handle(ConsensusCommand.RoundCompleted(attempt - 1L))
        runningAfterStale <- h.running.get
        cleanupsAfterStale <- h.cleanupCount.get
        _ <- h.fsm.handle(ConsensusCommand.RoundCompleted(attempt))
        runningAfterExact <- h.running.get
        cleanupsAfterExact <- h.cleanupCount.get
      } yield expect.all(runningAfterStale, cleanupsAfterStale == 0, !runningAfterExact, cleanupsAfterExact == 1)
    }
  }

  test("RetryCheckUpdate invokes the updater only for the exact current attempt") {
    harness(NodeState.Ready, initiallyRunning = true).use { h =>
      for {
        attempt <- installState(h.storage)
        _ <- h.fsm.handle(ConsensusCommand.RetryCheckUpdate(activeKey, attempt - 1L))
        updatesAfterStale <- h.updateCount.get
        _ <- h.fsm.handle(ConsensusCommand.RetryCheckUpdate(activeKey, attempt))
        updatesAfterExact <- h.updateCount.get
      } yield expect.all(updatesAfterStale == 0, updatesAfterExact == 1)
    }
  }

  test("ConsensusFinished releases only an exact attempt bound to the persisted outcome") {
    val committed = parentOutcome.copy(key = activeKey)
    val conflicting = committed.copy(context = "different-context")

    harness(NodeState.Ready, initiallyRunning = true).use { h =>
      for {
        _ <- h.storage.trySetInitialConsensusOutcome(committed)
        attempt <- installState(h.storage, committed)
        _ <- h.fsm.handle(ConsensusCommand.ConsensusFinished(activeKey, committed, TimeTrigger, attempt - 1L))
        runningAfterStaleAttempt <- h.running.get
        finishesAfterStaleAttempt <- h.finishCount.get
        _ <- h.fsm.handle(ConsensusCommand.ConsensusFinished(activeKey, conflicting, TimeTrigger, attempt))
        runningAfterConflictingOutcome <- h.running.get
        finishesAfterConflictingOutcome <- h.finishCount.get
        _ <- h.fsm.handle(ConsensusCommand.ConsensusFinished(activeKey, committed, TimeTrigger, attempt))
        runningAfterExact <- h.running.get
        finishesAfterExact <- h.finishCount.get
      } yield
        expect.all(
          runningAfterStaleAttempt,
          finishesAfterStaleAttempt == 0,
          runningAfterConflictingOutcome,
          finishesAfterConflictingOutcome == 0,
          !runningAfterExact,
          finishesAfterExact == 2
        )
    }
  }

  test("a delayed finished-key update cannot cancel the next active round") {
    val committed = parentOutcome.copy(key = activeKey)
    val nextKey = SnapshotOrdinal.unsafeApply(activeKey.value.value + 1L)

    harness(NodeState.Ready, initiallyRunning = true).use { h =>
      for {
        _ <- h.storage.trySetInitialConsensusOutcome(committed)
        oldAttempt <- installState(h.storage, committed, activeKey)
        nextAttempt <- installState(h.storage, committed, nextKey)
        _ <- h.fsm.handle(ConsensusCommand.CheckUpdate(activeKey))
        updatesAfterOldCheck <- h.updateCount.get
        // Model the exact historical bug: the old outcome is paired with the newer attempt.
        _ <- h.fsm.handle(ConsensusCommand.ConsensusFinished(activeKey, committed, TimeTrigger, nextAttempt))
        running <- h.running.get
        cleanups <- h.cleanupCount.get
        finishes <- h.finishCount.get
        oldStateAttempt <- h.storage.getStateAttemptId(activeKey)
        nextStateAttempt <- h.storage.getStateAttemptId(nextKey)
      } yield
        expect.all(
          nextAttempt > oldAttempt,
          oldStateAttempt.contains(oldAttempt),
          nextStateAttempt.contains(nextAttempt),
          updatesAfterOldCheck == 0,
          running,
          cleanups == 0,
          finishes == 0
        )
    }
  }

  test("Busy InitializeFromDownload keeps a legitimate same-lineage Observing round running") {
    harness(NodeState.Observing, initiallyRunning = true).use { h =>
      for {
        _ <- installState(h.storage)
        _ <- h.fsm.handle(ConsensusCommand.InitializeFromDownload(parentKey, signedArtifact, "context", isRecovery = true))
        running <- h.running.get
        state <- h.storage.getState(activeKey)
        cleanups <- h.cleanupCount.get
        immediatelyRequeued <- h.queue.tryTake
      } yield expect.all(running, state.nonEmpty, cleanups == 0, immediatelyRequeued.isEmpty)
    }
  }
}
