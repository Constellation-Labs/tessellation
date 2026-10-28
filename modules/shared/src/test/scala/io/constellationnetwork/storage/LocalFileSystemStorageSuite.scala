package io.constellationnetwork.storage

import cats.effect.IO

import io.constellationnetwork.json.JsonSerializer

import better.files.File
import fs2.io.file.Path
import io.circe.{Decoder, Encoder}
import weaver.SimpleIOSuite

object LocalFileSystemStorageSuite extends SimpleIOSuite {

  test("read returns None when both deserializers throw") {
    File.temporaryDirectory() { root =>
      implicit val jsonSerializer: JsonSerializer[IO] = new JsonSerializer[IO] {
        def serialize[A: Encoder](content: A): IO[Array[Byte]] = IO.pure(Array.emptyByteArray)

        def deserialize[A: Decoder](content: Array[Byte]): IO[Either[Throwable, A]] =
          IO.raiseError(new IllegalArgumentException("damaged JSON bytes"))
      }

      val storage = new SerializableLocalFileSystemStorage[IO, String](Path(root.pathAsString)) {
        def deserializeFallback(bytes: Array[Byte]): Either[Throwable, String] =
          throw new IllegalStateException("damaged fallback bytes")
      }

      for {
        _ <- storage.createDirectoryIfNotExists().rethrowT
        _ <- storage.write("damaged", Array[Byte](1, 2, 3))
        result <- storage.read("damaged")
      } yield expect.same(None, result)
    }
  }
}
