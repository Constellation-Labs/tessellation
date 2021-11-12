package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.aci.{StateChannelInput, StateChannelRouter}
import org.tessellation.ext.http4s.vars.AddressVar

import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.http4s.{EntityDecoder, HttpRoutes}
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class StateChannelRoutes[F[_]: Async](
  stateChannelRouter: StateChannelRouter[F]
) extends Http4sDsl[F] {
  private val logger = Slf4jLogger.getLogger[F]
  private val prefixPath = "/state-channel"
  implicit val decoder: EntityDecoder[F, Array[Byte]] = EntityDecoder.byteArrayDecoder[F]

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / AddressVar(address) / "input" =>
      for {
        payload <- req.as[Array[Byte]]
        input = StateChannelInput(address, payload)
        result <- stateChannelRouter
          .routeInput(input)
          .semiflatMap { output =>
            logger.debug(s"State channel output is $output") >> Ok()
          }
          .getOrElseF(NotFound(s"State channel not found for $address"))
      } yield result
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )
}
