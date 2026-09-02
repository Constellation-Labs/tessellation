package io.constellationnetwork.currency.l0.snapshot.programs

import cats.effect.{IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.security.hash.Hash

import weaver.SimpleIOSuite

object DownloadSuite extends SimpleIOSuite {

  private val stateOrdinal = SnapshotOrdinal.unsafeApply(17L)
  private val expectedProof = Hash("expected")
  private val recoveredState = "calculated-state-at-17"

  private def record(ref: Ref[IO, Vector[String]], event: String): IO[Unit] = ref.update(_ :+ event)

  private def calculatedStateHooks(
    ref: Ref[IO, Vector[String]],
    actualProof: Hash = expectedProof,
    currentOrdinal: SnapshotOrdinal = SnapshotOrdinal.unsafeApply(16L),
    currentState: String = "calculated-state-at-16"
  ): Download.CalculatedStateHooks[IO, String] =
    Download.CalculatedStateHooks[IO, String](
      fetchExact = (ordinal, proof) => record(ref, s"fetch:${ordinal.value.value}:${proof.value}").as(recoveredState),
      hash = state => record(ref, s"hash:$state").as(if (state === recoveredState) actualProof else Hash("current")),
      persistAtomically = (ordinal, state) => record(ref, s"persist:${ordinal.value.value}:$state"),
      readPersisted = ordinal => record(ref, s"read:${ordinal.value.value}").as(recoveredState.some),
      getCurrent = record(ref, "current").as(currentOrdinal -> currentState),
      setCurrent = (ordinal, state) => record(ref, s"set:${ordinal.value.value}:$state").as(true)
    )

  test("downloads retain the release/mainnet four-snapshot observation window") {
    val current = SnapshotOrdinal.unsafeApply(10L)

    expect
      .same(
        SnapshotOrdinal.unsafeApply(14L),
        Download.observationLimit(current, 4L)
      )
      .pure[IO]
  }

  test("a downloaded head catches up to the live peer tip before selecting a future observation") {
    val downloadedHead = SnapshotOrdinal.unsafeApply(146L)
    val peerTip = SnapshotOrdinal.unsafeApply(150L)

    expect
      .same(
        Right(
          Download.ObservationPlan(
            catchUpThrough = SnapshotOrdinal.unsafeApply(150L),
            observationLimit = SnapshotOrdinal.unsafeApply(154L)
          )
        ),
        Download.observationPlan(downloadedHead, peerTip, 4L)
      )
      .pure[IO]
  }

  test("a selected peer tip behind the downloaded head it served fails closed") {
    val downloadedHead = SnapshotOrdinal.unsafeApply(150L)
    val peerTip = SnapshotOrdinal.unsafeApply(149L)

    expect
      .same(
        Left(Download.PeerTipBehindDownloadedHead(downloadedHead, peerTip)),
        Download.observationPlan(downloadedHead, peerTip, 4L)
      )
      .pure[IO]
  }

  test("recovery clears stale events before publishing consensus registration") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- Download.prepareObservationAdmission(
        record(events, "clear"),
        record(events, "register")
      )
      observed <- events.get
    } yield expect.same(Vector("clear", "register"), observed)
  }

  test("successor retries are bounded and only active download states re-anchor") {
    expect
      .all(
        Download.fetchNextRetryCap === 6,
        Download.shouldReanchorAfterFailure(NodeState.DownloadInProgress),
        Download.shouldReanchorAfterFailure(NodeState.WaitingForObserving),
        Download.shouldReanchorAfterFailure(NodeState.Observing),
        Download.shouldReanchorAfterFailure(NodeState.WaitingForReady),
        !Download.shouldReanchorAfterFailure(NodeState.WaitingForDownload),
        !Download.shouldReanchorAfterFailure(NodeState.Ready),
        !Download.shouldReanchorAfterFailure(NodeState.Leaving)
      )
      .pure[IO]
  }

  test("exact calculated state is fetched, verified, and persisted in order") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- Download.restoreCalculatedStateSteps[IO, String](
        stateOrdinal,
        expectedProof.some,
        calculatedStateHooks(events).some
      )
      observed <- events.get
    } yield
      expect.same(
        Vector(
          "current",
          "hash:calculated-state-at-16",
          "fetch:17:expected",
          s"hash:$recoveredState",
          s"persist:17:$recoveredState",
          "read:17",
          s"hash:$recoveredState",
          s"set:17:$recoveredState"
        ),
        observed
      )
  }

  test("a retry after atomic calculated-state persistence completes safely") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      persisted <- Ref.of[IO, Option[String]](none)
      failOnce <- Ref.of[IO, Boolean](true)
      current <- Ref.of[IO, (SnapshotOrdinal, String)](SnapshotOrdinal.unsafeApply(16L) -> "old")
      hooks = Download.CalculatedStateHooks[IO, String](
        fetchExact = (_, _) => recoveredState.pure[IO],
        hash = state => (if (state === recoveredState) expectedProof else Hash("old")).pure[IO],
        persistAtomically = (_, state) =>
          persisted.set(state.some) >> failOnce.getAndSet(false).flatMap(IO.raiseWhen(_)(new RuntimeException("crash after persistence"))),
        readPersisted = _ => persisted.get,
        getCurrent = current.get,
        setCurrent = (ordinal, state) => current.set(ordinal -> state).as(true)
      )
      first <- Download.restoreCalculatedStateSteps(stateOrdinal, expectedProof.some, hooks.some).attempt
      _ <- Download.restoreCalculatedStateSteps(stateOrdinal, expectedProof.some, hooks.some)
      installed <- current.get
      stored <- persisted.get
      observed <- events.get
    } yield
      expect(first.isLeft) &&
        expect.same(stateOrdinal -> recoveredState, installed) &&
        expect.same(recoveredState.some, stored) &&
        expect(observed.isEmpty)
  }

  test("an exact-current retry after application state installation persists without another peer fetch") {
    val crash = new RuntimeException("crash before canonical snapshot installation")

    for {
      current <- Ref.of[IO, (SnapshotOrdinal, String)](SnapshotOrdinal.unsafeApply(16L) -> "old")
      fetchCount <- Ref.of[IO, Int](0)
      persistCount <- Ref.of[IO, Int](0)
      setCount <- Ref.of[IO, Int](0)
      canonicalInstallCount <- Ref.of[IO, Int](0)
      hooks = Download.CalculatedStateHooks[IO, String](
        fetchExact = (_, _) => fetchCount.update(_ + 1).as(recoveredState),
        hash = state => (if (state === recoveredState) expectedProof else Hash("old")).pure[IO],
        persistAtomically = (_, _) => persistCount.update(_ + 1),
        readPersisted = _ => recoveredState.some.pure[IO],
        getCurrent = current.get,
        setCurrent = (ordinal, state) => setCount.update(_ + 1) >> current.set(ordinal -> state).as(true)
      )
      first <- (Download.restoreCalculatedStateSteps(stateOrdinal, expectedProof.some, hooks.some) >> crash.raiseError[IO, Unit]).attempt
      _ <- Download.restoreCalculatedStateSteps(stateOrdinal, expectedProof.some, hooks.some) >> canonicalInstallCount.update(_ + 1)
      fetched <- fetchCount.get
      persisted <- persistCount.get
      count <- setCount.get
      canonicalCount <- canonicalInstallCount.get
    } yield
      expect.same(Left(crash), first) &&
        expect.same(1, fetched) &&
        expect.same(2, persisted) &&
        expect.same(1, count) &&
        expect.same(1, canonicalCount)
  }

  test("same calculated-state ordinal with a different hash fails closed") {
    val conflicting = "conflicting-state-at-17"

    for {
      fetchCount <- Ref.of[IO, Int](0)
      persistCount <- Ref.of[IO, Int](0)
      setCount <- Ref.of[IO, Int](0)
      hooks = Download.CalculatedStateHooks[IO, String](
        fetchExact = (_, _) => fetchCount.update(_ + 1).as(recoveredState),
        hash = state => (if (state === recoveredState) expectedProof else Hash("conflicting")).pure[IO],
        persistAtomically = (_, _) => persistCount.update(_ + 1),
        readPersisted = _ => recoveredState.some.pure[IO],
        getCurrent = (stateOrdinal -> conflicting).pure[IO],
        setCurrent = (_, _) => setCount.update(_ + 1).as(true)
      )
      result <- Download.restoreCalculatedStateSteps(stateOrdinal, expectedProof.some, hooks.some).attempt
      fetched <- fetchCount.get
      persisted <- persistCount.get
      set <- setCount.get
    } yield
      expect(
        result == Left(
          Download.CalculatedStateCurrentConflict(stateOrdinal, stateOrdinal, Hash("conflicting"), expectedProof)
        )
      ) && expect.same(0, fetched) && expect.same(0, persisted) && expect.same(0, set)
  }

  test("application state ahead of the recovery target fails closed") {
    val aheadOrdinal = SnapshotOrdinal.unsafeApply(18L)

    for {
      fetchCount <- Ref.of[IO, Int](0)
      persistCount <- Ref.of[IO, Int](0)
      setCount <- Ref.of[IO, Int](0)
      hooks = Download.CalculatedStateHooks[IO, String](
        fetchExact = (_, _) => fetchCount.update(_ + 1).as(recoveredState),
        hash = state => (if (state === recoveredState) expectedProof else Hash("ahead")).pure[IO],
        persistAtomically = (_, _) => persistCount.update(_ + 1),
        readPersisted = _ => recoveredState.some.pure[IO],
        getCurrent = (aheadOrdinal -> "ahead-state").pure[IO],
        setCurrent = (_, _) => setCount.update(_ + 1).as(true)
      )
      result <- Download.restoreCalculatedStateSteps(stateOrdinal, expectedProof.some, hooks.some).attempt
      fetched <- fetchCount.get
      persisted <- persistCount.get
      set <- setCount.get
    } yield
      expect(
        result == Left(
          Download.CalculatedStateCurrentConflict(stateOrdinal, aheadOrdinal, Hash("ahead"), expectedProof)
        )
      ) && expect.same(0, fetched) && expect.same(0, persisted) && expect.same(0, set)
  }

  test("a calculated-state proof mismatch fails closed before persistence") {
    val wrongProof = Hash("wrong")

    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- Download
        .restoreCalculatedStateSteps[IO, String](
          stateOrdinal,
          expectedProof.some,
          calculatedStateHooks(events, wrongProof).some
        )
        .attempt
      observed <- events.get
    } yield
      expect
        .same(
          Vector("current", "hash:calculated-state-at-16", "fetch:17:expected", s"hash:$recoveredState"),
          observed
        )
        .and(expect(result == Left(Download.CalculatedStateProofMismatch(stateOrdinal, wrongProof, expectedProof))))
  }

  test("calculated-state configuration mismatch fails closed without calling hooks") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- Download
        .restoreCalculatedStateSteps[IO, String](
          stateOrdinal,
          expectedProof.some,
          none
        )
        .attempt
      observed <- events.get
    } yield
      expect(observed.isEmpty)
        .and(
          expect(
            result == Left(
              Download.CalculatedStateConfigurationMismatch(
                hasArtifactState = true,
                hasLocalApplication = false
              )
            )
          )
        )
  }
}
