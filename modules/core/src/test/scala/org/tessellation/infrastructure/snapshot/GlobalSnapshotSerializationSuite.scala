package org.tessellation.infrastructure.snapshot

import java.nio.file.{Paths => JPaths}

import cats.effect.{IO, Resource}
import cats.syntax.all._

import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema._
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}

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

  type Res = KryoSerializer[IO]

  def sharedResource: Resource[IO, KryoSerializer[IO]] =
    KryoSerializer.forAsync[IO](sdkKryoRegistrar)

  test("snapshot is successfully deserialized and serialized with kryo") { implicit kryo =>
    for {
      storedBytes <- getBytesFromClasspath(kryoFilename)
      signedSnapshot: Signed[GlobalSnapshot] <- storedBytes.fromBinaryF[Signed[GlobalSnapshot]]
      _ = println(signedSnapshot.proofs)
      _ = println("") // 128 + 142 single SignatureProof
      additionalSignature = SignatureProof(
        Id(
          Hex(
            "f0073ae1852b2adee1af9353b5b7b124fd417b862565edfe5c853973e23e313180f6baffa4628a7ea26af7115bf1176ad70415ed8c08dd3c42264286bf4a33a5"
          )
        ),
        Signature(
          Hex(
            "30450221009c2fed2fc6ee39781e1bad9e3721f3dce1696b25b2d802bf0c3760573c217b34022054d9144b0418461ff67e371cffe6dedee0d6f8afd492f734c3d8be5a9ddcadcc"
          )
        )
      )
      proofSizesInBytes <- (signedSnapshot.proofs.toList :+ additionalSignature).traverse(_.toBinaryF)
      wholeSize <- (signedSnapshot.proofs.toList :+ additionalSignature).toBinaryF.map(_.length)
      _ = for (a <- proofSizesInBytes)
        println(wholeSize) // 296
      serializedBytes <- signedSnapshot.toBinaryF
      snapshotHash <- signedSnapshot.value.hashF
    } yield expect.same(serializedBytes, storedBytes).and(expect.same(snapshotHash, expectedHash))
  }

  test("snapshot is successfully deserialized and serialized with json parser") { implicit kryo =>
    for {
      storedJson <- getJsonFromClasspath(jsonFilename)
      signedSnapshot <- storedJson.as[Signed[GlobalSnapshot]].leftWiden[Throwable].liftTo[IO]
      snapshotHash <- signedSnapshot.value.hashF
      serializedJson = signedSnapshot.asJson
    } yield expect.same(serializedJson, storedJson).and(expect.same(snapshotHash, expectedHash))
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
