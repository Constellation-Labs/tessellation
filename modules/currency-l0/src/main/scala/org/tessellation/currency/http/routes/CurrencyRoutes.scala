package org.tessellation.currency.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.currency.domain.cell.{L0Cell, L0CellInput}
import org.tessellation.currency.domain.dag.CurrencyService
import org.tessellation.currency.schema.currency.CurrencyBlock
import org.tessellation.ext.http4s.AddressVar
import org.tessellation.sdk.ext.http4s.SnapshotOrdinalVar
import org.tessellation.security.signature.Signed

import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import shapeless._
import shapeless.syntax.singleton._

final case class CurrencyRoutes[F[_]: Async](dagService: CurrencyService[F], mkDagCell: L0Cell.Mk[F]) extends Http4sDsl[F] {
  private[routes] val prefixPath = "/dag"

  import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

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
        .as[Signed[CurrencyBlock]]
        .map(L0CellInput.HandleL1Block(_))
        .map(mkDagCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
