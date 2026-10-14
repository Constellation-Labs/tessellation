package io.constellationnetwork.node.shared.domain.node

import cats.data.NonEmptySet
import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.DelegatedRewardsEra
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set.empty, DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId1, peerId2), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, context, EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, context, EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
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
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext(), EpochProgress.MinValue)
    } yield expect.same(UpdateNodeParametersValidator.TooLargeDescription(invalidDescription, 140).invalidNec, result)
  }

  private val activation = EpochProgress(NonNegLong(100L))
  private val era = DelegatedRewardsEra(activation, SortedMap.empty)
  private val minimum = DelegatedRewardsEra.MinimumRegistrationBalance.value.value

  test("activation selects legacy seedlist validation before A and funded registration at A and A+1") { res =>
    implicit val (json, h, sp) = res
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      signed <- forAsyncHasher(testUpdateNodeParameters(source), keyPair)
      context = mkGlobalContext().copy(balances = SortedMap(source -> Balance(NonNegLong.unsafeFrom(minimum))))
      validator = mkValidator(Set.empty, era)
      before <- validator.validate(signed, context, EpochProgress(NonNegLong(99L)))
      at <- validator.validate(signed, context, activation)
      after <- validator.validate(signed, context, EpochProgress(NonNegLong(101L)))
      replay <- validator.validate(signed, context, EpochProgress(NonNegLong(99L)))
    } yield
      expect
        .same(NodeNotInSeedList(peerId).invalidNec, before)
        .and(expect.same(Valid(signed), at))
        .and(expect.same(Valid(signed), after))
        .and(expect.same(before, replay))
  }

  test("funding threshold checks previous context balance including missing balance for whitelisted nodes") { res =>
    implicit val (json, h, sp) = res
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      signed <- forAsyncHasher(testUpdateNodeParameters(source), keyPair)
      validator = mkValidator(Set(peerId), era)
      checks <- List(None, Some(minimum - 1L), Some(minimum), Some(minimum + 1L)).traverse { value =>
        val balance = value.map(v => Balance(NonNegLong.unsafeFrom(v)))
        val context = mkGlobalContext().copy(balances = SortedMap.from(balance.map(source -> _)))
        validator.validate(signed, context, activation).map { result =>
          val expected = balance
            .filter(_.satisfiesCollateral(DelegatedRewardsEra.MinimumRegistrationBalance))
            .fold(
              InsufficientRegistrationBalance(source, balance.getOrElse(Balance.empty), DelegatedRewardsEra.MinimumRegistrationBalance)
                .invalidNec[Signed[UpdateNodeParameters]]
            )(_ => signed.validNec)
          expect.same(expected, result)
        }
      }
    } yield checks.reduce(_ and _)
  }

  test("new-era ignores requested commission without rewriting the signed bytes or parent reference") { res =>
    implicit val (json, h, sp) = res
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      validator = mkValidator(Set(peerId), era)
      context = mkGlobalContext().copy(balances = SortedMap(source -> Balance(NonNegLong.unsafeFrom(minimum))))
      checks <- List(0, 5000000, 10000000, 100000000).traverse { fraction =>
        for {
          signed <- forAsyncHasher(
            testUpdateNodeParameters(source).copy(
              delegatedStakeRewardParameters = DelegatedStakeRewardParameters(RewardFraction.unsafeFrom(fraction))
            ),
            keyPair
          )
          reference <- UpdateNodeParametersReference.of(signed)
          beforeHash <- h.hash(signed)
          result <- validator.validate(signed, context, activation)
          afterHash <- result.toOption.traverse(h.hash[Signed[UpdateNodeParameters]])
          afterReference <- result.toOption.traverse(UpdateNodeParametersReference.of[IO])
          legacy <- validator.validate(signed, context, EpochProgress(NonNegLong(99L)))
        } yield
          expect
            .same(Valid(signed), result)
            .and(expect.same(Some(beforeHash), afterHash))
            .and(expect.same(Some(reference), afterReference))
            .and(expect.same(fraction >= 5000000 && fraction <= 10000000, legacy.isValid))
      }
    } yield checks.reduce(_ and _)
  }

  test("new-era still requires one signature by the funded source wallet owner") { res =>
    implicit val (json, h, sp) = res
    for {
      owner <- KeyPairGenerator.makeKeyPair[IO]
      attacker <- KeyPairGenerator.makeKeyPair[IO]
      source = owner.getPublic.toAddress
      context = mkGlobalContext().copy(balances = SortedMap(source -> Balance(NonNegLong.unsafeFrom(minimum))))
      wrongSigner <- forAsyncHasher(testUpdateNodeParameters(source), attacker)
      ownerSigned <- forAsyncHasher(testUpdateNodeParameters(source), owner)
      multipleSigners <- ownerSigned.signAlsoWith(attacker)
      validator = mkValidator(Set.empty, era)
      wrongResult <- validator.validate(wrongSigner, context, activation)
      multipleResult <- validator.validate(multipleSigners, context, activation)
      altered = ownerSigned.copy(value = ownerSigned.value.copy(nodeMetadataParameters = NodeMetadataParameters("altered", "description")))
      alteredResult <- validator.validate(altered, context, activation)
    } yield
      expect(
        wrongResult.fold(_.exists(_ == InvalidSigned(NotSignedExclusivelyByAddressOwner)), _ => false),
        "funded source must own signature"
      ).and(expect(multipleResult.fold(_.exists(_ == InvalidProofsCount(2)), _ => false), "multiple signatures must be rejected"))
        .and(
          expect(
            alteredResult.fold(_.exists { case InvalidSigned(_: InvalidSignatures) => true; case _ => false }, _ => false),
            "tampered data must fail signature verification"
          )
        )
  }

  test("new-era edits keep parent and metadata checks and recheck balance") { res =>
    implicit val (json, h, sp) = res
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      parent <- forAsyncHasher(testUpdateNodeParameters(source), keyPair)
      reference <- UpdateNodeParametersReference.of(parent)
      context = mkGlobalContext(SortedMap(parent.proofs.head.id -> (parent, SnapshotOrdinal.unsafeApply(99L))))
        .copy(balances = SortedMap(source -> Balance(NonNegLong.unsafeFrom(minimum))))
      update <- forAsyncHasher(testUpdateNodeParameters(source).copy(parent = reference), keyPair)
      invalidMetadata <- forAsyncHasher(
        update.value.copy(nodeMetadataParameters = NodeMetadataParameters("x" * 141, "description")),
        keyPair
      )
      validator = mkValidator(Set.empty, era)
      valid <- validator.validate(update, context, activation)
      stale <- validator.validate(parent, context, activation)
      metadata <- validator.validate(invalidMetadata, context, activation)
      insufficient <- validator.validate(update, context.copy(balances = SortedMap.empty), activation)
    } yield
      expect
        .same(Valid(update), valid)
        .and(expect.same(InvalidParent(UpdateNodeParametersReference.empty).invalidNec, stale))
        .and(expect.same(TooLargeName("x" * 141, 140).invalidNec, metadata))
        .and(
          expect.same(
            InsufficientRegistrationBalance(source, Balance.empty, DelegatedRewardsEra.MinimumRegistrationBalance).invalidNec,
            insufficient
          )
        )
  }

  test("disabled era keeps legacy rules even at the maximum epoch") { res =>
    implicit val (json, h, sp) = res
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      signed <- forAsyncHasher(testUpdateNodeParameters(source), keyPair)
      validator = mkValidator(Set(peerId), DelegatedRewardsEra.Disabled)
      result <- validator.validate(signed, mkGlobalContext(), EpochProgress.MaxValue)
    } yield expect.same(Valid(signed), result)
  }

  private def mkValidator(peersList: Set[PeerId], era: DelegatedRewardsEra)(
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
      era
    )
  }

  def mkGlobalContext(updateNodeParameters: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)] = SortedMap.empty) =
    GlobalSnapshotInfo.empty.copy(updateNodeParameters = Some(updateNodeParameters))

}
