package org.tessellation.domain.statechannel

import cats.data.NonEmptySet
import cats.data.Validated.Valid
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.validated._

import scala.collection.immutable.SortedSet

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.sdk.domain.statechannel.StateChannelValidator
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.signature.SignedValidator.InvalidSignatures
import org.tessellation.security.signature.signature.{Signature, SignatureProof}
import org.tessellation.security.signature.{Signed, SignedValidator}
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

  private val testStateChannel = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)

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
        StateChannelValidator.InvalidSigned(InvalidSignatures(signedSCBinary.proofs)).invalidNec,
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
        StateChannelValidator.NotSignedExclusivelyByStateChannelOwner.invalidNec,
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
        StateChannelValidator.NotSignedExclusivelyByStateChannelOwner.invalidNec,
        result
      )
  }

  test("should fail when binary size exceeds max allowed size") { res =>
    implicit val (kryo, sp) = res

    val validator = mkValidator(maxBinarySizeInBytes = 443)

    val signedSCBinary = Signed(
      testStateChannel,
      NonEmptySet.fromSetUnsafe(
        SortedSet(
          SignatureProof(
            Id(
              Hex(
                "c4960e903a9d05662b83332a9ee7059ec214e6d587ae5d80f97924bb1519be7ed60116887ce90cff6134697df3faebdfb6a6a04c06a7270b90685532d2fa85e1"
              )
            ),
            Signature(
              Hex(
                "304402201cf4a09b3a693f2627ca94df9715bb8b119c8518e79128b88d4d6531f01dac5502204f377d700ebb8f336f8eedb1a9dde9f2aacca4132612d6528aea4ec2570d89f3"
              )
            )
          )
        )
      )
    )
    val scOutput = StateChannelOutput(Address("DAG7EJu17WPtbKMP5kNBWGpp3iVtmNwDeS6E4ge8"), signedSCBinary)

    validator
      .validate(scOutput)
      .map(
        expect.same(
          StateChannelValidator.BinaryExceedsMaxAllowedSize(443, 444).invalidNec,
          _
        )
      )
  }

  private def mkValidator(maxBinarySizeInBytes: Long = 1024)(implicit S: SecurityProvider[IO], K: KryoSerializer[IO]) =
    StateChannelValidator.make[IO](SignedValidator.make[IO], maxBinarySizeInBytes)

}
