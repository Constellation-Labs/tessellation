package io.constellationnetwork.libp2p.gossip

import java.security.KeyPair

import cats.effect.{IO, Resource}

import scala.concurrent.duration._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

import io.circe.{Decoder, Encoder}
import weaver.MutableIOSuite

object LibP2PGossipSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO], KeyPair)

  def sharedResource: Resource[IO, Res] = for {
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
    kp <- KeyPairGenerator.makeKeyPair[IO].asResource
  } yield (j, h, sp, kp)

  // Test rumor types
  case class TestMessage(content: String, timestamp: Long)
  case class TestNodeInfo(peerId: String, version: String)

  object TestMessage {
    implicit val encoder: Encoder[TestMessage] = io.circe.generic.semiauto.deriveEncoder
    implicit val decoder: Decoder[TestMessage] = io.circe.generic.semiauto.deriveDecoder
  }

  object TestNodeInfo {
    implicit val encoder: Encoder[TestNodeInfo] = io.circe.generic.semiauto.deriveEncoder
    implicit val decoder: Decoder[TestNodeInfo] = io.circe.generic.semiauto.deriveDecoder
  }

  test("start and stop correctly") { _ =>
    val config = LibP2PGossipProtocol.Config(
      listenPort = 4001,
      gossipInterval = 1.second,
      maxPeers = 10
    )

    for {
      protocol <- LibP2PGossipProtocol.make[IO](config)
      _ <- protocol.start
      peerId <- protocol.getPeerId
      addresses <- protocol.getListenAddresses
      _ <- protocol.stop
    } yield expect.all(peerId.nonEmpty, addresses.nonEmpty)
  }

  test("store and retrieve peer rumors") { res =>
    implicit val (j, h, sp, kp) = res

    val config = LibP2PRumorStorage.Config()

    for {
      storage <- LibP2PRumorStorage.make[IO](config)

      // Create a test rumor
      peerId = PeerId(Hex("test-peer"))
      ordinal = Ordinal.MinValue
      contentType = ContentType.of[TestMessage]
      jsonContent = Encoder[TestMessage].apply(TestMessage("test", System.currentTimeMillis()))
      rumorRaw = PeerRumorRaw(peerId, ordinal, jsonContent, contentType)
      signedRumor <- Signed.forAsyncHasher(rumorRaw, kp)

      // Add rumor
      _ <- storage.addPeerRumor(signedRumor)

      // Retrieve rumors
      rumors <- storage.getPeerRumors(peerId)
      peerIds <- storage.getPeerIds

    } yield expect.all(rumors.size == 1, peerIds.contains(peerId))
  }

  test("store and retrieve common rumors") { res =>
    implicit val (j, h, sp, kp) = res
    val config = LibP2PRumorStorage.Config()

    for {
      storage <- LibP2PRumorStorage.make[IO](config)
      // Create a test rumor
      contentType = ContentType.of[TestNodeInfo]
      jsonContent = Encoder[TestNodeInfo].apply(TestNodeInfo("test-peer", "1.0.0"))
      rumorRaw = CommonRumorRaw(jsonContent, contentType)
      signedRumor <- Signed.forAsyncHasher(rumorRaw, kp)

      // Add rumor
      _ <- storage.addCommonRumor(signedRumor)

      // Retrieve hashes
      hashes <- storage.getCommonRumorHashes

    } yield expect.all(hashes.nonEmpty)
  }

  test("spread rumors") { _ =>
    val config = LibP2PGossipService.Config()

    for {
      service <- LibP2PGossipService.make[IO](config)
      // Spread a peer rumor
      _ <- service.spread(TestMessage("test peer rumor", System.currentTimeMillis()))

      // Spread a common rumor
      _ <- service.spreadCommon(TestNodeInfo("test-peer", "1.0.0"))

      // Verify no exceptions
    } yield success
  }

  test("query rumors") { _ =>
    val protocolConfig = LibP2PGossipProtocol.Config(
      listenPort = 4002,
      gossipInterval = 1.second,
      maxPeers = 10
    )

    val clientConfig = LibP2PGossipClient.Config()

    for {
      protocol <- LibP2PGossipProtocol.make[IO](protocolConfig)
      storage <- LibP2PRumorStorage.make[IO](LibP2PRumorStorage.Config())
      client <- LibP2PGossipClient.make[IO](protocol, storage, clientConfig)

      // Query peer rumors
      _ <- client.queryPeerRumors(Map.empty)

      // Query common rumors
      _ <- client.getCommonRumorOffer

      // Verify no exceptions
    } yield success
  }

  test("register and handle rumors") { _ =>
    val config = LibP2PGossipServer.Config()

    for {
      server <- LibP2PGossipServer.make[IO](config)
      // Register handlers
      _ <- server.registerRumorHandler[TestMessage] { message =>
        IO.println(s"Received test message: ${message.content}")
      }

      _ <- server.registerPeerRumorHandler[TestNodeInfo] { nodeInfo =>
        IO.println(s"Received node info from ${nodeInfo.origin}: ${nodeInfo.content}")
      }

      // Register request handler
      _ <- server.registerRequestHandler[String, String](
        "test_request",
        request => IO.pure(s"Response to: $request")
      )

      // Verify no exceptions
    } yield success
  }

  test("handle rumor endpoints") { _ =>
    val protocolConfig = LibP2PGossipProtocol.Config(
      listenPort = 4003,
      gossipInterval = 1.second,
      maxPeers = 10
    )

    val clientConfig = LibP2PGossipClient.Config()
    val serverConfig = LibP2PGossipServer.Config()
    val routesConfig = LibP2PGossipRoutes.Config()

    for {
      protocol <- LibP2PGossipProtocol.make[IO](protocolConfig)
      storage <- LibP2PRumorStorage.make[IO](LibP2PRumorStorage.Config())
      client <- LibP2PGossipClient.make[IO](protocol, storage, clientConfig)
      server <- LibP2PGossipServer.make[IO](serverConfig)
      routes <- LibP2PGossipRoutes.make[IO](client, server, routesConfig)

      // Test endpoints
      _ <- routes.getInitialPeerRumors
      _ <- routes.getCommonRumorOffer

      // Verify no exceptions
    } yield success
  }
}
