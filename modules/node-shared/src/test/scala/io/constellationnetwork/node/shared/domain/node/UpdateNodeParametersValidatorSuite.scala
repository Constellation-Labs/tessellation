package io.constellationnetwork.node.shared.domain.node

import cats.data.NonEmptySet
import cats.data.Validated.{Invalid, Valid}
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.{catsSyntaxOptionId, catsSyntaxValidatedIdBinCompat0}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator.InvalidSigned
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.SignedValidator.{InvalidSignatures, NotSignedExclusivelyByAddressOwner}
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}
import io.constellationnetwork.shared.sharedKryoRegistrar

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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, mkGlobalContext())
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
      result <- validator.validate(signedUpdateNodeParameters, context)
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
      result <- validator.validate(signedUpdateNodeParameters, context)
    } yield expect.same(UpdateNodeParametersValidator.InvalidParent(lastRef).invalidNec, result)
  }

  private def mkValidator(peersList: Set[PeerId])(
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
      seedList.some
    )
  }

  def mkGlobalContext(updateNodeParameters: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)] = SortedMap.empty) =
    GlobalSnapshotInfo.empty.copy(updateNodeParameters = Some(updateNodeParameters))

}
