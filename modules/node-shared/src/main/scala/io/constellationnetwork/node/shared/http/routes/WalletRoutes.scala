package io.constellationnetwork.node.shared.http.routes

import cats.effect.Async
import cats.syntax.flatMap._

import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.node.shared.domain.snapshot.services.AddressService
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.snapshot.Snapshot

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
      addressService.getTotalSupply.flatMap {
        case Some((supply, ordinal)) =>
          Ok(("total" ->> supply) :: ("ordinal" ->> ordinal.value.value) :: HNil)
        case _ => NotFound()
      }

    case GET -> Root / "circulated-supply" =>
      addressService.getCirculatedSupply.flatMap {
        case Some((supply, ordinal)) =>
          Ok(("circulated" ->> supply) :: ("ordinal" ->> ordinal.value.value) :: HNil)
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
