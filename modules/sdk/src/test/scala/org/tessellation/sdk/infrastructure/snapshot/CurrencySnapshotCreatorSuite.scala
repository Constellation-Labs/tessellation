package org.tessellation.sdk.infrastructure.snapshot

import cats.effect.{IO, Resource}

import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.generators.signatureProofGen
import org.tessellation.sdk.cli.CliMethod.snapshotSizeConfig
import org.tessellation.sdk.sdkKryoRegistrar

import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object CurrencySnapshotCreatorSuite extends MutableIOSuite with Checkers {
  type Res = KryoSerializer[IO]

  def sharedResource: Resource[IO, KryoSerializer[IO]] =
    KryoSerializer.forAsync[IO](sdkKryoRegistrar)

  test("single signature size matches the size limit") { implicit kryo =>
    forall(signatureProofGen) { proof =>
      for {
        bytes: Array[Byte] <- proof.toBinaryF
        sizeInBytes = bytes.length
        sizeDiff = sizeInBytes.toLong - snapshotSizeConfig.singleSignatureProofSize.value
      } yield expect(sizeDiff <= 4L) // ECDSA uses variable length for signature so the size may vary +/- 4 chars
    }
  }
}
