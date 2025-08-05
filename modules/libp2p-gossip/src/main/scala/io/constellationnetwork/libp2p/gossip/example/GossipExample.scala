package io.constellationnetwork.libp2p.gossip.example

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.libp2p.gossip._

import io.circe.{Decoder, Encoder}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object GossipExample extends IOApp {

  // Example rumor types
  case class ChatMessage(sender: String, message: String, timestamp: Long)
  case class NodeInfo(peerId: String, version: String, uptime: Long)

  object ChatMessage {
    implicit val encoder: Encoder[ChatMessage] = io.circe.generic.semiauto.deriveEncoder
    implicit val decoder: Decoder[ChatMessage] = io.circe.generic.semiauto.deriveDecoder
  }

  object NodeInfo {
    implicit val encoder: Encoder[NodeInfo] = io.circe.generic.semiauto.deriveEncoder
    implicit val decoder: Decoder[NodeInfo] = io.circe.generic.semiauto.deriveDecoder
  }

  def run(args: List[String]): IO[ExitCode] = {
    val logger = Slf4jLogger.getLogger[IO]

    for {
      _ <- logger.info("Starting LibP2P Gossip Example")

      // Create protocol configuration
      protocolConfig = LibP2PGossipProtocol.Config(
        listenPort = 4001,
        gossipInterval = 1.second,
        maxPeers = 10
      )

      // Create service configuration
      serviceConfig = LibP2PGossipService.Config(
        maxRumorsInMemory = 100,
        gossipInterval = 2.seconds,
        maxPeers = 10
      )

      // Start the example
      _ <- runExample(protocolConfig, serviceConfig)

      _ <- logger.info("LibP2P Gossip Example completed")
    } yield ExitCode.Success
  }

  def runExample(
    protocolConfig: LibP2PGossipProtocol.Config,
    serviceConfig: LibP2PGossipService.Config
  ): IO[Unit] = {
    val logger = Slf4jLogger.getLogger[IO]

    for {
      // Create protocol
      protocol <- LibP2PGossipProtocol.make[IO](protocolConfig)
      _ <- protocol.start

      // Create storage
      storage <- LibP2PRumorStorage.make[IO](LibP2PRumorStorage.Config())

      // Create service
      service <- LibP2PGossipService.make[IO](serviceConfig)

      // Get local peer information
      peerId <- protocol.getPeerId
      addresses <- protocol.getListenAddresses
      _ <- logger.info(s"Local peer ID: $peerId")
      _ <- logger.info(s"Listening on: ${addresses.mkString(", ")}")

      // Spread some rumors
      _ <- service.spread(ChatMessage("Alice", "Hello, world!", System.currentTimeMillis()))
      _ <- service.spreadCommon(NodeInfo(peerId, "1.0.0", System.currentTimeMillis()))

      // Wait a bit for rumors to propagate
      _ <- IO.sleep(5.seconds)

      // Stop the protocol
      _ <- protocol.stop
    } yield ()
  }
}
