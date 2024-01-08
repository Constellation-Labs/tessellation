package org.tessellation.node.shared.infrastructure.snapshot

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.cli.CliMethod.snapshotSizeConfig
import org.tessellation.node.shared.nodeSharedKryoRegistrar
import org.tessellation.security.signature.{Signed, signature}
import org.tessellation.security.{Hasher, KeyPairGenerator, SecurityProvider}

import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object CurrencySnapshotCreatorSuite extends MutableIOSuite with Checkers {
  type Res = (KryoSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] =
    for {
      implicit0(k: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
      implicit0(j: JsonHashSerializer[IO]) <- JsonHashSerializer.forSync[IO].asResource
      h = Hasher.forSync[IO]
      sp <- SecurityProvider.forAsync[IO]
    } yield (k, h, sp)

  def signatureProofsGen(implicit sp: SecurityProvider[IO], h: Hasher[IO]): Gen[signature.SignatureProof] = for {
    text <- Gen.alphaStr
    keyPair <- Gen.delay(KeyPairGenerator.makeKeyPair[IO].unsafeRunSync())
    signed = Signed.forAsyncKryo(text, keyPair).unsafeRunSync()
  } yield signed.proofs.head

  test("single signature size matches the size limit") { implicit res =>
    implicit val (kryo, h, sp) = res

    forall(signatureProofsGen) { proof =>
      for {
        sizeInBytes: Int <- proof.toBinaryF.map(_.length)
        sizeDiff = Math.abs(sizeInBytes.toLong - snapshotSizeConfig.singleSignatureSizeInBytes.value)
      } yield expect(sizeDiff <= 4L) // ECDSA uses variable length for signature so the size may vary +/- 4 chars
    }
  }
}
