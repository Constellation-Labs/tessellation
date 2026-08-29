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
    actualProof: Hash = expectedProof
  ): Download.CalculatedStateHooks[IO, String] =
    Download.CalculatedStateHooks[IO, String](
      fetchExact = (ordinal, proof) => record(ref, s"fetch:${ordinal.value.value}:${proof.value}").as(recoveredState),
      hash = state => record(ref, s"hash:$state").as(actualProof),
      persist = (ordinal, state) => record(ref, s"persist:${ordinal.value.value}:$state")
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

  test("a stale periodic checkpoint catches up to the live peer tip before selecting a future observation") {
    val checkpoint = SnapshotOrdinal.unsafeApply(146L)
    val peerTip = SnapshotOrdinal.unsafeApply(150L)

    expect
      .same(
        Right(
          Download.ObservationPlan(
            catchUpThrough = SnapshotOrdinal.unsafeApply(150L),
            observationLimit = SnapshotOrdinal.unsafeApply(154L)
          )
        ),
        Download.observationPlan(checkpoint, peerTip, 4L)
      )
      .pure[IO]
  }

  test("a selected peer tip behind its own checkpoint fails closed") {
    val checkpoint = SnapshotOrdinal.unsafeApply(150L)
    val peerTip = SnapshotOrdinal.unsafeApply(149L)

    expect
      .same(
        Left(Download.PeerTipBehindCheckpoint(checkpoint, peerTip)),
        Download.observationPlan(checkpoint, peerTip, 4L)
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
          "fetch:17:expected",
          s"hash:$recoveredState",
          s"persist:17:$recoveredState"
        ),
        observed
      )
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
        .same(Vector("fetch:17:expected", s"hash:$recoveredState"), observed)
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
