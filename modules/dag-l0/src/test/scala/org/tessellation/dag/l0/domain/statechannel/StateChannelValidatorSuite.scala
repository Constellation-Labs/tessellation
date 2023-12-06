package org.tessellation.dag.l0.domain.statechannel

import cats.data.NonEmptySet
import cats.data.Validated.Valid
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.option._
import cats.syntax.validated._

import scala.collection.immutable.SortedSet

import org.tessellation.currency.schema.currency.SnapshotFee
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
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
import eu.timepit.refined.types.numeric.PosLong
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

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      peerId = NonEmptySet.one(PeerId.fromPublic(keyPair.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId.toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peerId).some
      )
      result <- validator.validate(scOutput)
    } yield expect.same(Valid(scOutput), result)

  }

  test("should fail when the signature is wrong") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1).map(signed =>
        signed.copy(proofs =
          NonEmptySet.fromSetUnsafe(
            SortedSet(signed.proofs.head.copy(id = keyPair2.getPublic.toId))
          )
        )
      )
      scOutput = StateChannelOutput(address, signedSCBinary)
      peerId = signedSCBinary.proofs.map(_.id.toPeerId)
      validator = mkValidator(
        peerId.toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peerId).some
      )
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.InvalidSigned(InvalidSignatures(signedSCBinary.proofs)).invalidNec,
        result
      )
  }

  test("should succeed when there is more than one allowed signature") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1)
      doubleSigned <- signedSCBinary.signAlsoWith(keyPair2)
      scOutput = StateChannelOutput(address, doubleSigned)
      peersIds = doubleSigned.proofs.map(_.id.toPeerId)
      validator = mkValidator(
        peersIds.toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peersIds).some
      )
      result <- validator.validate(scOutput)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when binary size exceeds max allowed size") { res =>
    implicit val (kryo, sp) = res

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
    val address = testStateChannel.toAddress
    val scOutput = StateChannelOutput(address, signedSCBinary)
    val peerId = signedSCBinary.proofs.map(_.id.toPeerId)
    val validator = mkValidator(
      peerId.map(SeedlistEntry(_, none, none, none)).toSortedSet.some,
      Map(address -> peerId).some,
      maxBinarySizeInBytes = 443L
    )

    validator
      .validate(scOutput)
      .map(
        expect.same(
          StateChannelValidator.BinaryExceedsMaxAllowedSize(443, 444).invalidNec,
          _
        )
      )
  }

  test("should fail when there is signature not from seedlist") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      peerId2 = NonEmptySet.one(PeerId.fromPublic(keyPair2.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.SignersNotInSeedlist(SignedValidator.SignersNotInSeedlist(peerId2.map(_.toId))).invalidNec,
        result
      )
  }

  test("should succeed when there is signature not from seedlist but is validated as historical") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validateHistorical(scOutput)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when the state channel address is not on the allowance list") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(None, Map.empty[Address, NonEmptySet[PeerId]].some)
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.StateChannelAddressNotAllowed(address).invalidNec,
        result
      )
  }

  test("should succeed when the state channel address is not on the allowance list but is validated as historical") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(None, Map.empty[Address, NonEmptySet[PeerId]].some)
      result <- validator.validateHistorical(scOutput)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when no signature is on the state channel allowance list for given address") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      peerId2 = NonEmptySet.one(PeerId.fromPublic(keyPair2.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair2)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.union(peerId2).toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.NoSignerFromStateChannelAllowanceList.invalidNec,
        result
      )
  }

  test("should succeed when no signature is on the state channel allowance list for given address but is validated as historical") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      peerId2 = NonEmptySet.one(PeerId.fromPublic(keyPair2.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair2)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.union(peerId2).toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validateHistorical(scOutput)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should succeed when at least one signature is on the state channel allowance list for given address") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      peerId2 = NonEmptySet.one(PeerId.fromPublic(keyPair2.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.union(peerId2).toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validate(scOutput)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should succeed when the seedlist and state channel allowance list check is disabled") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(None, None)
      result <- validator.validate(scOutput)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when address is not derived from genesis of the state channel") { res =>
    implicit val (kryo, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      peerId = NonEmptySet.one(PeerId.fromPublic(keyPair.getPublic))
      address = keyPair.getPublic.toAddress
      signedSCBinary <- forAsyncKryo(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId.toSortedSet.map(SeedlistEntry(_, none, none, none)).some,
        Map(address -> peerId).some
      )
      result <- validator.validate(scOutput)
    } yield
      expect.same(
        StateChannelValidator.StateChannellGenesisAddressNotGeneratedFromData(address).invalidNec,
        result
      )

  }

  private def mkValidator(
    seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    maxBinarySizeInBytes: PosLong = 1024L
  )(
    implicit S: SecurityProvider[IO],
    K: KryoSerializer[IO]
  ) =
    StateChannelValidator.make[IO](SignedValidator.make[IO], seedlist, stateChannelAllowanceLists, maxBinarySizeInBytes)

}
