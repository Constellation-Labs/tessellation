package io.constellationnetwork.currency.l0.snapshot.storage

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotStateProof, SnapshotFee}
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.{SnapshotOrdinal, SnapshotTips}
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import fs2.io.file.Files
import weaver.SimpleIOSuite

object StateChannelBinaryOutboxStorageSuite extends SimpleIOSuite {

  private val proof = SignatureProof(Id(Hex("peer")), Signature(Hex("signature")))

  private def signed[A](value: A): Signed[A] = Signed(value, NonEmptySet.one(proof))

  private def artifact(ordinal: Long, hash: String): Hashed[CurrencyIncrementalSnapshot] = {
    val value = CurrencyIncrementalSnapshot(
      ordinal = SnapshotOrdinal.unsafeApply(ordinal),
      height = Height.MinValue,
      subHeight = SubHeight.MinValue,
      lastSnapshotHash = Hash.empty,
      blocks = SortedSet.empty,
      rewards = SortedSet.empty,
      tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof = CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
      epochProgress = EpochProgress.MinValue,
      dataApplication = None,
      messages = None,
      globalSnapshotSyncs = None,
      feeTransactions = None,
      artifacts = None,
      allowSpendBlocks = None,
      tokenLockBlocks = None,
      globalSyncView = None
    )

    Hashed(signed(value), Hash(hash), ProofsHash(s"$hash-proofs"))
  }

  private def binary(ordinal: Long)(implicit hasher: Hasher[IO]): IO[Hashed[StateChannelSnapshotBinary]] =
    binaryWithParent(ordinal, Hash(s"parent-${ordinal - 1L}"))

  private def binaryWithParent(
    ordinal: Long,
    parentHash: Hash
  )(implicit hasher: Hasher[IO]): IO[Hashed[StateChannelSnapshotBinary]] =
    signed(
      StateChannelSnapshotBinary(
        parentHash,
        Array[Byte](ordinal.toByte, 2, 3),
        SnapshotFee.MinValue
      )
    ).toHashed

  test("fresh startup creates a previously absent outbox directory") {
    Files[IO].tempDirectory.use { directory =>
      val outboxDirectory = directory / "new-outbox-child"

      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        _ <- StateChannelBinaryOutboxStorage.make[IO](outboxDirectory)
        exists <- Files[IO].exists(outboxDirectory)
      } yield expect(exists)
    }
  }

  test("crash after prepare but before artifact persistence discards the non-publishable entry") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        value <- binary(11L)
        _ <- storage.prepare(value, artifact(11L, "artifact-11"))
        reconciled <- storage.reconcilePrepared(_ => none.pure[IO])
        reloaded <- StateChannelBinaryOutboxStorage.make[IO](directory)
        pending <- reloaded.getCommitted(Set.empty, 100)
      } yield expect(reconciled.isEmpty) && expect(pending.isEmpty)
    }
  }

  test("crash after artifact persistence promotes and restores the exact binary bytes") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        currencyArtifact = artifact(12L, "artifact-12")
        value <- binary(12L)
        _ <- storage.prepare(value, currencyArtifact)
        reconciled <- storage.reconcilePrepared(ordinal => Option.when(ordinal === currencyArtifact.ordinal)(currencyArtifact).pure[IO])
        reloaded <- StateChannelBinaryOutboxStorage.make[IO](directory)
        pending <- reloaded.getCommitted(Set.empty, 100)
      } yield
        expect.all(
          reconciled.map(_.binaryHash) === List(value.hash),
          pending.map(_.binaryHash) === List(value.hash),
          pending.map(_.binary) === List(value.signed)
        )
    }
  }

  test("a committed receipt survives ordinary snapshot-info retention and restores exact bytes") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        value <- binary(13L)
        _ <- storage.prepare(value, artifact(13L, "artifact-13"))
        _ <- storage.markLocallyCommitted(value.hash)
        reconciled <- storage.reconcilePrepared(_ => none.pure[IO])
        reloaded <- StateChannelBinaryOutboxStorage.make[IO](directory)
        pending <- reloaded.getCommitted(Set.empty, 100)
      } yield
        expect.all(
          reconciled.map(_.binaryHash) === List(value.hash),
          pending.map(_.binary) === List(value.signed)
        )
    }
  }

  test("a crash after rejected persistence discards a mismatched uncommitted intent") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        value <- binary(13L)
        _ <- storage.prepare(value, artifact(13L, "attempted-artifact"))
        reconciled <- storage.reconcilePrepared(_ => artifact(13L, "different-durable-artifact").some.pure[IO])
        pending <- storage.getCommitted(Set.empty, 100)
      } yield expect.all(reconciled.isEmpty, pending.isEmpty)
    }
  }

  test("a committed receipt conflicting with a still-present durable artifact fails closed") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        value <- binary(13L)
        _ <- storage.prepare(value, artifact(13L, "committed-artifact"))
        _ <- storage.markLocallyCommitted(value.hash)
        result <- storage.reconcilePrepared(_ => artifact(13L, "different-durable-artifact").some.pure[IO]).attempt
      } yield expect(result.swap.exists(_.isInstanceOf[StateChannelBinaryOutboxStorage.CurrencyArtifactMismatch]))
    }
  }

  test("canonical confirmation of a descendant clears its linked predecessors durably") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        first <- binary(14L)
        second <- binary(15L)
        _ <- storage.prepare(first, artifact(14L, "artifact-14")) >> storage.markLocallyCommitted(first.hash)
        _ <- storage.prepare(second, artifact(15L, "artifact-15")) >> storage.markLocallyCommitted(second.hash)
        confirmed <- storage.confirm(Set(second.hash))
        reloaded <- StateChannelBinaryOutboxStorage.make[IO](directory)
        pending <- reloaded.getCommitted(Set.empty, 100)
      } yield expect(confirmed.map(_.binaryHash) === List(first.hash, second.hash)) && expect(pending.isEmpty)
    }
  }

  test("canonical replacement discards every pending entry from the superseded local suffix") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        first <- binary(20L)
        second <- binary(21L)
        _ <- storage.prepare(first, artifact(20L, "artifact-20")) >> storage.markLocallyCommitted(first.hash)
        _ <- storage.prepare(second, artifact(21L, "artifact-21")) >> storage.markLocallyCommitted(second.hash)
        _ <- storage.discardAllForCanonicalReplacement
        pending <- storage.getCommitted(Set.empty, 100)
      } yield expect(pending.isEmpty)
    }
  }

  test("validated GL0 canonical Currency tip clears entries after incremental confirmation aged out") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        first <- binary(30L)
        second <- binary(31L)
        _ <- storage.prepare(first, artifact(30L, "artifact-30")) >> storage.markLocallyCommitted(first.hash)
        _ <- storage.prepare(second, artifact(31L, "artifact-31")) >> storage.markLocallyCommitted(second.hash)
        confirmed <- storage.confirmCanonicalTip(SnapshotOrdinal.unsafeApply(31L), second.hash)
        pending <- storage.getCommitted(Set.empty, 100)
      } yield expect(confirmed.map(_.binaryHash) === List(first.hash, second.hash)) && expect(pending.isEmpty)
    }
  }

  test("a canonical GL0 tip that conflicts at the same Currency ordinal fails closed") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        value <- binary(32L)
        _ <- storage.prepare(value, artifact(32L, "artifact-32")) >> storage.markLocallyCommitted(value.hash)
        result <- storage.confirmCanonicalTip(SnapshotOrdinal.unsafeApply(32L), Hash("different-binary")).attempt
      } yield expect(result.swap.exists(_.isInstanceOf[StateChannelBinaryOutboxStorage.CanonicalTipMismatch]))
    }
  }

  test("a pending successor must attach directly to the canonical GL0 tip") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        canonicalHash = Hash("canonical-binary-40")
        successor <- binaryWithParent(41L, Hash("losing-binary-40"))
        _ <- storage.prepare(successor, artifact(41L, "artifact-41"))
        _ <- storage.markLocallyCommitted(successor.hash)
        result <- storage.confirmCanonicalTip(SnapshotOrdinal.unsafeApply(40L), canonicalHash).attempt
      } yield expect(result.swap.exists(_.isInstanceOf[StateChannelBinaryOutboxStorage.CanonicalTipMismatch]))
    }
  }

  test("a pending successor attached to the canonical GL0 tip remains publishable") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory)
        canonicalHash = Hash("canonical-binary-50")
        successor <- binaryWithParent(51L, canonicalHash)
        _ <- storage.prepare(successor, artifact(51L, "artifact-51"))
        _ <- storage.markLocallyCommitted(successor.hash)
        confirmed <- storage.confirmCanonicalTip(SnapshotOrdinal.unsafeApply(50L), canonicalHash)
        pending <- storage.getCommitted(Set.empty, 100)
      } yield expect(confirmed.isEmpty) && expect.same(List(successor.hash), pending.map(_.binaryHash))
    }
  }

  test("outbox backpressures before count or serialized-byte bounds can grow without limit") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory, maxEntries = 1, maxSerializedBytes = 1024L * 1024L)
        first <- binary(40L)
        second <- binary(41L)
        _ <- storage.prepare(first, artifact(40L, "artifact-40"))
        countResult <- storage.prepare(second, artifact(41L, "artifact-41")).attempt
        stats <- storage.stats
      } yield
        expect.all(
          countResult.swap.exists(_.isInstanceOf[StateChannelBinaryOutboxStorage.CapacityExceeded]),
          stats.pendingCount === 1,
          stats.serializedBytes > 0L
        )
    }
  }

  test("startup rejects an on-disk outbox above the configured resource budget before restoring it") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory, maxEntries = 2)
        first <- binary(42L)
        second <- binary(43L)
        _ <- storage.prepare(first, artifact(42L, "artifact-42"))
        _ <- storage.prepare(second, artifact(43L, "artifact-43"))
        result <- StateChannelBinaryOutboxStorage.make[IO](directory, maxEntries = 1).attempt
      } yield expect(result.swap.exists(_.isInstanceOf[StateChannelBinaryOutboxStorage.CapacityExceeded]))
    }
  }

  test("a single entry above the serialized-byte budget is rejected before disk mutation") {
    Files[IO].tempDirectory.use { directory =>
      for {
        implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
        implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
        storage <- StateChannelBinaryOutboxStorage.make[IO](directory, maxSerializedBytes = 1L)
        value <- binary(44L)
        result <- storage.prepare(value, artifact(44L, "artifact-44")).attempt
        stats <- storage.stats
      } yield
        expect.all(
          result.swap.exists(_.isInstanceOf[StateChannelBinaryOutboxStorage.CapacityExceeded]),
          stats.pendingCount === 0,
          stats.serializedBytes === 0L
        )
    }
  }
}
