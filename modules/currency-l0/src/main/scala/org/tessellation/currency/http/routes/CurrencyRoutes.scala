package org.tessellation.currency.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import org.tessellation.currency.domain.cell.{L0Cell, L0CellInput}
import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshot}
import org.tessellation.ext.http4s.AddressVar
import org.tessellation.sdk.ext.http4s.SnapshotOrdinalVar
import org.tessellation.security.signature.Signed
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import org.tessellation.sdk.domain.snapshot.services.AddressService
import shapeless._
import shapeless.syntax.singleton._

final case class CurrencyRoutes[F[_]: Async](addressService: AddressService[F, CurrencySnapshot], mkCell: L0Cell.Mk[F])
    extends Http4sDsl[F] {
  private[routes] val prefixPath = "/dag"

  import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

  private val httpRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / AddressVar(address) / "balance" =>
      addressService
        .getBalance(address)
        .flatMap {
          case Some((balance, ordinal)) =>
            Ok(("balance" ->> balance) :: ("ordinal" ->> ordinal.value.value) :: HNil)
          case _ => NotFound()
        }

    case GET -> Root / "total-supply" =>
      addressService.getTotalSupply.flatMap {
        case Some((supply, ordinal)) =>
          Ok(("total" ->> supply) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / "wallet-count" =>
      addressService.getWalletCount.flatMap {
        case Some((wallets, ordinal)) =>
          Ok(("count" ->> wallets) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / SnapshotOrdinalVar(ordinal) / AddressVar(address) / "balance" =>
      addressService.getBalance(ordinal, address).flatMap {
        case Some((balance, ordinal)) =>
          Ok(("balance" ->> balance) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / SnapshotOrdinalVar(ordinal) / "total-supply" =>
      addressService.getTotalSupply(ordinal).flatMap {
        case Some((supply, ordinal)) =>
          Ok(("total" ->> supply) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / SnapshotOrdinalVar(ordinal) / "wallet-count" =>
      addressService.getWalletCount(ordinal).flatMap {
        case Some((wallets, ordinal)) =>
          Ok(("count" ->> wallets) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case req @ POST -> Root / "l1-output" =>
      req
        .as[Signed[CurrencyBlock]]
        .map(L0CellInput.HandleL1Block)
        .map(mkCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
