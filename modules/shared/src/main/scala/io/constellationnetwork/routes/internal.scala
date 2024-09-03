package io.constellationnetwork.routes

import cats.Monad

import eu.timepit.refined.api.Refined
import eu.timepit.refined.boolean.Not
import eu.timepit.refined.generic.Equal
import eu.timepit.refined.predicates.all.AnyOf
import org.http4s.HttpRoutes
import org.http4s.server.Router
import shapeless.{::, HNil}

object internal {
  trait InternalRoutes[F[_]] {
    protected def prefixPath: InternalUrlPrefix
  }

  trait PublicRoutes[F[_]] extends InternalRoutes[F] {
    protected val public: HttpRoutes[F]

    def publicRoutes(implicit m: Monad[F]): HttpRoutes[F] = Router(
      prefixPath.value -> public
    )
  }

  trait P2PRoutes[F[_]] extends InternalRoutes[F] {
    protected val p2p: HttpRoutes[F]

    def p2pRoutes(implicit m: Monad[F]): HttpRoutes[F] = Router(
      prefixPath.value -> p2p
    )
  }

  trait P2PPublicRoutes[F[_]] extends InternalRoutes[F] {
    protected val p2pPublic: HttpRoutes[F]

    def p2pPublicRoutes(implicit m: Monad[F]): HttpRoutes[F] = Router(
      prefixPath.value -> p2pPublic
    )
  }

  trait CliRoutes[F[_]] extends InternalRoutes[F] {
    protected val cli: HttpRoutes[F]

    def cliRoutes(implicit m: Monad[F]): HttpRoutes[F] = Router(
      prefixPath.value -> cli
    )
  }

  private type InternalUrlPrefixes = AnyOf[
    Equal["/"]
      :: Equal["/cluster"]
      :: Equal["/targets"]
      :: Equal["/transactions"]
      :: Equal["/metrics"]
      :: Equal["/registration"]
      :: Equal["/rumors"]
      :: Equal["/consensus"]
      :: Equal["/state-channels"]
      :: Equal["/construction"]
      :: Equal["/trust"]
      :: Equal["/debug"]
      :: Equal["/node"]
      :: Equal["/currency"]
      :: Equal["/dag"]
      :: Equal["/network"]
      :: Equal["/snapshots"]
      :: Equal["/global-snapshots"]
      :: Equal["/metagraph"]
      :: HNil
  ]

  type InternalUrlPrefix = String Refined InternalUrlPrefixes
  type ExternalUrlPrefix = String Refined Not[InternalUrlPrefixes]
}
