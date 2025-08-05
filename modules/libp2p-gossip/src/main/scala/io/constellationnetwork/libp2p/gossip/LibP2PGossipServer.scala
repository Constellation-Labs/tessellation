package io.constellationnetwork.libp2p.gossip

import cats.effect.{Async, Ref}
import cats.syntax.all._

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.schema.gossip._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.Signed

import io.circe.{Decoder, Encoder, Json}
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class LibP2PGossipServer[F[_]: Async] private (
  storage: LibP2PRumorStorage[F],
  handlers: Ref[F, Map[String, RumorHandler[F]]],
  requestHandlers: Ref[F, Map[String, RequestHandler[F]]],
  config: LibP2PGossipServer.Config
) {
  private val logger = Slf4jLogger.getLogger[F]

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

  def registerRequestHandler[Req: Encoder: Decoder, Resp: Encoder: Decoder](
    requestType: String,
    handler: Req => F[Resp]
  ): F[Unit] =
    for {
      _ <- requestHandlers.update { current =>
        val requestHandler = RequestHandler.make[F, Req, Resp](handler)
        current.updated(requestType, requestHandler)
      }
      _ <- logger.info(s"Registered request handler for type: $requestType")
    } yield ()

  def handleIncomingRumor(rumor: Signed[RumorRaw]): F[Unit] =
    for {
      _ <- logger.debug(s"Handling incoming rumor: ${rumor.value.contentType}")
      handlerOpt <- handlers.get.map(_.get(rumor.value.contentType.value))
      _ <- handlerOpt match {
        case Some(handler) =>
          rumor.value match {
            case peerRumor: PeerRumorRaw =>
              val signedPeerRumor = Signed(peerRumor, rumor.proofs)
              handler.handlePeerRumor(signedPeerRumor)
            case commonRumor: CommonRumorRaw =>
              val signedCommonRumor = Signed(commonRumor, rumor.proofs)
              handler.handleCommonRumor(signedCommonRumor)
          }
        case None =>
          logger.warn(s"No handler registered for rumor type: ${rumor.value.contentType}")
      }
    } yield ()

  def handleIncomingRequest(requestType: String, requestData: Json): F[Json] =
    for {
      _ <- logger.debug(s"Handling incoming request: $requestType")
      handlerOpt <- requestHandlers.get.map(_.get(requestType))
      response <- handlerOpt match {
        case Some(handler) =>
          handler.handleRequest(requestData)
        case None =>
          logger.warn(s"No handler registered for request type: $requestType")
          Async[F].delay(Json.Null)
      }
    } yield response

  def addPeerRumor(rumor: Signed[PeerRumorRaw]): F[Unit] =
    for {
      _ <- storage.addPeerRumor(rumor)
      _ <- handleIncomingRumor(rumor)
    } yield ()

  def addCommonRumor(rumor: Signed[CommonRumorRaw]): F[Unit] =
    for {
      _ <- storage.addCommonRumor(rumor)
      _ <- handleIncomingRumor(rumor)
    } yield ()
}

object LibP2PGossipServer {

  final case class Config(
    maxHandlers: Int = 100,
    requestTimeout: FiniteDuration = 30.seconds
  )

  def make[F[_]: Async](config: Config): F[LibP2PGossipServer[F]] =
    for {
      storage <- LibP2PRumorStorage.make[F](LibP2PRumorStorage.Config())
      handlers <- Ref.of[F, Map[String, RumorHandler[F]]](Map.empty)
      requestHandlers <- Ref.of[F, Map[String, RequestHandler[F]]](Map.empty)
    } yield new LibP2PGossipServer[F](storage, handlers, requestHandlers, config)
}

// Request handler trait
trait RequestHandler[F[_]] {
  def handleRequest(requestData: Json): F[Json]
}

object RequestHandler {
  def make[F[_]: Async, Req: Decoder, Resp: Encoder](handler: Req => F[Resp]): RequestHandler[F] =
    new RequestHandler[F] {
      def handleRequest(requestData: Json): F[Json] =
        for {
          request <- Async[F].fromEither(requestData.as[Req])
          response <- handler(request)
          responseJson = Encoder[Resp].apply(response)
        } yield responseJson
    }
}
