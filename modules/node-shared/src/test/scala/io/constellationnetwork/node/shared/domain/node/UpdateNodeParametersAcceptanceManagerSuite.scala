package io.constellationnetwork.node.shared.domain.node

import cats.effect.IO
import cats.effect.kernel.Resource
import cats.implicits.{catsSyntaxOptionId, toTraverseOps}

import scala.collection.immutable.SortedMap

import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.DelegatedRewardsEra
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.types.numeric.PosInt
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
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      signedValidList <- validList(source).traverse(params => forAsyncHasher(params, keyPair))
      signedInvalidList <- invalidList(source).traverse(params => forAsyncHasher(params, keyPair))
      acceptanceManager = mkAcceptanceManager(Set(peerId), DelegatedRewardsEra.Disabled)
      acceptanceResult <- acceptanceManager.acceptUpdateNodeParameters(
        signedInvalidList ++ signedValidList,
        mkGlobalContext(),
        SnapshotOrdinal.MinValue
      )
    } yield
      expect.same((signedValidList.reverse, signedInvalidList.reverse), (acceptanceResult.accepted, acceptanceResult.notAccepted.map(_._1)))
  }

  test("acceptance and historical replay use the supplied artifact ordinal and preserve signed registrations") { res =>
    implicit val (json, h, sp) = res
    val activation = SnapshotOrdinal.unsafeApply(100L)
    val era = DelegatedRewardsEra(activation, SortedMap.empty)
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      source = keyPair.getPublic.toAddress
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      // Codec-valid 0% was rejected by historical range validation, but must remain signed as submitted at A.
      signed <- forAsyncHasher(invalidTestUpdateNodeParameters1(source), keyPair)
      historical <- forAsyncHasher(validTestUpdateNodeParameters1(source), keyPair)
      context = mkGlobalContext().copy(balances = SortedMap(source -> Balance(DelegatedRewardsEra.MinimumRegistrationBalance.value)))
      manager = mkAcceptanceManager(Set(peerId), era)
      before <- manager.acceptUpdateNodeParameters(List(signed, historical), context, SnapshotOrdinal.unsafeApply(99L))
      at <- manager.acceptUpdateNodeParameters(List(signed, historical), context, activation)
      after <- manager.acceptUpdateNodeParameters(List(signed, historical), context, SnapshotOrdinal.unsafeApply(101L))
      replay <- manager.acceptUpdateNodeParameters(List(signed, historical), context, SnapshotOrdinal.unsafeApply(99L))
      inputHash <- h.hash(List(historical, signed))
      acceptedHash <- h.hash(at.accepted)
    } yield
      expect
        .same(List(historical), before.accepted)
        .and(expect.same(List(signed), before.notAccepted.map(_._1)))
        .and(expect.same(List(historical, signed), at.accepted))
        .and(expect.same(List.empty, at.notAccepted))
        .and(expect.same(at, after))
        .and(expect.same(before, replay))
        .and(expect.same(inputHash, acceptedHash))
  }

  private def mkAcceptanceManager(peersList: Set[PeerId], era: DelegatedRewardsEra)(
    implicit S: SecurityProvider[IO],
    J: JsonSerializer[IO],
    H: Hasher[IO]
  ): UpdateNodeParametersAcceptanceManager[IO] = {
    val signedValidator = SignedValidator.make[IO]
    val seedList = peersList.map(peerId => SeedlistEntry(peerId, None, None, None, None))
    val updateNodeParametersValidator = UpdateNodeParametersValidator.make[IO](
      signedValidator,
      RewardFraction(5_000_000),
      RewardFraction(10_000_000),
      PosInt(140),
      seedList.some,
      era
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
