package org.tessellation.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.domain.cell.{L0Cell, L0CellInput}
import org.tessellation.domain.dag.DAGService
import org.tessellation.ext.http4s.{AddressVar, SnapshotOrdinalVar}
import org.tessellation.security.signature.Signed

import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import shapeless._
import shapeless.syntax.singleton._

final case class DagRoutes[F[_]: Async](dagService: DAGService[F], mkDagCell: L0Cell.Mk[F]) extends Http4sDsl[F] {
  private[routes] val prefixPath = "/dag"

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / AddressVar(address) / "balance" =>
      dagService
        .getBalance(address)
        .flatMap {
          case Some((balance, ordinal)) =>
            Ok(("balance" ->> balance) :: ("ordinal" ->> ordinal.value.value) :: HNil)
          case _ => NotFound()
        }

    case GET -> Root / "total-supply" =>
      dagService.getTotalSupply.flatMap {
        case Some((supply, ordinal)) =>
          Ok(("total" ->> supply) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / "wallet-count" =>
      dagService.getWalletCount.flatMap {
        case Some((wallets, ordinal)) =>
          Ok(("count" ->> wallets) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / SnapshotOrdinalVar(ordinal) / AddressVar(address) / "balance" =>
      dagService.getBalance(ordinal, address).flatMap {
        case Some((balance, ordinal)) =>
          Ok(("balance" ->> balance) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / SnapshotOrdinalVar(ordinal) / "total-supply" =>
      dagService.getTotalSupply(ordinal).flatMap {
        case Some((supply, ordinal)) =>
          Ok(("total" ->> supply) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / SnapshotOrdinalVar(ordinal) / "wallet-count" =>
      dagService.getWalletCount(ordinal).flatMap {
        case Some((wallets, ordinal)) =>
          Ok(("count" ->> wallets) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case req @ POST -> Root / "l1-output" =>
      req
        .as[Signed[DAGBlock]]
        .map(L0CellInput.HandleDAGL1(_))
        .map(mkDagCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
