package io.constellationnetwork.libp2p.gossip

import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

import io.circe.{Decoder, Encoder}
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class LibP2PGossipService[F[_]: Async](
  rumorStorage: LibP2PRumorStorage[F],
  handlers: Ref[F, Map[String, RumorHandler[F]]],
  config: LibP2PGossipService.Config
) {
  private val logger = Slf4jLogger.getLogger[F]

  def spread[A: TypeTag: Encoder](rumorContent: A): F[Unit] =
    for {
      _ <- logger.info(s"Spreading rumor of type ${implicitly[TypeTag[A]].tpe}")
      rumor <- createPeerRumor(rumorContent)
      _ <- rumorStorage.addPeerRumor(rumor)
      _ <- logger.info("Rumor spread successfully")
    } yield ()

  def spreadCommon[A: TypeTag: Encoder](rumorContent: A): F[Unit] =
    for {
      _ <- logger.info(s"Spreading common rumor of type ${implicitly[TypeTag[A]].tpe}")
      rumor <- createCommonRumor(rumorContent)
      _ <- rumorStorage.addCommonRumor(rumor)
      _ <- logger.info("Common rumor spread successfully")
    } yield ()

  def registerRumorHandler[A: TypeTag: Decoder](handler: A => F[Unit]): F[Unit] = {
    val contentType = ContentType.of[A]
    for {
      _ <- handlers.update { current =>
        val rumorHandler = RumorHandler.fromCommonRumorConsumer[F, A](handler)
        current.updated(contentType.value, rumorHandler)
      }
      _ <- logger.info(s"Registered rumor handler for type ${implicitly[TypeTag[A]].tpe}")
    } yield ()
  }

  def registerPeerRumorHandler[A: TypeTag: Decoder](handler: PeerRumor[A] => F[Unit]): F[Unit] = {
    val contentType = ContentType.of[A]
    for {
      _ <- handlers.update { current =>
        val rumorHandler = RumorHandler.fromPeerRumorConsumer[F, A]()(handler)
        current.updated(contentType.value, rumorHandler)
      }
      _ <- logger.info(s"Registered peer rumor handler for type ${implicitly[TypeTag[A]].tpe}")
    } yield ()
  }

  private def createPeerRumor[A: TypeTag: Encoder](content: A): F[Signed[PeerRumorRaw]] =
    // This is a simplified implementation - in a real scenario, you'd need proper signing
    for {
      peerId <- getLocalPeerId
      ordinal <- getNextOrdinal(peerId)
      contentType <- Async[F].delay(ContentType.of[A])
      jsonContent <- Async[F].delay(Encoder[A].apply(content))
      rumorRaw = PeerRumorRaw(peerId, ordinal, jsonContent, contentType)
      // For now, we'll create a mock signed rumor - in practice you'd use proper signing
      signedRumor = Signed(
        rumorRaw,
        cats.data.NonEmptySet.one(
          io.constellationnetwork.security.signature.signature.SignatureProof(
            io.constellationnetwork.schema.ID.Id(io.constellationnetwork.security.hex.Hex("mock-id")),
            io.constellationnetwork.security.signature.signature.Signature(io.constellationnetwork.security.hex.Hex("mock-sig"))
          )
        )
      )
    } yield signedRumor

  private def createCommonRumor[A: TypeTag: Encoder](content: A): F[Signed[CommonRumorRaw]] =
    for {
      contentType <- Async[F].delay(ContentType.of[A])
      jsonContent <- Async[F].delay(Encoder[A].apply(content))
      rumorRaw = CommonRumorRaw(jsonContent, contentType)
      // For now, we'll create a mock signed rumor - in practice you'd use proper signing
      signedRumor = Signed(
        rumorRaw,
        cats.data.NonEmptySet.one(
          io.constellationnetwork.security.signature.signature.SignatureProof(
            io.constellationnetwork.schema.ID.Id(io.constellationnetwork.security.hex.Hex("mock-id")),
            io.constellationnetwork.security.signature.signature.Signature(io.constellationnetwork.security.hex.Hex("mock-sig"))
          )
        )
      )
    } yield signedRumor

  private def getLocalPeerId: F[PeerId] =
    // This would be retrieved from the libp2p host
    Async[F].delay(PeerId(io.constellationnetwork.security.hex.Hex("local-peer-id")))

  private def getNextOrdinal(peerId: PeerId): F[Ordinal] =
    // This would be retrieved from storage
    Async[F].delay(Ordinal.MinValue)
}

object LibP2PGossipService {

  final case class Config(
    maxRumorsInMemory: Int = 1000,
    gossipInterval: FiniteDuration = 1.second,
    maxPeers: Int = 50
  )

  def make[F[_]: Async](config: Config): F[LibP2PGossipService[F]] =
    for {
      rumorStorage <- LibP2PRumorStorage.make[F](LibP2PRumorStorage.Config())
      handlers <- Ref.of[F, Map[String, RumorHandler[F]]](Map.empty)
    } yield new LibP2PGossipService[F](rumorStorage, handlers, config)
}

// Rumor handler trait
trait RumorHandler[F[_]] {
  def handleCommonRumor(rumor: Signed[CommonRumorRaw]): F[Unit]
  def handlePeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit]
}

object RumorHandler {
  def fromCommonRumorConsumer[F[_]: Async, A: Decoder](consumer: A => F[Unit]): RumorHandler[F] =
    new RumorHandler[F] {
      def handleCommonRumor(rumor: Signed[CommonRumorRaw]): F[Unit] =
        // Decode the content and pass to consumer
        Async[F].unit // Simplified for now

      def handlePeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit] = Async[F].unit
    }

  def fromPeerRumorConsumer[F[_]: Async, A: Decoder]()(consumer: PeerRumor[A] => F[Unit]): RumorHandler[F] =
    new RumorHandler[F] {
      def handleCommonRumor(rumor: Signed[CommonRumorRaw]): F[Unit] = Async[F].unit

      def handlePeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit] =
        // Decode the content and pass to consumer
        Async[F].unit // Simplified for now
    }
}
