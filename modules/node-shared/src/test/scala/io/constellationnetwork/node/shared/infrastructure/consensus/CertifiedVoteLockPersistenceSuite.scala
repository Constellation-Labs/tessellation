package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Eq
import cats.data.NonEmptySet
import cats.effect._
import cats.effect.kernel.Outcome
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.{ConsensusConfig, EventCutterConfig}
import io.constellationnetwork.node.shared.domain.gossip.Gossip
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.ViewChangeVote
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import fs2.Stream
import fs2.io.file.{Files => Fs2Files, Path}
import io.circe.Encoder
import monocle.Lens
import weaver.MutableIOSuite

object CertifiedVoteLockPersistenceSuite extends MutableIOSuite {

  override type Res = (Path, JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      base <- Fs2Files[IO].tempDirectory(None, "certified-vote-lock-test-", None)
      serializer <- Resource.eval(JsonSerializer.forAsync[IO])
      provider <- SecurityProvider.forAsync[IO]
      implicit0(json: JsonSerializer[IO]) = serializer
      hasher = Hasher.forJson[IO]
    } yield (base, serializer, hasher, provider)

  final case class TestOutcome(key: SnapshotOrdinal)
  private implicit val testOutcomeEq: Eq[TestOutcome] = Eq.fromUniversalEquals
  private implicit val outcomeKeyLens: Lens[TestOutcome, SnapshotOrdinal] =
    Lens[TestOutcome, SnapshotOrdinal](_.key)(key => _.copy(key = key))

  private type TestStorage = ConsensusStorage[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String]

  private val config = ConsensusConfig(
    timeTriggerInterval = 10.seconds,
    declarationTimeout = 10.seconds,
    declarationRangeLimit = 100L,
    lockDuration = 10.seconds,
    eventCutter = EventCutterConfig(
      maxBinarySizeBytes = PosInt(1024),
      maxUpdateNodeParametersSize = PosInt(1024)
    )
  )

  private val key10 = SnapshotOrdinal.unsafeApply(10L)
  private val key11 = SnapshotOrdinal.unsafeApply(11L)
  private val hashA = Hash.fromBytes("value-a".getBytes("UTF-8"))
  private val hashB = Hash.fromBytes("value-b".getBytes("UTF-8"))
  private val fraction = 2.0 / 3.0

  private def storage(persistence: CertifiedVoteLockPersistence[IO, SnapshotOrdinal]): IO[TestStorage] =
    ConsensusStorage.make[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String](config, persistence)

  private def nonEmptyPeers(peers: Iterable[PeerId]): NonEmptySet[PeerId] =
    NonEmptySet.fromSetUnsafe(SortedSet.from(peers))

  private def singleMemberValue(pair: java.security.KeyPair, suffix: String)(implicit hasher: Hasher[IO]): IO[(ProposalValue, PeerId)] = {
    val selfId = PeerId.fromId(pair.getPublic.toId)
    val committee = nonEmptyPeers(List(selfId))

    Hasher[IO].hash(committee).map { committeeHash =>
      ProposalValue(
        schemaVersion = SchemaVersion,
        domain = ConsensusDomain.DagL0,
        networkId = "integrationnet",
        key = key10.value.value,
        parentArtifactHash = Hash.fromBytes(s"parent-$suffix".getBytes("UTF-8")),
        artifactHash = Hash.fromBytes(s"artifact-$suffix".getBytes("UTF-8")),
        contextHash = Hash.fromBytes(s"context-$suffix".getBytes("UTF-8")),
        roundStartFacilitators = committee,
        roundStartFacilitatorsHash = committeeHash,
        roundStartCore = committee,
        roundStartCoreHash = committeeHash,
        committedView = 0L,
        trigger = EventTrigger,
        admissionNominee = none,
        admittedPeers = SortedSet.empty,
        evictedPeers = SortedSet.empty,
        observedResponders = SortedSet(selfId),
        observedSelfHealth = SortedMap.empty,
        timeoutVoters = SortedSet.empty,
        consensusEndTime = none
      ) -> selfId
    }
  }

  private def certifiedQc(
    implicit hasher: Hasher[IO],
    provider: SecurityProvider[IO]
  ): IO[(CertifiedProposalQC, Set[PeerId], List[java.security.KeyPair])] =
    for {
      pairs <- List.fill(4)(KeyPairGenerator.makeKeyPair[IO]).sequence
      ids = pairs.map(pair => PeerId.fromId(pair.getPublic.toId))
      committee = nonEmptyPeers(ids)
      committeeHash <- Hasher[IO].hash(committee)
      value = ProposalValue(
        schemaVersion = SchemaVersion,
        domain = ConsensusDomain.DagL0,
        networkId = "integrationnet",
        key = key10.value.value,
        parentArtifactHash = Hash.fromBytes("parent".getBytes("UTF-8")),
        artifactHash = Hash.fromBytes("artifact".getBytes("UTF-8")),
        contextHash = Hash.fromBytes("context".getBytes("UTF-8")),
        roundStartFacilitators = committee,
        roundStartFacilitatorsHash = committeeHash,
        roundStartCore = committee,
        roundStartCoreHash = committeeHash,
        committedView = 0L,
        trigger = EventTrigger,
        admissionNominee = none,
        admittedPeers = SortedSet.empty,
        evictedPeers = SortedSet.empty,
        observedResponders = SortedSet.from(ids),
        observedSelfHealth = SortedMap.empty,
        timeoutVoters = SortedSet.empty,
        consensusEndTime = none
      )
      voters = pairs.take(3)
      votes <- voters.traverse(signOutcomeVote[IO](value, _).map(_._2))
      qc <- buildProposalQc[IO](
        value,
        SortedMap.from(voters.map(pair => PeerId.fromId(pair.getPublic.toId)).zip(votes)),
        ids.toSet,
        ids.toSet,
        fraction
      ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
    } yield (qc, ids.toSet, pairs)

  test("atomic journal round-trips across a fresh storage instance") {
    case (base, serializer, _, _) =>
      implicit val json: JsonSerializer[IO] = serializer
      val path = base / "roundtrip"
      val lock = CertifiedVoteLock(Some(2L), Some(hashA), None)

      for {
        first <- CertifiedVoteLockPersistence.forSnapshotOrdinal[IO](path)
        _ <- first.write(key10, lock)
        restarted <- CertifiedVoteLockPersistence.forSnapshotOrdinal[IO](path)
        restored <- restarted.read(key10)
        tempFiles <- Fs2Files[IO].list(path).map(_.fileName.toString).filter(_.endsWith(".tmp")).compile.toList
      } yield expect.all(restored.contains(lock), tempFiles.isEmpty)
  }

  test("missing is None but corrupt or truncated journal bytes fail closed") {
    case (base, serializer, _, _) =>
      implicit val json: JsonSerializer[IO] = serializer
      val path = base / "corrupt"

      for {
        journal <- CertifiedVoteLockPersistence.forSnapshotOrdinal[IO](path)
        missing <- journal.read(key10)
        _ <- Stream.emits(Array[Byte](1, 2, 3, 4)).through(Fs2Files[IO].writeAll(path / key10.value.value.toString)).compile.drain
        corrupt <- journal.read(key10).attempt
      } yield expect.all(missing.isEmpty, corrupt.isLeft)
  }

  test("an identical clean lock is not forced to disk again on prepare re-entry") {
    case (_, _, _, _) =>
      for {
        writes <- Ref.of[IO, Int](0)
        persisted <- Ref.of[IO, Option[CertifiedVoteLock]](None)
        persistence = new CertifiedVoteLockPersistence[IO, SnapshotOrdinal] {
          def read(key: SnapshotOrdinal): IO[Option[CertifiedVoteLock]] = persisted.get
          def write(key: SnapshotOrdinal, lock: CertifiedVoteLock): IO[Unit] = writes.update(_ + 1) >> persisted.set(lock.some)
          def delete(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAtOrBelow(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAbove(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
        }
        consensus <- storage(persistence)
        first <- consensus.tryLockCertifiedVote(key10, 0L, hashA, None)
        second <- consensus.tryLockCertifiedVote(key10, 0L, hashA, None)
        writeCount <- writes.get
      } yield expect.all(first.isRight, second.isRight, writeCount === 1)
  }

  test("a journal write failure prevents the caller from reaching vote emission") {
    case (_, _, hasher, provider) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = provider
      val failure = new CertifiedVoteLockPersistence[IO, SnapshotOrdinal] {
        def read(key: SnapshotOrdinal): IO[Option[CertifiedVoteLock]] = IO.pure(None)
        def write(key: SnapshotOrdinal, lock: CertifiedVoteLock): IO[Unit] = IO.raiseError(new RuntimeException("disk failed"))
        def delete(key: SnapshotOrdinal): IO[Unit] = IO.unit
        def deleteAtOrBelow(key: SnapshotOrdinal): IO[Unit] = IO.unit
        def deleteAbove(key: SnapshotOrdinal): IO[Unit] = IO.unit
      }

      for {
        emitted <- Ref.of[IO, Boolean](false)
        consensus <- storage(failure)
        pair <- KeyPairGenerator.makeKeyPair[IO]
        built <- singleMemberValue(pair, "write-failure")
        (value, selfId) = built
        resources <- ConsensusResources.empty[IO, String, String]
        gossip = new Gossip[IO] {
          def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
          def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
          def spreadDirect[A: TypeTag: Encoder](rumorContent: A, targets: Set[PeerId]): IO[Unit] = emitted.set(true)
          def setDirectPushFn(fn: Gossip.DirectPushFn[IO]): IO[Unit] = IO.unit
        }
        result <- CertifiedConsensusRound
          .prepare[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String](
            key10,
            value,
            None,
            resources,
            Set(selfId),
            Set(selfId),
            fraction,
            selfId,
            pair,
            consensus,
            gossip
          )
          .attempt
        didEmit <- emitted.get
        storedVotes <- consensus.getResources(key10).map(_.outcomeVotes)
        dirtyRead <- consensus.getCertifiedVoteLock(key10).attempt
      } yield
        expect.all(
          result.isLeft,
          !didEmit,
          storedVotes.isEmpty,
          dirtyRead.isLeft
        )
  }

  test("a QC journal failure prevents vote transport and commit progression") {
    case (_, _, hasher, provider) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = provider

      for {
        writes <- Ref.of[IO, Int](0)
        persisted <- Ref.of[IO, Option[CertifiedVoteLock]](None)
        persistence = new CertifiedVoteLockPersistence[IO, SnapshotOrdinal] {
          def read(key: SnapshotOrdinal): IO[Option[CertifiedVoteLock]] = persisted.get
          def write(key: SnapshotOrdinal, lock: CertifiedVoteLock): IO[Unit] =
            writes.getAndUpdate(_ + 1).flatMap {
              case 0 => persisted.set(lock.some)
              case _ => IO.raiseError(new RuntimeException("QC disk write failed"))
            }
          def delete(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAtOrBelow(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAbove(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
        }
        emitted <- Ref.of[IO, Boolean](false)
        consensus <- storage(persistence)
        pair <- KeyPairGenerator.makeKeyPair[IO]
        built <- singleMemberValue(pair, "qc-write-failure")
        (value, selfId) = built
        resources <- ConsensusResources.empty[IO, String, String]
        gossip = new Gossip[IO] {
          def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
          def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
          def spreadDirect[A: TypeTag: Encoder](rumorContent: A, targets: Set[PeerId]): IO[Unit] = emitted.set(true)
          def setDirectPushFn(fn: Gossip.DirectPushFn[IO]): IO[Unit] = IO.unit
        }
        result <- CertifiedConsensusRound
          .prepare[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String](
            key10,
            value,
            None,
            resources,
            Set(selfId),
            Set(selfId),
            fraction,
            selfId,
            pair,
            consensus,
            gossip
          )
          .attempt
        didEmit <- emitted.get
        dirtyRead <- consensus.getCertifiedVoteLock(key10).attempt
        writeCount <- writes.get
      } yield
        expect.all(
          result.isLeft,
          !didEmit,
          writeCount === 3,
          dirtyRead.isLeft
        )
  }

  test("cancellation during persistence cannot expose a non-durable lock or emit a vote") {
    case (_, _, hasher, provider) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = provider

      for {
        writeStarted <- Deferred[IO, Unit]
        writeCanceled <- Deferred[IO, Unit]
        attempts <- Ref.of[IO, Int](0)
        persisted <- Ref.of[IO, Option[CertifiedVoteLock]](None)
        emitted <- Ref.of[IO, Boolean](false)
        persistence = new CertifiedVoteLockPersistence[IO, SnapshotOrdinal] {
          def read(key: SnapshotOrdinal): IO[Option[CertifiedVoteLock]] = persisted.get
          def write(key: SnapshotOrdinal, lock: CertifiedVoteLock): IO[Unit] =
            attempts.getAndUpdate(_ + 1).flatMap {
              case 0 =>
                writeStarted.complete(()).void >> IO.never[Unit].onCancel(writeCanceled.complete(()).void)
              case _ => persisted.set(lock.some)
            }
          def delete(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAtOrBelow(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAbove(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
        }
        consensus <- storage(persistence)
        pair <- KeyPairGenerator.makeKeyPair[IO]
        built <- singleMemberValue(pair, "cancelled-write")
        (value, selfId) = built
        resources <- ConsensusResources.empty[IO, String, String]
        gossip = new Gossip[IO] {
          def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
          def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
          def spreadDirect[A: TypeTag: Encoder](rumorContent: A, targets: Set[PeerId]): IO[Unit] = emitted.set(true)
          def setDirectPushFn(fn: Gossip.DirectPushFn[IO]): IO[Unit] = IO.unit
        }
        fiber <- CertifiedConsensusRound
          .prepare[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String](
            key10,
            value,
            None,
            resources,
            Set(selfId),
            Set(selfId),
            fraction,
            selfId,
            pair,
            consensus,
            gossip
          )
          .start
        _ <- writeStarted.get
        cancellation <- fiber.cancel.start
        _ <- writeCanceled.get
        _ <- cancellation.join
        outcome <- fiber.join
        votesWhileBlocked <- consensus.getResources(key10).map(_.outcomeVotes)
        emittedWhileBlocked <- emitted.get
        diskWhileBlocked <- persisted.get
        lockAfterCancellation <- consensus.getCertifiedVoteLock(key10)
        diskAfterCancellation <- persisted.get
        writeAttempts <- attempts.get
        votesAfterCancellation <- consensus.getResources(key10).map(_.outcomeVotes)
        emittedAfterCancellation <- emitted.get
        wasCanceled = outcome match {
          case Outcome.Canceled() => true
          case _                  => false
        }
      } yield
        expect.all(
          votesWhileBlocked.isEmpty,
          !emittedWhileBlocked,
          diskWhileBlocked.isEmpty,
          wasCanceled,
          writeAttempts === 2,
          lockAfterCancellation === diskAfterCancellation,
          diskAfterCancellation.isDefined,
          votesAfterCancellation.isEmpty,
          !emittedAfterCancellation
        )
  }

  test("dirty reads retry persistence and fail closed until the stricter lock is durable") {
    case (_, _, _, _) =>
      for {
        attempts <- Ref.of[IO, Int](0)
        persisted <- Ref.of[IO, Option[CertifiedVoteLock]](None)
        persistence = new CertifiedVoteLockPersistence[IO, SnapshotOrdinal] {
          def read(key: SnapshotOrdinal): IO[Option[CertifiedVoteLock]] = persisted.get
          def write(key: SnapshotOrdinal, lock: CertifiedVoteLock): IO[Unit] =
            attempts.getAndUpdate(_ + 1).flatMap {
              case 0 | 1 => IO.raiseError(new RuntimeException("journal unavailable"))
              case _     => persisted.set(lock.some)
            }
          def delete(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAtOrBelow(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAbove(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
        }
        consensus <- storage(persistence)
        initial <- consensus.tryLockCertifiedVote(key10, 0L, hashA, None).attempt
        firstRead <- consensus.getCertifiedVoteLock(key10).attempt
        durableBeforeRetry <- persisted.get
        secondRead <- consensus.getCertifiedVoteLock(key10)
        durableAfterRetry <- persisted.get
        count <- attempts.get
      } yield
        expect.all(
          initial.isLeft,
          firstRead.isLeft,
          durableBeforeRetry.isEmpty,
          secondRead.exists(_.votedValueHashAtHighestView.contains(hashA)),
          secondRead === durableAfterRetry,
          count === 3
        )
  }

  test("verified carried QC persists before progress even when local vote emission is disabled") {
    case (_, _, hasher, provider) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = provider

      for {
        built <- certifiedQc
        (qc, committee, corePairs) = built
        corePair = corePairs.head
        persisted <- Ref.of[IO, Option[CertifiedVoteLock]](None)
        writes <- Ref.of[IO, Int](0)
        emitted <- Ref.of[IO, Boolean](false)
        persistence = new CertifiedVoteLockPersistence[IO, SnapshotOrdinal] {
          def read(key: SnapshotOrdinal): IO[Option[CertifiedVoteLock]] = persisted.get
          def write(key: SnapshotOrdinal, lock: CertifiedVoteLock): IO[Unit] = writes.update(_ + 1) >> persisted.set(lock.some)
          def delete(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAtOrBelow(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
          def deleteAbove(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
        }
        consensus <- storage(persistence)
        resources <- ConsensusResources.empty[IO, String, String]
        selfId = PeerId.fromId(corePair.getPublic.toId)
        gossip = new Gossip[IO] {
          def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
          def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
          def spreadDirect[A: TypeTag: Encoder](rumorContent: A, targets: Set[PeerId]): IO[Unit] = emitted.set(true)
          def setDirectPushFn(fn: Gossip.DirectPushFn[IO]): IO[Unit] = IO.unit
        }
        result <- CertifiedConsensusRound.prepare[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String](
          key10,
          qc.value,
          qc.some,
          resources,
          committee,
          committee,
          fraction,
          selfId,
          corePair,
          consensus,
          gossip,
          allowVoteEmission = false
        )
        durable <- persisted.get
        storedVotes <- consensus.getResources(key10).map(_.outcomeVotes)
        didEmit <- emitted.get
        writeCount <- writes.get
      } yield
        expect.all(
          result.exists(progress => progress.proposalQc.contains(qc) && !progress.voteEmitted),
          durable.flatMap(_.lockedQc).contains(qc),
          storedVotes.isEmpty,
          !didEmit,
          writeCount === 1
        )
  }

  test("QC-driven progress cannot bypass a conflicting durable lock for abstaining Core or non-Core recipients") {
    case (_, _, hasher, provider) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = provider

      for {
        built <- certifiedQc
        (lockedQc, committee, corePairs) = built
        conflictingValue = lockedQc.value.copy(artifactHash = hashB)
        conflictingVotes <- corePairs.take(3).traverse(signOutcomeVote[IO](conflictingValue, _).map(_._2))
        conflictingQc <- buildProposalQc[IO](
          conflictingValue,
          SortedMap.from(
            corePairs
              .take(3)
              .map(pair => PeerId.fromId(pair.getPublic.toId))
              .zip(conflictingVotes)
          ),
          committee,
          committee,
          fraction
        ).flatMap(result => IO.fromEither(result.leftMap(new IllegalStateException(_))))
        outsider <- KeyPairGenerator.makeKeyPair[IO]
        exercise = (selfPair: java.security.KeyPair) =>
          for {
            persisted <- Ref.of[IO, Option[CertifiedVoteLock]](None)
            persistence = new CertifiedVoteLockPersistence[IO, SnapshotOrdinal] {
              def read(key: SnapshotOrdinal): IO[Option[CertifiedVoteLock]] = persisted.get
              def write(key: SnapshotOrdinal, lock: CertifiedVoteLock): IO[Unit] = persisted.set(lock.some)
              def delete(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
              def deleteAtOrBelow(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
              def deleteAbove(key: SnapshotOrdinal): IO[Unit] = persisted.set(None)
            }
            consensus <- storage(persistence)
            _ <- consensus.advanceCertifiedLockedQc(key10, lockedQc)
            resources <- ConsensusResources.empty[IO, String, String]
            selfId = PeerId.fromId(selfPair.getPublic.toId)
            emitted <- Ref.of[IO, Boolean](false)
            gossip = new Gossip[IO] {
              def spread[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
              def spreadCommon[A: TypeTag: Encoder](rumorContent: A): IO[Unit] = emitted.set(true)
              def spreadDirect[A: TypeTag: Encoder](rumorContent: A, targets: Set[PeerId]): IO[Unit] = emitted.set(true)
              def setDirectPushFn(fn: Gossip.DirectPushFn[IO]): IO[Unit] = IO.unit
            }
            result <- CertifiedConsensusRound.prepare[IO, String, SnapshotOrdinal, String, Unit, String, TestOutcome, String](
              key10,
              conflictingValue,
              conflictingQc.some,
              resources,
              committee,
              committee,
              fraction,
              selfId,
              selfPair,
              consensus,
              gossip,
              allowVoteEmission = false
            )
            durable <- consensus.getCertifiedVoteLock(key10)
            didEmit <- emitted.get
            storedVotes <- consensus.getResources(key10).map(_.outcomeVotes)
          } yield
            (
              result.left.exists(_.code === "locked_on_qc"),
              durable.flatMap(_.lockedQc).contains(lockedQc),
              storedVotes.isEmpty,
              !didEmit
            )
        coreResult <- exercise(corePairs.head)
        nonCoreResult <- exercise(outsider)
      } yield
        expect.all(
          coreResult._1,
          coreResult._2,
          coreResult._3,
          coreResult._4,
          nonCoreResult._1,
          nonCoreResult._2,
          nonCoreResult._3,
          nonCoreResult._4
        )
  }

  test("restart restores the QC lock, refuses a conflicting later-view vote, and offers only a verified QC") {
    case (base, serializer, hasher, provider) =>
      implicit val json: JsonSerializer[IO] = serializer
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = provider
      val path = base / "qc-restart"

      for {
        built <- certifiedQc
        (qc, committee, _) = built
        journal1 <- CertifiedVoteLockPersistence.forSnapshotOrdinal[IO](path)
        mismatchedKeyWrite <- journal1.write(key11, CertifiedVoteLock(None, None, qc.some)).attempt
        first <- storage(journal1)
        _ <- first.tryLockCertifiedVote(key10, 0L, qc.valueHash, None)
        _ <- first.advanceCertifiedLockedQc(key10, qc)
        journal2 <- CertifiedVoteLockPersistence.forSnapshotOrdinal[IO](path)
        restarted <- storage(journal2)
        restored <- restarted.getCertifiedVoteLock(key10)
        offered <- verifyPersistedLockedQc[IO](restored, committee, committee, fraction)
        vcv = ViewChangeVote(
          fromView = 0L,
          toView = 1L,
          facilitatorsHash = qc.value.roundStartFacilitatorsHash,
          lastSnapshotHash = qc.value.parentArtifactHash,
          highestKnownQc = none,
          highestKnownCertifiedQc = offered.toOption.flatten
        )
        conflicting <- restarted.tryLockCertifiedVote(key10, 1L, hashB, None)
      } yield
        expect.all(
          mismatchedKeyWrite.isLeft,
          restored.flatMap(_.lockedQc).contains(qc),
          offered === Right(Some(qc)),
          vcv.highestKnownCertifiedQc.contains(qc),
          conflicting.left.exists(_.code === "locked_on_qc")
        )
  }

  test("finalization deletes after sidecar success; restart retains next-key lock while explicit rollback prunes it") {
    case (base, serializer, _, _) =>
      implicit val json: JsonSerializer[IO] = serializer
      val path = base / "lifecycle"
      val lock10 = CertifiedVoteLock(Some(0L), Some(hashA), None)
      val lock11 = CertifiedVoteLock(Some(0L), Some(hashB), None)

      for {
        journal <- CertifiedVoteLockPersistence.forSnapshotOrdinal[IO](path)
        consensus <- storage(journal)
        _ <- journal.write(key10, lock10)
        failedHook <- (IO.raiseError[Unit](new RuntimeException("sidecar failed")) >> consensus.deleteCertifiedVoteLock(key10)).attempt
        retained <- journal.read(key10)
        _ <- IO.unit >> consensus.deleteCertifiedVoteLock(key10)
        finalizedRemoved <- journal.read(key10)
        _ <- journal.write(key10, lock10)
        _ <- journal.write(key11, lock11)
        // Ordinary download/restart initialized from finalized N must remove stale <=N records but retain an in-flight lock at N+1.
        _ <- consensus.deleteCertifiedVoteLocksAtOrBelow(key10)
        downloadRemovedFinalized <- journal.read(key10)
        downloadRetainedInFlight <- journal.read(key11)
        sameViewConflictAfterRestart <- consensus.tryLockCertifiedVote(key11, 0L, hashA, None)
        // Explicit coordinated rollback to N is the distinct path authorized to discard speculative records above N.
        _ <- consensus.deleteCertifiedVoteLocksAbove(key10)
        rollbackRemovedInFlight <- journal.read(key11)
      } yield
        expect.all(
          failedHook.isLeft,
          retained.contains(lock10),
          finalizedRemoved.isEmpty,
          downloadRemovedFinalized.isEmpty,
          downloadRetainedInFlight.contains(lock11),
          sameViewConflictAfterRestart.left.exists(_.code === "conflicting_same_view"),
          rollbackRemovedInFlight.isEmpty
        )
  }
}
