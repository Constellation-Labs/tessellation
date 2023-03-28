package org.tessellation.domain.statechannel

import cats.data.NonEmptySet
import cats.data.Validated.Valid
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.validated._

import scala.collection.immutable.SortedSet

import org.tessellation.domain.statechannel.StateChannelValidator._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.signature.SignedValidator.InvalidSignatures
import org.tessellation.security.signature.signature.{Signature, SignatureProof}
import org.tessellation.security.signature.{Signed, SignedValidator}
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

  test("should succeed when state channel is signed correctly and binary size is within allowed maximum") { res =>
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
        signed.copy(proofs =
          NonEmptySet.fromSetUnsafe(
            SortedSet(signed.proofs.head.copy(id = keyPair2.getPublic.toId))
          )
        )
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

  test("should fail when binary size exceeds max allowed size") { res =>
    implicit val (kryo, sp) = res

    val validator = mkValidator(maxBinarySizeInBytes = 425)

    val signedSCBinary = Signed(
      testStateChannel,
      NonEmptySet.fromSetUnsafe(
        SortedSet(
          SignatureProof(
            Id(
              Hex(
                "5ba2acdc046d9561f6310dafe0156ea7ec8c6fe3d9fdf9f19981accd52b56a807e61861593a067f2e3d27be0353155e88d5571975ac10fd033223a9c0e68e52c"
              )
            ),
            Signature(
              Hex(
                "3045022100bdb17bf6c65dd885a8ad5db4643fc9744c9dcbad0acc56024347f3cc61eeca8d022047c83e0a5f4d82d3150266e5b2ea335d7595a19627df7ee1256ea8bec9ab76a2"
              )
            )
          )
        )
      )
    )
    val scOutput = StateChannelOutput(Address("DAG1cjrte5wTNmZvZ3hAfAyayDzGcfxFGB2XCwPf"), signedSCBinary)

    validator
      .validate(scOutput)
      .map(
        expect.same(
          BinaryExceedsMaxAllowedSize(425, 426).invalidNec,
          _
        )
      )
  }

  private def mkValidator(maxBinarySizeInBytes: Long = 1024)(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) =
    StateChannelValidator.make[IO](SignedValidator.make[IO], maxBinarySizeInBytes)

}
