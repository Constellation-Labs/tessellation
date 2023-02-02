package org.tessellation.domain.statechannel

import cats.data.Validated.Valid
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.validated._

import org.tessellation.domain.statechannel.StateChannelValidator
import org.tessellation.domain.statechannel.StateChannelValidator.{InvalidSigned, NotSignedExclusivelyByStateChannelOwner}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.signature.SignedValidator
import org.tessellation.security.signature.SignedValidator.InvalidSignatures
import org.tessellation.security.{KeyPairGenerator, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object StateChannelValidatorSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, StateChannelServiceSuite.Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { ks =>
      SecurityProvider.forAsync[IO].map((ks, _))
    }

  private val testStateChannel = StateChannelSnapshotBinary(Hash.empty, "test".getBytes)

  test("should succeed when state channel is signed correctly") { res =>
    implicit val (kryo, sp) = res

    val validator = mkValidator()

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(keyPair.getPublic().toAddress, signedSCBinary)
      result <- validator.validate(scOutput)
    } yield expect.same(Valid(scOutput), result)

  }

  test("should fail when the signature is wrong") { res =>
    implicit val (kryo, sp) = res

    val validator = mkValidator()

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1).map(signed =>
        signed.copy(proofs = signed.proofs.map(_.copy(id = keyPair2.getPublic.toId)))
      )
      scOutput = StateChannelOutput(keyPair2.getPublic().toAddress, signedSCBinary)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        InvalidSigned(InvalidSignatures(signedSCBinary.proofs)).invalidNec,
        result
      )
  }

  test("should fail when the signature doesn't match address") { res =>
    implicit val (kryo, sp) = res

    val validator = mkValidator()

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1)
      scOutput = StateChannelOutput(keyPair2.getPublic().toAddress, signedSCBinary)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        NotSignedExclusivelyByStateChannelOwner.invalidNec,
        result
      )
  }

  test("should fail when there is more than one signature") { res =>
    implicit val (kryo, sp) = res

    val validator = mkValidator()

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1)
      doubleSigned <- signedSCBinary.signAlsoWith(keyPair2)
      scOutput = StateChannelOutput(keyPair1.getPublic().toAddress, doubleSigned)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        NotSignedExclusivelyByStateChannelOwner.invalidNec,
        result
      )
  }

  private def mkValidator()(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) =
    StateChannelValidator.make[IO](SignedValidator.make[IO])

}
