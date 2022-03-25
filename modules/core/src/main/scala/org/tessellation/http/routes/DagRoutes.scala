package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.domain.dag.DAGService
import org.tessellation.ext.http4s.vars.AddressVar
import org.tessellation.schema.balance.Balance

import derevo.circe.magnolia.encoder
import derevo.derive
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router

final case class DagRoutes[F[_]: Async](dagService: DAGService[F]) extends Http4sDsl[F] {
  import DTO._

  private[routes] val prefixPath = "/dag"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / AddressVar(address) / "balance" =>
      dagService
        .getBalance(address)
        .map(BalanceDTO(_))
        .flatMap(Ok(_))

    case GET -> Root / "total-supply" =>
      dagService.getTotalSupply
        .map(TotalSupplyDTO(_))
        .flatMap(Ok(_))
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}

object DTO {

  @derive(encoder)
  case class BalanceDTO(balance: Balance)

  @derive(encoder)
  case class TotalSupplyDTO(total: BigInt)
}
