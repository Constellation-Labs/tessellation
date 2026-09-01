package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.{IO, Resource}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.SnapshotOrdinal

import fs2.Stream
import fs2.io.file.{Files, Path}
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}
import weaver.MutableIOSuite

object OrdinalJsonSidecarStorageSuite extends MutableIOSuite {

  final case class Example(label: String, count: Int)
  object Example {
    implicit val encoder: Encoder[Example] = deriveEncoder
    implicit val decoder: Decoder[Example] = deriveDecoder
  }

  override type Res = (Path, JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      path <- Files[IO].tempDirectory(None, "ordinal-json-sidecar-test-", None)
      serializer <- JsonSerializer.forAsync[IO].asResource
    } yield path -> serializer

  private val ord10 = SnapshotOrdinal.unsafeApply(10L)
  private val ord11 = SnapshotOrdinal.unsafeApply(11L)
  private val ord12 = SnapshotOrdinal.unsafeApply(12L)
  private val ord21 = SnapshotOrdinal.unsafeApply(21L)
  private val ord20 = SnapshotOrdinal.unsafeApply(20L)

  test("a fresh storage instance reads the typed value written by an earlier instance") {
    case (base, serializer) =>
      implicit val jsonSerializer: JsonSerializer[IO] = serializer
      val testBase = base / "roundtrip"

      for {
        first <- OrdinalJsonSidecarStorage.make[IO, Example](testBase)
        _ <- first.write(ord10, Example("certified", 3))
        restarted <- OrdinalJsonSidecarStorage.make[IO, Example](testBase)
        result <- restarted.read(ord10)
        tempFiles <- Files[IO].list(testBase).map(_.fileName.toString).filter(_.endsWith(".tmp")).compile.toList
      } yield expect.all(result.contains(Example("certified", 3)), tempFiles.isEmpty)
  }

  test("missing or corrupt files never fabricate recovery evidence") {
    case (base, serializer) =>
      implicit val jsonSerializer: JsonSerializer[IO] = serializer
      val testBase = base / "malformed"
      val corrupt = testBase / ord11.value.value.toString

      for {
        storage <- OrdinalJsonSidecarStorage.make[IO, Example](testBase)
        missing <- storage.read(ord10)
        _ <- Stream.emits(Array[Byte](1, 2, 3, 4)).through(Files[IO].writeAll(corrupt)).compile.drain
        malformed <- storage.read(ord11)
      } yield expect.all(missing.isEmpty, malformed.isEmpty)
  }

  test("rewriting an ordinal atomically replaces the prior typed sidecar without temporary-file residue") {
    case (base, serializer) =>
      implicit val jsonSerializer: JsonSerializer[IO] = serializer
      val testBase = base / "replace"

      for {
        storage <- OrdinalJsonSidecarStorage.make[IO, Example](testBase)
        _ <- storage.write(ord10, Example("old", 1))
        _ <- storage.write(ord10, Example("new", 2))
        value <- storage.read(ord10)
        files <- Files[IO].list(testBase).map(_.fileName.toString).compile.toList
      } yield expect.all(value.contains(Example("new", 2)), files == List(ord10.value.value.toString))
  }

  test("deleteAbove follows the snapshot rollback boundary") {
    case (base, serializer) =>
      implicit val jsonSerializer: JsonSerializer[IO] = serializer
      val testBase = base / "delete-above"

      for {
        storage <- OrdinalJsonSidecarStorage.make[IO, Example](testBase)
        _ <- storage.write(ord10, Example("keep", 10))
        _ <- storage.write(ord11, Example("remove", 11))
        _ <- storage.deleteAbove(ord10)
        kept <- storage.read(ord10)
        removed <- storage.read(ord11)
      } yield expect.all(kept.contains(Example("keep", 10)), removed.isEmpty)
  }

  test("retention reuses the snapshot-info logarithmic cutoff and always preserves the immediate recovery window") {
    case (base, serializer) =>
      implicit val jsonSerializer: JsonSerializer[IO] = serializer
      val testBase = base / "retention"

      for {
        storage <- OrdinalJsonSidecarStorage.make[IO, Example](testBase)
        _ <- storage.write(ord11, Example("prune", 11))
        _ <- storage.write(ord12, Example("recent", 12))
        _ <- storage.write(ord20, Example("predecessor", 20))
        _ <- storage.write(ord21, Example("current", 21))
        _ <- storage.retain(SnapshotOrdinal.MinValue, ord21)
        pruned <- storage.read(ord11)
        recent <- storage.read(ord12)
        predecessor <- storage.read(ord20)
        current <- storage.read(ord21)
      } yield
        expect.all(
          pruned.isEmpty,
          recent.contains(Example("recent", 12)),
          predecessor.contains(Example("predecessor", 20)),
          current.contains(Example("current", 21))
        )
  }

  test("retention preserves an explicitly pinned recovery anchor after the immediate window advances") {
    case (base, serializer) =>
      implicit val jsonSerializer: JsonSerializer[IO] = serializer
      val testBase = base / "pinned-recovery-anchor"

      for {
        storage <- OrdinalJsonSidecarStorage.make[IO, Example](testBase)
        _ <- storage.write(ord10, Example("authorized-anchor", 10))
        _ <- storage.write(ord20, Example("predecessor", 20))
        _ <- storage.write(ord21, Example("current", 21))
        _ <- storage.retain(SnapshotOrdinal.MinValue, ord21, Set(ord10))
        anchor <- storage.read(ord10)
        predecessor <- storage.read(ord20)
        current <- storage.read(ord21)
      } yield
        expect.all(
          anchor.contains(Example("authorized-anchor", 10)),
          predecessor.contains(Example("predecessor", 20)),
          current.contains(Example("current", 21))
        )
  }
}
