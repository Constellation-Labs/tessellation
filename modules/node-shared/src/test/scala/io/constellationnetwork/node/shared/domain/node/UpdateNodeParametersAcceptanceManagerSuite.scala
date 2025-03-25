package io.constellationnetwork.node.shared.domain.node

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.toTraverseOps

import scala.collection.immutable.SortedMap

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}
import io.constellationnetwork.shared.sharedKryoRegistrar

import weaver.MutableIOSuite

object UpdateNodeParametersAcceptanceManagerSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (j, h, sp)

  test("should properly sort valid and invalid node parameters") { res =>
    implicit val (json, h, sp) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      signedValidList <- validList(source).traverse(params => forAsyncHasher(params, keyPair))
      signedInvalidList <- invalidList(source).traverse(params => forAsyncHasher(params, keyPair))
      acceptanceManager = mkAcceptanceManager()
      acceptanceResult <- acceptanceManager.acceptUpdateNodeParameters(signedInvalidList ++ signedValidList, mkGlobalContext())
    } yield
      expect.same((signedValidList.reverse, signedInvalidList.reverse), (acceptanceResult.accepted, acceptanceResult.notAccepted.map(_._1)))
  }

  private def mkAcceptanceManager()(
    implicit S: SecurityProvider[IO],
    J: JsonSerializer[IO],
    H: Hasher[IO]
  ): UpdateNodeParametersAcceptanceManager[IO] = {
    val signedValidator = SignedValidator.make[IO]
    val updateNodeParametersValidator = UpdateNodeParametersValidator.make[IO](
      signedValidator,
      RewardFraction(5_000_000),
      RewardFraction(10_000_000)
    )
    UpdateNodeParametersAcceptanceManager.make[IO](updateNodeParametersValidator)
  }

  def mkGlobalContext(updateNodeParameters: SortedMap[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)] = SortedMap.empty) =
    GlobalSnapshotInfo.empty.copy(updateNodeParameters = Some(updateNodeParameters))

  def validTestUpdateNodeParameters1(source: Address): UpdateNodeParameters = UpdateNodeParameters(
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

  def validTestUpdateNodeParameters2(source: Address): UpdateNodeParameters = UpdateNodeParameters(
    source = source,
    delegatedStakeRewardParameters = DelegatedStakeRewardParameters(
      rewardFraction = RewardFraction(10_000_000)
    ),
    nodeMetadataParameters = NodeMetadataParameters(
      name = "name",
      description = "description"
    ),
    parent = UpdateNodeParametersReference.empty
  )

  def invalidTestUpdateNodeParameters1(source: Address): UpdateNodeParameters = UpdateNodeParameters(
    source = source,
    delegatedStakeRewardParameters = DelegatedStakeRewardParameters(
      rewardFraction = RewardFraction(0)
    ),
    nodeMetadataParameters = NodeMetadataParameters(
      name = "name",
      description = "description"
    ),
    parent = UpdateNodeParametersReference.empty
  )

  def invalidTestUpdateNodeParameters2(source: Address): UpdateNodeParameters = UpdateNodeParameters(
    source = source,
    delegatedStakeRewardParameters = DelegatedStakeRewardParameters(
      rewardFraction = RewardFraction(100_000_000)
    ),
    nodeMetadataParameters = NodeMetadataParameters(
      name = "name",
      description = "description"
    ),
    parent = UpdateNodeParametersReference.empty
  )

  def validList(source: Address) =
    List(validTestUpdateNodeParameters1(source), validTestUpdateNodeParameters2(source))

  def invalidList(source: Address) =
    List(invalidTestUpdateNodeParameters1(source), invalidTestUpdateNodeParameters2(source))
}
