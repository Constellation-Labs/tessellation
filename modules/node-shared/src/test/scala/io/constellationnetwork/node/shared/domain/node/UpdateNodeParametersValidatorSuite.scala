package io.constellationnetwork.node.shared.domain.node

import cats.data.NonEmptySet
import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.{catsSyntaxOptionId, catsSyntaxValidatedIdBinCompat0}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.DefaultDelegatedRewardsConfigProvider
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator.{
  InsufficientRegistrationBalance,
  InvalidSigned,
  NodeNotInSeedList
}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.SignedValidator.{InvalidSignatures, NotSignedExclusivelyByAddressOwner}
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import weaver.MutableIOSuite

object UpdateNodeParametersValidatorSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  def testUpdateNodeParameters(source: Address): UpdateNodeParameters = UpdateNodeParameters(
    source = source,
    delegatedStakeRewardParameters = DelegatedStakeRewardParameters(
      rewardFraction = RewardFraction(5_000_000)
    ),
    nodeMetadataParameters = NodeMetadataParameters(
      name = "name",
      description = "description"
    ),
    parent = UpdateNodeParametersReference.empty
  )

  test("should succeed when the node parameters are signed correctly and the reward value is at the lower bound") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      validTestUpdateNodeParameters = testUpdateNodeParameters(source)
      signedUpdateNodeParameters <- forAsyncHasher(validTestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(Valid(signedUpdateNodeParameters), result)
  }

  test("should succeed when the node parameters are signed correctly and the reward value is at the upper bound") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      validTestUpdateNodeParameters = testUpdateNodeParameters(source).copy(delegatedStakeRewardParameters =
        DelegatedStakeRewardParameters(
          rewardFraction = RewardFraction(10_000_000)
        )
      )
      signedUpdateNodeParameters <- forAsyncHasher(validTestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(Valid(signedUpdateNodeParameters), result)
  }

  test("should succeed when the node parameters are signed correctly and the reward value is within the allowed range") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      validTestUpdateNodeParameters = testUpdateNodeParameters(source).copy(delegatedStakeRewardParameters =
        DelegatedStakeRewardParameters(
          rewardFraction = RewardFraction(8_000_000)
        )
      )
      signedUpdateNodeParameters <- forAsyncHasher(validTestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(Valid(signedUpdateNodeParameters), result)
  }

  test("should fail when the node is absent in seed list") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      validTestUpdateNodeParameters = testUpdateNodeParameters(source)
      signedUpdateNodeParameters <- forAsyncHasher(validTestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set.empty)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(UpdateNodeParametersValidator.NodeNotInSeedList(peerId).invalidNec, result)
  }

  test("should fail when the signature is wrong") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair1.getPublic.toAddress
      peerId1 = PeerId.fromId(keyPair1.getPublic.toId)
      peerId2 = PeerId.fromId(keyPair2.getPublic.toId)
      signedUpdateNodeParameters <- forAsyncHasher(testUpdateNodeParameters(source), keyPair1).map(signed =>
        signed.copy(proofs =
          NonEmptySet.fromSetUnsafe(
            SortedSet(signed.proofs.head.copy(id = keyPair2.getPublic.toId))
          )
        )
      )
      validator = mkValidator(Set(peerId1, peerId2))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield
      expect.all(result match {
        case Invalid(errors) =>
          errors.exists {
            case InvalidSigned(InvalidSignatures(signedUpdateNodeParameters.proofs)) => true
            case InvalidSigned(NotSignedExclusivelyByAddressOwner)                   => true
            case _                                                                   => false
          }
        case _ => false
      })
  }

  test("should fail when the reward value is too high") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      invalidTtestUpdateNodeParameters = testUpdateNodeParameters(source).copy(delegatedStakeRewardParameters =
        DelegatedStakeRewardParameters(
          rewardFraction = RewardFraction(10_000_001)
        )
      )
      signedUpdateNodeParameters <- forAsyncHasher(invalidTtestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(UpdateNodeParametersValidator.InvalidRewardValue(10_000_001).invalidNec, result)
  }

  test("should fail when the reward value is too low") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      invalidTtestUpdateNodeParameters = testUpdateNodeParameters(source).copy(delegatedStakeRewardParameters =
        DelegatedStakeRewardParameters(
          rewardFraction = RewardFraction(4_999_999)
        )
      )
      signedUpdateNodeParameters <- forAsyncHasher(invalidTtestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(UpdateNodeParametersValidator.InvalidRewardValue(4_999_999).invalidNec, result)
  }

  test("should succeed when lastRef is empty and the global context is empty") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      validTestUpdateNodeParameters = testUpdateNodeParameters(source)
      signedUpdateNodeParameters <- forAsyncHasher(validTestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(Valid(signedUpdateNodeParameters), result)
  }

  test("should fail when lastRef is not empty and the global context is empty") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      lastRef = UpdateNodeParametersReference(UpdateNodeParametersReference.empty.ordinal.next, UpdateNodeParametersReference.empty.hash)
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      invalidTestUpdateNodeParameters = testUpdateNodeParameters(source).copy(parent = lastRef)
      signedUpdateNodeParameters <- forAsyncHasher(invalidTestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(UpdateNodeParametersValidator.InvalidParent(lastRef).invalidNec, result)
  }

  test("should succeed when lastRef is not empty and the global context contains the parent") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      parent = testUpdateNodeParameters(source)
      signedParent <- forAsyncHasher(parent, keyPair)
      context = mkGlobalContext(SortedMap(signedParent.proofs.head.id -> (signedParent, SnapshotOrdinal.MinValue)))
      lastRef <- h.hash(parent).map(hash => UpdateNodeParametersReference(parent.ordinal, hash))
      validTestUpdateNodeParameters = testUpdateNodeParameters(source).copy(parent = lastRef)
      signedUpdateNodeParameters <- forAsyncHasher(validTestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, context, legacyEpoch)
    } yield expect.same(Valid(signedUpdateNodeParameters), result)
  }

  test("should fail when lastRef is not empty and the global context does not contain the parent") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      parent = testUpdateNodeParameters(source)
      signedParent <- forAsyncHasher(parent, keyPair)
      context = mkGlobalContext(SortedMap(signedParent.proofs.head.id -> (signedParent, SnapshotOrdinal.MinValue)))
      lastRef <- h.hash(parent).map(hash => UpdateNodeParametersReference(parent.ordinal.next, hash))
      invalidTestUpdateNodeParameters = testUpdateNodeParameters(source).copy(parent = lastRef)
      signedUpdateNodeParameters <- forAsyncHasher(invalidTestUpdateNodeParameters, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, context, legacyEpoch)
    } yield expect.same(UpdateNodeParametersValidator.InvalidParent(lastRef).invalidNec, result)
  }

  test("should fail when the metadata name is greater than config 'maxMetadataFieldsChars'") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      validTestUpdateNodeParameters = testUpdateNodeParameters(source)
      invalidName =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Vivamus efficitur, nisl eu volutpat feugiat, urna sapien pretium justo, ac mollis nulla."
      updatedParametersInvalidName = validTestUpdateNodeParameters.copy(nodeMetadataParameters =
        NodeMetadataParameters(
          name = invalidName,
          description = "VALID DESCRIPTION"
        )
      )
      signedUpdateNodeParameters <- forAsyncHasher(updatedParametersInvalidName, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(UpdateNodeParametersValidator.TooLargeName(invalidName, 140).invalidNec, result)
  }

  test("should fail when the metadata description is greater than config 'maxMetadataFieldsChars'") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      validTestUpdateNodeParameters = testUpdateNodeParameters(source)
      invalidDescription =
        "Lorem ipsum dolor sit amet, consectetur adipiscing elit. Vivamus efficitur, nisl eu volutpat feugiat, urna sapien pretium justo, ac mollis nulla."
      updatedParametersInvalidName = validTestUpdateNodeParameters.copy(nodeMetadataParameters =
        NodeMetadataParameters(
          name = "Valid Name",
          description = invalidDescription
        )
      )
      signedUpdateNodeParameters <- forAsyncHasher(updatedParametersInvalidName, keyPair)
      validator = mkValidator(Set(peerId))
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), legacyEpoch)
    } yield expect.same(UpdateNodeParametersValidator.TooLargeDescription(invalidDescription, 140).invalidNec, result)
  }

  private val legacyEpoch = EpochProgress.MinValue

  test("after the metanomics gate a node outside the seedlist can register with the registration balance") { res =>
    implicit val (json, h, sp) = res
    val minimum = DefaultDelegatedRewardsConfigProvider.minRegistrationBalance(legacyEpoch)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      signed <- forAsyncHasher(testUpdateNodeParameters(source), keyPair)
      funded = mkGlobalContext().copy(balances = SortedMap(source -> Balance(minimum.value)))
      legacy <- mkValidator(Set.empty).validate(signed, funded, legacyEpoch)
      active <- mkValidator(Set.empty, metanomicsV2Active = true).validate(signed, funded, legacyEpoch)
    } yield expect.same(NodeNotInSeedList(peerId).invalidNec, legacy).and(expect.same(Valid(signed), active))
  }

  test("after the metanomics gate a seedlist node below the registration balance cannot register") { res =>
    implicit val (json, h, sp) = res
    val minimum = DefaultDelegatedRewardsConfigProvider.minRegistrationBalance(legacyEpoch)
    val short = Balance(NonNegLong.unsafeFrom(minimum.value.value - 1))

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      signed <- forAsyncHasher(testUpdateNodeParameters(source), keyPair)
      underfunded = mkGlobalContext().copy(balances = SortedMap(source -> short))
      legacy <- mkValidator(Set(peerId)).validate(signed, underfunded, legacyEpoch)
      active <- mkValidator(Set(peerId), metanomicsV2Active = true).validate(signed, underfunded, legacyEpoch)
      unfunded <- mkValidator(Set(peerId), metanomicsV2Active = true).validate(signed, mkGlobalContext(), legacyEpoch)
    } yield
      expect
        .same(Valid(signed), legacy)
        .and(expect.same(InsufficientRegistrationBalance(source, short, minimum).invalidNec, active))
        .and(expect.same(InsufficientRegistrationBalance(source, Balance.empty, minimum).invalidNec, unfunded))
  }

  test("a node holding 250K DAG can register until the Nov 14 epoch and needs 1M DAG from it") { res =>
    implicit val (json, h, sp) = res
    val raise = DefaultDelegatedRewardsConfigProvider.mainnetRegistrationBalanceRaiseEpoch
    val beforeRaise = EpochProgress(NonNegLong.unsafeFrom(raise.value.value - 1L))
    val quarterMillion = Balance(NonNegLong.unsafeFrom(250_000_00000000L))

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      signed <- forAsyncHasher(testUpdateNodeParameters(source), keyPair)
      funded = mkGlobalContext().copy(balances = SortedMap(source -> quarterMillion))
      validator = mkValidator(Set.empty, metanomicsV2Active = true)
      before <- validator.validate(signed, funded, beforeRaise)
      at <- validator.validate(signed, funded, raise)
    } yield
      expect
        .same(Valid(signed), before)
        .and(
          expect.same(
            InsufficientRegistrationBalance(source, quarterMillion, Amount(NonNegLong.unsafeFrom(1_000_000_00000000L))).invalidNec,
            at
          )
        )
  }

  private def mkValidator(peersList: Set[PeerId], metanomicsV2Active: Boolean = false)(
    implicit S: SecurityProvider[IO],
    J: JsonSerializer[IO],
    H: Hasher[IO]
  ): UpdateNodeParametersValidator[IO] = {
    val seedList = peersList.map(peerId => SeedlistEntry(peerId, None, None, None, None))
    val signedValidator = SignedValidator.make[IO]
    UpdateNodeParametersValidator.make[IO](
      signedValidator,
      RewardFraction(5_000_000),
      RewardFraction(10_000_000),
      PosInt(140),
      seedList.some,
      _ => metanomicsV2Active
    )
  }

  def mkGlobalContext(updateNodeParameters: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)] = SortedMap.empty) =
    GlobalSnapshotInfo.empty.copy(updateNodeParameters = Some(updateNodeParameters))

}
