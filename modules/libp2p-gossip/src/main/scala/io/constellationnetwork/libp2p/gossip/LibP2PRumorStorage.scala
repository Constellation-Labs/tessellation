package io.constellationnetwork.libp2p.gossip

import cats.data.Chain
import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class LibP2PRumorStorage[F[_]: Async](
  peerRumors: Ref[F, Map[PeerId, Chain[Signed[PeerRumorRaw]]]],
  commonRumors: Ref[F, Map[Hash, Signed[CommonRumorRaw]]],
  config: LibP2PRumorStorage.Config
) {
  private val logger = Slf4jLogger.getLogger[F]

  def addPeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit] =
    for {
      _ <- peerRumors.update { current =>
        val peerId = rumor.value.origin
        val existing = current.getOrElse(peerId, Chain.empty)
        val updated = existing :+ rumor
        current.updated(peerId, updated)
      }
      _ <- logger.info(s"Added peer rumor for ${rumor.value.origin}")
    } yield ()

  def addCommonRumor(rumor: Signed[CommonRumorRaw]): F[Unit] =
    for {
      hash <- computeHash(rumor.value)
      _ <- commonRumors.update { current =>
        current.updated(hash, rumor)
      }
      _ <- logger.info(s"Added common rumor with hash $hash")
    } yield ()

  def getPeerRumors(peerId: PeerId): F[Chain[Signed[PeerRumorRaw]]] =
    peerRumors.get.map(_.getOrElse(peerId, Chain.empty))

  def getPeerRumorsFromCursor(peerId: PeerId, fromOrdinal: Ordinal): F[Chain[Signed[PeerRumorRaw]]] =
    getPeerRumors(peerId).map { rumors =>
      rumors.filter(_.value.ordinal >= fromOrdinal)
    }

  def getCommonRumors(hashes: Set[Hash]): F[Chain[Signed[CommonRumorRaw]]] =
    commonRumors.get.map { current =>
      Chain.fromSeq(hashes.flatMap(current.get).toList)
    }

  def getPeerIds: F[Set[PeerId]] =
    peerRumors.get.map(_.keySet)

  def getCommonRumorHashes: F[Set[Hash]] =
    commonRumors.get.map(_.keySet)

  def getLastPeerOrdinals: F[Map[PeerId, Ordinal]] =
    peerRumors.get.map { current =>
      current.flatMap {
        case (peerId, rumors) =>
          rumors.lastOption.map(rumor => peerId -> rumor.value.ordinal)
      }
    }

  private def computeHash(rumor: RumorRaw): F[Hash] =
    // This is a simplified hash computation - in practice you'd use proper hashing
    Async[F].delay(Hash("mock-hash"))
}

object LibP2PRumorStorage {

  final case class Config(
    maxPeerRumors: Int = 1000,
    maxCommonRumors: Int = 1000,
    cleanupInterval: FiniteDuration = 1.minute
  )

  def make[F[_]: Async](config: Config): F[LibP2PRumorStorage[F]] =
    for {
      peerRumors <- Ref.of[F, Map[PeerId, Chain[Signed[PeerRumorRaw]]]](Map.empty)
      commonRumors <- Ref.of[F, Map[Hash, Signed[CommonRumorRaw]]](Map.empty)
    } yield new LibP2PRumorStorage[F](peerRumors, commonRumors, config)
}
