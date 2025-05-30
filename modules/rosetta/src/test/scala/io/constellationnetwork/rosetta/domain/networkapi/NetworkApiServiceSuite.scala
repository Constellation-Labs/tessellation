package io.constellationnetwork.rosetta.domain.networkapi

import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.option._

import io.constellationnetwork.BuildInfo
import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.rosetta.domain.NetworkIdentifier
import io.constellationnetwork.rosetta.domain.error._
import io.constellationnetwork.rosetta.domain.network.{BlockchainId, NetworkEnvironment}
import io.constellationnetwork.rosetta.domain.networkapi.NetworkApiService.LastSnapshotInfo
import io.constellationnetwork.rosetta.domain.networkapi.model.options
import io.constellationnetwork.rosetta.domain.networkapi.model.status._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generators._
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.timestamp.SnapshotTimestamp
import io.constellationnetwork.schema.{GlobalSnapshot, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.cats.{refTypeEq, refTypeOrder, refTypeShow}
import org.scalacheck.{Arbitrary, Gen}
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object NetworkApiServiceSuite extends MutableIOSuite with Checkers {
  type Res = (SecurityProvider[IO], Hasher[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (sp, h)

  private def errorResult[A]: IO[A] = IO.raiseError(new Exception("unexpected call"))
  def makeNetworkApiService(
    lastSnapshotInfo: IO[Option[LastSnapshotInfo]] = errorResult,
    genesisSnapshot: IO[(SnapshotOrdinal, Hash)] = errorResult,
    nodeState: IO[NodeState] = errorResult
  ): NetworkApiService[IO] =
    NetworkApiService.make[IO](lastSnapshotInfo, genesisSnapshot, nodeState)

  pureTest("list returns mainnet identifier for that AppEnvironment") {
    val netId = NetworkIdentifier(BlockchainId.dag, NetworkEnvironment.Mainnet, none)
    val expected = List(netId)
    val actual = makeNetworkApiService().list(AppEnvironment.Mainnet)
    expect.eql(expected, actual)
  }

  pureTest("list returns testnet identifier for that AppEnvironment") {
    val netId = NetworkIdentifier(BlockchainId.dag, NetworkEnvironment.Testnet, none)
    val expected = List(netId)
    val actual = makeNetworkApiService().list(AppEnvironment.Testnet)
    expect.eql(expected, actual)
  }

  pureTest("list returns integrationnet identifier for that AppEnvironment") {
    val netId = NetworkIdentifier(BlockchainId.dag, NetworkEnvironment.Integrationnet, none)
    val expected = List(netId)
    val actual = makeNetworkApiService().list(AppEnvironment.Integrationnet)
    expect.eql(expected, actual)
  }

  pureTest("list returns empty list for Dev AppEnvironment") {
    val expected = List()
    val actual = makeNetworkApiService().list(AppEnvironment.Dev)
    expect.eql(expected, actual)
  }

  pureTest("options returns default version/allow") {
    val expected = options
      .NetworkApiOptions(options.Version("1.4.14", BuildInfo.version), options.Allow.default)

    val actual = makeNetworkApiService().options
    expect.eql(expected, actual)
  }

  test("status returns LatestSnapshotNotFound when no snapshots found") { _ =>
    val expected = LatestSnapshotNotFound.asLeft[NetworkStatusResponse]
    makeNetworkApiService(none.pure[IO]).status.value
      .map(expect.eql(expected, _))
  }

  test("status returns NetworkStatusResponse when snapshot found") { res =>
    implicit val (sp, h) = res

    val gen = for {
      genesisOrdinal <- snapshotOrdinalGen
      genesisHash <- Arbitrary.arbitrary[Hash]
      nodeState <- Gen.oneOf(NodeState.values)
    } yield (genesisOrdinal, genesisHash, nodeState)

    forall(gen) {
      case (genesisOrdinal, genesisHash, nodeState) =>
        for {
          keyPair <- KeyPairGenerator.makeKeyPair
          genesis <- forAsyncHasher(
            GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue),
            keyPair
          ).flatMap(_.toHashed[IO])

          incrementalSnapshot <- GlobalSnapshot
            .mkFirstIncrementalSnapshot[IO](genesis)
            .flatMap(forAsyncHasher(_, keyPair))
            .flatMap(_.toHashed[IO])

          timestamp = SnapshotTimestamp(System.currentTimeMillis())

          networkApiService = makeNetworkApiService(
            (incrementalSnapshot, timestamp).some.pure[IO],
            (genesisOrdinal, genesisHash).pure[IO],
            nodeState.pure[IO]
          )

          genesisBlockId = BlockIdentifier(genesisOrdinal, Hex(genesisHash.value))
          oldestBlockId = genesisBlockId
          currentBlockId = BlockIdentifier(incrementalSnapshot.ordinal, Hex(incrementalSnapshot.hash.value))

          expectedStage: Stage = nodeState match {
            case NodeState.Ready => Stage.Ready
            case _               => Stage.NotReady
          }

          expected = NetworkStatusResponse(
            currentBlockIdentifier = currentBlockId,
            currentBlockTimestamp = timestamp.millisSinceEpoch,
            genesisBlockIdentifier = genesisBlockId,
            oldestBlockIdentifier = oldestBlockId,
            syncStatus = SyncStatus(
              currentIndex = incrementalSnapshot.ordinal.value.value,
              targetIndex = incrementalSnapshot.ordinal.value.value,
              stage = expectedStage,
              synced = expectedStage == Stage.Ready
            ),
            peers = incrementalSnapshot.nextFacilitators.map(RosettaPeerId(_)).toList
          ).asRight[NetworkApiError]

          actual <- networkApiService.status.value

        } yield expect.eql(expected, actual)
    }
  }
}
