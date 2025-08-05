package io.constellationnetwork.libp2p.gossip

import cats.data.Chain
import cats.effect.Async
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class LibP2PGossipClient[F[_]: Async](
  protocol: LibP2PGossipProtocol[F],
  storage: LibP2PRumorStorage[F],
  config: LibP2PGossipClient.Config
) {
  private val logger = Slf4jLogger.getLogger[F]

  def queryPeerRumors(ordinals: Map[PeerId, Ordinal]): F[Chain[Signed[PeerRumorRaw]]] =
    for {
      _ <- logger.info(s"Querying peer rumors for ordinals: $ordinals")
      rumors <- ordinals.toList.traverse {
        case (peerId, ordinal) =>
          storage.getPeerRumorsFromCursor(peerId, ordinal)
      }
      result = Chain.fromSeq(rumors.flatMap(_.toList))
      _ <- logger.info(s"Retrieved ${result.size} peer rumors")
    } yield result

  def getInitialPeerRumors: F[Chain[Signed[PeerRumorRaw]]] =
    for {
      _ <- logger.info("Getting initial peer rumors")
      rumors <- storage.getPeerRumors(PeerId(io.constellationnetwork.security.hex.Hex("all")))
      _ <- logger.info(s"Retrieved ${rumors.size} initial peer rumors")
    } yield rumors

  def getCommonRumorOffer: F[Set[Hash]] =
    for {
      _ <- logger.info("Getting common rumor offer")
      hashes <- storage.getCommonRumorHashes
      _ <- logger.info(s"Retrieved ${hashes.size} common rumor hashes")
    } yield hashes

  def queryCommonRumors(hashes: Set[Hash]): F[Chain[Signed[CommonRumorRaw]]] =
    for {
      _ <- logger.info(s"Querying common rumors for hashes: $hashes")
      rumors <- storage.getCommonRumors(hashes)
      _ <- logger.info(s"Retrieved ${rumors.size} common rumors")
    } yield rumors

  def getInitialCommonRumorHashes: F[Set[Hash]] =
    for {
      _ <- logger.info("Getting initial common rumor hashes")
      hashes <- storage.getCommonRumorHashes
      _ <- logger.info(s"Retrieved ${hashes.size} initial common rumor hashes")
    } yield hashes

  def connectToPeer(address: String): F[Unit] =
    for {
      _ <- logger.info(s"Connecting to peer at $address")
      _ <- protocol.connect(address)
      _ <- logger.info(s"Successfully connected to peer at $address")
    } yield ()

  def getLocalPeerId: F[String] =
    protocol.getPeerId

  def getListenAddresses: F[List[String]] =
    protocol.getListenAddresses
}

object LibP2PGossipClient {

  final case class Config(
    requestTimeout: FiniteDuration = 30.seconds,
    maxRetries: Int = 3,
    retryDelay: FiniteDuration = 1.second
  )

  def make[F[_]: Async](
    protocol: LibP2PGossipProtocol[F],
    storage: LibP2PRumorStorage[F],
    config: Config
  ): F[LibP2PGossipClient[F]] =
    Async[F].delay(new LibP2PGossipClient[F](protocol, storage, config))
}
