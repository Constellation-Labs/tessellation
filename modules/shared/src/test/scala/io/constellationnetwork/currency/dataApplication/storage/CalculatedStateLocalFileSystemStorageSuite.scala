package io.constellationnetwork.currency.dataApplication.storage

import java.nio.charset.StandardCharsets.UTF_8

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.schema.SnapshotOrdinal

import fs2.Stream
import fs2.io.file.Files
import weaver.SimpleIOSuite

object CalculatedStateLocalFileSystemStorageSuite extends SimpleIOSuite {

  test("atomic calculated-state writes replace one exact ordinal without leaving temporary files") {
    Files[IO].tempDirectory.use { directory =>
      val ordinal = SnapshotOrdinal.unsafeApply(42L)
      implicit val encode: String => IO[Array[Byte]] = value => value.getBytes(UTF_8).pure[IO]
      implicit val decode: Array[Byte] => IO[String] = bytes => new String(bytes, UTF_8).pure[IO]

      for {
        storage <- CalculatedStateLocalFileSystemStorage.make[IO](directory)
        _ <- storage.writeAtomically(ordinal, "first")
        _ <- storage.writeAtomically(ordinal, "second")
        restored <- storage.read[String](ordinal)
        files <- Files[IO].list(directory).map(_.fileName.toString).compile.toList
      } yield expect.same("second".some, restored) && expect.same(List("42"), files)
    }
  }

  test("startup removes abandoned atomic-write files without touching unrelated files") {
    Files[IO].tempDirectory.use { directory =>
      val abandoned = directory / ".atomic-42.interrupted.tmp"
      val unrelated = directory / "unrelated.tmp"
      val malformed = directory / ".atomic-not-an-ordinal.interrupted.tmp"

      for {
        _ <- Stream.emits(Array[Byte](1)).through(Files[IO].writeAll(abandoned)).compile.drain
        _ <- Stream.emits(Array[Byte](2)).through(Files[IO].writeAll(unrelated)).compile.drain
        _ <- Stream.emits(Array[Byte](3)).through(Files[IO].writeAll(malformed)).compile.drain
        _ <- CalculatedStateLocalFileSystemStorage.make[IO](directory)
        abandonedExists <- Files[IO].exists(abandoned)
        unrelatedExists <- Files[IO].exists(unrelated)
        malformedExists <- Files[IO].exists(malformed)
      } yield expect.all(!abandonedExists, unrelatedExists, malformedExists)
    }
  }
}
