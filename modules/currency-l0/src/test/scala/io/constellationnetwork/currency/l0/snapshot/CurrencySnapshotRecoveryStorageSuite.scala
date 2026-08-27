package io.constellationnetwork.currency.l0.snapshot

import cats.effect.{IO, Ref}
import cats.syntax.all._

import io.constellationnetwork.currency.l0.snapshot.CurrencySnapshotRecoveryStorage.{
  CalculatedStateHooks,
  CalculatedStateProofMismatch,
  RecoveryModeMismatch
}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash

import weaver.SimpleIOSuite

object CurrencySnapshotRecoveryStorageSuite extends SimpleIOSuite {

  private val ordinal = SnapshotOrdinal.unsafeApply(17L)
  private val expectedProof = Hash("expected")
  private val recoveredState = "calculated-state-at-17"

  private def record(ref: Ref[IO, Vector[String]], event: String): IO[Unit] = ref.update(_ :+ event)

  private def hooks(ref: Ref[IO, Vector[String]], actualProof: Hash = expectedProof): CalculatedStateHooks[IO, String] =
    CalculatedStateHooks[IO, String](
      fetchExact = (requested, proof) => record(ref, s"fetch:${requested.value.value}:${proof.value}").as(recoveredState),
      hash = state => record(ref, s"hash:$state").as(actualProof),
      persist = (requested, state) => record(ref, s"persist:${requested.value.value}:$state")
    )

  test("certified calculated state is fetched, verified, and persisted before the snapshot head advances") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- CurrencySnapshotRecoveryStorage.synchronizeSteps[IO, String](
        ordinal,
        expectedProof.some,
        hooks(events).some,
        record(events, "snapshot-head"),
        record(events, "clear-mempool")
      )
      observed <- events.get
    } yield
      expect.same(
        Vector(
          "fetch:17:expected",
          s"hash:$recoveredState",
          s"persist:17:$recoveredState",
          "snapshot-head",
          "clear-mempool"
        ),
        observed
      )
  }

  test("a calculated-state proof mismatch fails closed before persistence or snapshot advancement") {
    val wrongProof = Hash("wrong")

    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- CurrencySnapshotRecoveryStorage
        .synchronizeSteps[IO, String](
          ordinal,
          expectedProof.some,
          hooks(events, wrongProof).some,
          record(events, "snapshot-head"),
          record(events, "clear-mempool")
        )
        .attempt
      observed <- events.get
    } yield
      expect
        .same(Vector("fetch:17:expected", s"hash:$recoveredState"), observed)
        .and(expect(result == Left(CalculatedStateProofMismatch(ordinal, wrongProof, expectedProof))))
  }

  test("an unavailable calculated state fails closed before hashing, persistence, or snapshot advancement") {
    val unavailable = new RuntimeException("state unavailable")

    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      unavailableHooks = CalculatedStateHooks[IO, String](
        fetchExact = (_, _) => record(events, "fetch").flatMap(_ => unavailable.raiseError[IO, String]),
        hash = _ => record(events, "hash").as(expectedProof),
        persist = (_, _) => record(events, "persist")
      )
      result <- CurrencySnapshotRecoveryStorage
        .synchronizeSteps[IO, String](
          ordinal,
          expectedProof.some,
          unavailableHooks.some,
          record(events, "snapshot-head"),
          record(events, "clear-mempool")
        )
        .attempt
      observed <- events.get
    } yield expect.same(Vector("fetch"), observed).and(expect.same(Left(unavailable), result))
  }

  test("a currency without a data application advances the snapshot head and then clears the mempool") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      _ <- CurrencySnapshotRecoveryStorage.synchronizeSteps[IO, String](
        ordinal,
        none,
        none,
        record(events, "snapshot-head"),
        record(events, "clear-mempool")
      )
      observed <- events.get
    } yield expect.same(Vector("snapshot-head", "clear-mempool"), observed)
  }

  test("calculated-state configuration asymmetry fails closed") {
    for {
      events <- Ref.of[IO, Vector[String]](Vector.empty)
      result <- CurrencySnapshotRecoveryStorage
        .synchronizeSteps[IO, String](
          ordinal,
          expectedProof.some,
          none,
          record(events, "snapshot-head"),
          record(events, "clear-mempool")
        )
        .attempt
      observed <- events.get
    } yield
      expect(observed.isEmpty)
        .and(expect(result == Left(RecoveryModeMismatch(hasArtifactState = true, hasCalculatedStateHooks = false))))
  }
}
