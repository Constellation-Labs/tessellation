package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.{Kleisli, NonEmptySet}
import cats.effect.IO
import cats.effect.kernel.Deferred
import cats.effect.std.Supervisor

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage => ClusterStorageAlg}
import io.constellationnetwork.node.shared.http.p2p.PeerResponse.PeerResponse
import io.constellationnetwork.node.shared.infrastructure.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.message.{GetConsensusOutcomeRequest, RegistrationResponse}
import io.constellationnetwork.node.shared.infrastructure.metrics.{Metrics, NoOpMetrics}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.cluster.{ClusterId, ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.gossip.{CommonRumorRaw, ContentType, RumorRaw}
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import com.comcast.ip4s.IpLiteralSyntax
import eu.timepit.refined.auto._
import io.circe.Json
import weaver.SimpleIOSuite

object ConsensusDirectSenderSuite extends SimpleIOSuite {

  private implicit val metrics: Metrics[IO] = NoOpMetrics.make

  private val peerId = PeerId(Hex("01"))
  private val peer = Peer(
    peerId,
    host"127.0.0.1",
    port"9000",
    port"9001",
    ClusterSessionToken(Generation.MinValue),
    SessionToken(Generation.MinValue),
    NodeState.Ready,
    Responsive,
    Hash.empty
  )

  private val rumor: Hashed[RumorRaw] = {
    val proof = SignatureProof(Id(Hex("01")), Signature(Hex("00")))
    val raw: RumorRaw = CommonRumorRaw(Json.Null, ContentType("consensus-direct-sender-test"))
    Hashed(Signed(raw, NonEmptySet.of(proof)), Hash.empty, ProofsHash(Hash.empty.value))
  }

  private def client(push: P2PContext => IO[Boolean]): ConsensusClient[IO, Unit, Unit] =
    new ConsensusClient[IO, Unit, Unit] {
      private def unused[A](name: String): PeerResponse[IO, A] =
        Kleisli(_ => IO.raiseError(new AssertionError(s"unexpected client call: $name")))

      def getRegistration: PeerResponse[IO, RegistrationResponse[Unit]] = unused("getRegistration")

      def getLatestConsensusOutcome: PeerResponse[IO, Option[Unit]] = unused("getLatestConsensusOutcome")

      def getSpecificConsensusOutcome(request: GetConsensusOutcomeRequest[Unit]): PeerResponse[IO, Option[Unit]] =
        unused("getSpecificConsensusOutcome")

      def pushRumor(signedRumor: Signed[RumorRaw]): PeerResponse[IO, Boolean] = Kleisli(push)
    }

  private def clusterStorage: IO[ClusterStorageAlg[IO]] =
    ClusterStorage.make[IO](ClusterId("8d07c061-d42f-4d9c-9efc-37e0d1ee73e7"), Map(peer.id -> peer))

  test("callback returns after enqueue without awaiting a blocked peer HTTP call") {
    Supervisor[IO].use { implicit supervisor =>
      for {
        storage <- clusterStorage
        pushStarted <- Deferred[IO, Unit]
        releasePush <- Deferred[IO, Unit]
        pushClient = client(_ => pushStarted.complete(()).attempt.void >> releasePush.get.as(true))
        directPush <- ConsensusDirectSender.makeDirectPushFn[IO, Unit, Unit](storage, pushClient)
        started <- IO.monotonic
        _ <- directPush(rumor, Set(peerId))
        elapsed <- IO.monotonic.map(_ - started)
        _ <- pushStarted.get.timeout(1.second)
      } yield
        expect(
          elapsed < 250.millis,
          s"direct-push callback must only enqueue; it waited ${elapsed.toMillis}ms for peer delivery"
        )
    }
  }

  test("queued job is delivered to a responsive target") {
    Supervisor[IO].use { implicit supervisor =>
      for {
        storage <- clusterStorage
        delivered <- Deferred[IO, PeerId]
        pushClient = client(ctx => delivered.complete(ctx.id).attempt.as(true))
        directPush <- ConsensusDirectSender.makeDirectPushFn[IO, Unit, Unit](storage, pushClient)
        _ <- directPush(rumor, Set(peerId))
        actual <- delivered.get.timeout(1.second)
      } yield expect.same(peerId, actual)
    }
  }
}
