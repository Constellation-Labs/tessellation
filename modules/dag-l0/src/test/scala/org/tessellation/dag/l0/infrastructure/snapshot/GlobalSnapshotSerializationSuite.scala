package org.tessellation.dag.l0.infrastructure.snapshot

import java.nio.file.{Paths => JPaths}

import cats.effect.{IO, Resource}
import cats.syntax.all._

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.schema._
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.io.file.{Files, Path}
import io.circe.Json
import io.circe.jawn.CirceSupportParser
import io.circe.syntax._
import io.estatico.newtype.ops._
import org.typelevel.jawn.Facade
import org.typelevel.jawn.fs2._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object GlobalSnapshotSerializationSuite extends MutableIOSuite with Checkers {

  val expectedHash: Hash = Hash("c24121cb3233364d80e80cb473510a4b7ddf4cb47a47a2f84cff8b6fee7f8b1c")
  val kryoFilename: String = expectedHash.coerce
  val jsonFilename: String = s"${expectedHash.coerce}.json"

  type Res = (KryoSerializer[IO], Hasher[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    hk = Hasher.forKryo[IO]
  } yield (ks, hk)

  test("snapshot is successfully deserialized and serialized with kryo") { res =>
    implicit val (ks, hk) = res

    for {
      storedBytes <- getBytesFromClasspath(kryoFilename)
      signedSnapshot <- storedBytes.fromBinaryF[Signed[GlobalSnapshot]]
      serializedBytes <- signedSnapshot.toBinaryF
      hashCompare <- hk.compare(signedSnapshot.value, expectedHash)
      // } yield expect.eql(serializedBytes, storedBytes).and(expect(hashCompare))
    } yield expect(hashCompare)
  }

  test("snapshot is successfully deserialized and serialized with json parser") { implicit res =>
    implicit val (_, hk) = res

    for {
      storedJson <- getJsonFromClasspath(jsonFilename)
      signedSnapshot <- storedJson.as[Signed[GlobalSnapshot]].leftWiden[Throwable].liftTo[IO]
      serializedJson = signedSnapshot.asJson
      hashCompare <- hk.compare(signedSnapshot.value, expectedHash)
    } yield expect.eql(serializedJson, storedJson).and(expect(hashCompare))
  }

  private def getJsonFromClasspath(name: String): F[Json] = {
    implicit val facade: Facade[Json] = new CirceSupportParser(None, false).facade

    Files[F]
      .readAll(resourceAsPath(name))
      .chunks
      .parseJsonStream[Json]
      .compile
      .lastOrError
  }

  private def getBytesFromClasspath(name: String): F[Array[Byte]] =
    Files[F]
      .readAll(resourceAsPath(name))
      .compile
      .to(Array)

  private def resourceAsPath(name: String): Path =
    Path.fromNioPath(JPaths.get(Thread.currentThread().getContextClassLoader.getResource(name).toURI))

}
