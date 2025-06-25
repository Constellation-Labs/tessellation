package io.constellationnetwork.node.shared.domain.statechannel

import cats.data.NonEmptySet
import cats.data.Validated.Valid
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.option._
import cats.syntax.validated._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.SignedValidator.InvalidSignatures
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import weaver.MutableIOSuite

object StateChannelValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  private val testStateChannel = StateChannelSnapshotBinary(Hash.empty, "test".getBytes, SnapshotFee.MinValue)

  test("should succeed when state channel is signed correctly and binary size is within allowed maximum") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      peerId = NonEmptySet.one(PeerId.fromPublic(keyPair.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId.toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peerId).some
      )
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield expect.same(Valid(scOutput), result)

  }

  test("should fail when the signature is wrong") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair1).map(signed =>
        signed.copy(proofs =
          NonEmptySet.fromSetUnsafe(
            SortedSet(signed.proofs.head.copy(id = keyPair2.getPublic.toId))
          )
        )
      )
      scOutput = StateChannelOutput(address, signedSCBinary)
      peerId = signedSCBinary.proofs.map(_.id.toPeerId)
      validator = mkValidator(
        peerId.toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peerId).some
      )
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield
      expect.same(
        StateChannelValidator.InvalidSigned(InvalidSignatures(signedSCBinary.proofs)).invalidNec,
        result
      )
  }

  test("should succeed when there is more than one allowed signature") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair1)
      doubleSigned <- signedSCBinary.signAlsoWith(keyPair2)
      scOutput = StateChannelOutput(address, doubleSigned)
      peersIds = doubleSigned.proofs.map(_.id.toPeerId)
      validator = mkValidator(
        peersIds.toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peersIds).some
      )
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when binary size exceeds max allowed size") { res =>
    implicit val (kryo, h, sp) = res

    val signedSCBinary = Signed(
      testStateChannel,
      NonEmptySet.fromSetUnsafe(
        SortedSet(
          SignatureProof(
            Id(
              Hex(
                "d7cfd37cf032e855a3616f6bea1943569fdd94807e814e53062cc6a62da7d4dfac48f0e66d27020f3a9ca6507c776ccfc2042ee206181290c674b545132cc0fd"
              )
            ),
            Signature(
              Hex(
                "304602210090a1bf0f79b0bb8cdf5d5df926c46258d980d3cbabf87413355e4e7a2fa7a72e022100dd27dc251c9032ab9bf9af28ef53b47a9bc4991e87c05d2cfdbab98d3946e1de"
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
      peerId.map(SeedlistEntry(_, none, none, none, none)).toSortedSet.some,
      Map(address -> peerId).some,
      maxBinarySizeInBytes = 282L
    )

    validator
      .validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
      .map(
        expect.same(
          StateChannelValidator.BinaryExceedsMaxAllowedSize(282, 283).invalidNec,
          _
        )
      )
  }

  test("should fail when there is signature not from seedlist") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      peerId2 = NonEmptySet.one(PeerId.fromPublic(keyPair2.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield
      expect.same(
        StateChannelValidator.SignersNotInSeedlist(SignedValidator.SignersNotInSeedlist(peerId2.map(_.toId))).invalidNec,
        result
      )
  }

  test("should succeed when there is signature not from seedlist but is validated as historical") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validateHistorical(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when the state channel address is not on the allowance list") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(None, Map.empty[Address, NonEmptySet[PeerId]].some)
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield
      expect.same(
        StateChannelValidator.StateChannelAddressNotAllowed(address).invalidNec,
        result
      )
  }

  test("should succeed when the state channel address is not on the allowance list but is validated as historical") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(None, Map.empty[Address, NonEmptySet[PeerId]].some)
      result <- validator.validateHistorical(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when no signature is on the state channel allowance list for given address") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      peerId2 = NonEmptySet.one(PeerId.fromPublic(keyPair2.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair2)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.union(peerId2).toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield
      expect.same(
        StateChannelValidator.NoSignerFromStateChannelAllowanceList.invalidNec,
        result
      )
  }

  test("should succeed when no signature is on the state channel allowance list for given address but is validated as historical") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      peerId2 = NonEmptySet.one(PeerId.fromPublic(keyPair2.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair2)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.union(peerId2).toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validateHistorical(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should succeed when at least one signature is on the state channel allowance list for given address") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      peerId1 = NonEmptySet.one(PeerId.fromPublic(keyPair1.getPublic))
      peerId2 = NonEmptySet.one(PeerId.fromPublic(keyPair2.getPublic))
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair1).flatMap(_.signAlsoWith(keyPair2))
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId1.union(peerId2).toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peerId1).some
      )
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should succeed when the seedlist and state channel allowance list check is disabled") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = testStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(None, None)
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when address is not derived from genesis of the state channel") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      peerId = NonEmptySet.one(PeerId.fromPublic(keyPair.getPublic))
      address = keyPair.getPublic.toAddress
      signedSCBinary <- forAsyncHasher(testStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      validator = mkValidator(
        peerId.toSortedSet.map(SeedlistEntry(_, none, none, none, none)).some,
        Map(address -> peerId).some
      )
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield
      expect.same(
        StateChannelValidator.StateChannellGenesisAddressNotGeneratedFromData(address).invalidNec,
        result
      )

  }

  test("should succeed when snapshot fee is sufficient") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      feeTestStateChannel = testStateChannel.copy(fee = SnapshotFee(1000L))
      address = feeTestStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(feeTestStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      feeConfigs = SortedMap(SnapshotOrdinal.MinValue -> FeeCalculatorConfig(1000L, BigDecimal(0.0d), 1L, BigDecimal(0.0d)))
      validator = mkValidator(None, None, feeConfigs = feeConfigs)
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield expect.same(Valid(scOutput), result)
  }

  test("should fail when snapshot fee is not sufficient") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      feeTestStateChannel = testStateChannel.copy(fee = SnapshotFee(1000L))
      address = feeTestStateChannel.toAddress
      signedSCBinary <- forAsyncHasher(feeTestStateChannel, keyPair)
      scOutput = StateChannelOutput(address, signedSCBinary)
      feeConfigs = SortedMap(SnapshotOrdinal.MinValue -> FeeCalculatorConfig(1001L, BigDecimal(0.0d), 1L, BigDecimal(0.0d)))
      validator = mkValidator(None, None, feeConfigs = feeConfigs)
      result <- validator.validate(scOutput, SnapshotOrdinal.MinValue, SnapshotFeesInfo.empty)
    } yield
      expect.same(
        StateChannelValidator.BinaryFeeNotSufficient(SnapshotFee(1000L), SnapshotFee(1001L), 1, SnapshotOrdinal.MinValue).invalidNec,
        result
      )
  }

  private def mkValidator(
    seedlist: Option[Set[SeedlistEntry]],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    maxBinarySizeInBytes: PosLong = 1024L,
    feeConfigs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig] = SortedMap(SnapshotOrdinal.MinValue -> FeeCalculatorConfig.noFee)
  )(
    implicit S: SecurityProvider[IO],
    J: JsonSerializer[IO],
    H: Hasher[IO]
  ) =
    StateChannelValidator
      .make[IO](SignedValidator.make[IO], seedlist, stateChannelAllowanceLists, maxBinarySizeInBytes, FeeCalculator.make[IO](feeConfigs))

}
