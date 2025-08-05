package io.constellationnetwork.libp2p.gossip

import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import io.libp2p.core.Host
import io.libp2p.core.dsl.HostBuilder
import io.libp2p.core.multiformats.Multiaddr
import io.libp2p.protocol.Ping
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class LibP2PGossipProtocol[F[_]: Async](
  host: Host,
  config: LibP2PGossipProtocol.Config
) {
  private val logger = Slf4jLogger.getLogger[F]

  def start: F[Unit] =
    for {
      _ <- Async[F].fromCompletableFuture(Async[F].delay(host.start()))
      _ <- logger.info(s"LibP2P gossip protocol started on ${host.listenAddresses()}")
    } yield ()

  def stop: F[Unit] =
    for {
      _ <- Async[F].fromCompletableFuture(Async[F].delay(host.stop()))
      _ <- logger.info("LibP2P gossip protocol stopped")
    } yield ()

  def connect(address: String): F[Unit] =
    for {
      _ <- logger.info(s"Connecting to peer at $address")
      _ <- Async[F].fromCompletableFuture(Async[F].delay(host.getNetwork.connect(Multiaddr.fromString(address))))
    } yield ()

  def getPeerId: F[String] = Async[F].delay(host.getPeerId.toString)

  def getListenAddresses: F[List[String]] = Async[F].delay(host.listenAddresses().asScala.map(_.toString).toList)
}

object LibP2PGossipProtocol {

  final case class Config(
    listenPort: Int,
    gossipInterval: FiniteDuration,
    maxPeers: Int,
    enableMetrics: Boolean = false,
    requestTimeout: FiniteDuration = 30.seconds
  )

  def make[F[_]: Async](config: Config): F[LibP2PGossipProtocol[F]] =
    for {
      host <- Async[F].delay {
        new HostBuilder()
          .protocol(new Ping())
          .listen(s"/ip4/0.0.0.0/tcp/${config.listenPort}")
          .build()
      }
    } yield new LibP2PGossipProtocol[F](host, config)
}
