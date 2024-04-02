package org.tessellation.node.shared.http.routes

import cats.effect.Async
import cats.syntax.flatMap._

import org.tessellation.ext.http4s.AddressVar
import org.tessellation.node.shared.domain.snapshot.services.AddressService
import org.tessellation.routes.internal._
import org.tessellation.schema.snapshot.Snapshot

import io.circe.shapes._
import org.http4s.HttpRoutes
import org.http4s.dsl.Http4sDsl
import shapeless._
import shapeless.syntax.singleton._

final case class WalletRoutes[F[_]: Async, S <: Snapshot](
  prefixPath: InternalUrlPrefix,
  addressService: AddressService[F, S]
) extends Http4sDsl[F]
    with PublicRoutes[F] {
  import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / AddressVar(address) / "balance" =>
      addressService
        .getBalance(address)
        .flatMap {
          case Some((balance, ordinal)) =>
            Ok(("balance" ->> balance) :: ("ordinal" ->> ordinal.value.value) :: HNil)
          case _ => NotFound()
        }

    case GET -> Root / "total-supply" =>
      addressService.getFilteredOutTotalSupply.flatMap {
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
  }

}
