package org.tessellation.sdk.http.routes

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.ext.http4s.AddressVar
import org.tessellation.kernel._
import org.tessellation.schema.Block
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.snapshot.services.AddressService
import org.tessellation.sdk.ext.http4s.SnapshotOrdinalVar
import org.tessellation.security.signature.Signed

import io.circe.Decoder
import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityDecoder
import org.http4s.dsl.Http4sDsl
import org.http4s.server.Router
import shapeless._
import shapeless.syntax.singleton._

final case class CurrencyRoutes[F[_]: Async, T <: Transaction, B <: Block[T]: Decoder, S <: Snapshot[T, B]](
  prefixPath: String,
  addressService: AddressService[F, S],
  mkCell: Signed[B] => Cell[F, StackF, _, Either[CellError, Ω], _]
) extends Http4sDsl[F] {
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
        .as[Signed[B]]
        .map(mkCell)
        .flatMap(_.run())
        .flatMap(_ => Ok())
  }

  val publicRoutes: HttpRoutes[F] = Router(
    prefixPath -> httpRoutes
  )

}
