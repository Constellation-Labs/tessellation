package org.tessellation.sdk.infrastructure

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._

import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.key.ops.PublicKeyOps
import org.tessellation.sdk.config.types.CollateralConfig
import org.tessellation.sdk.domain.collateral.{Collateral, LatestBalances}

import eu.timepit.refined.auto._

object Collateral {

  def make[F[_]: Async: SecurityProvider](
    config: CollateralConfig,
    latestBalances: LatestBalances[F]
  ): Collateral[F] =
    new Collateral[F] {

      def hasCollateral(peerId: PeerId): F[Boolean] =
        peerId.value.toPublicKey
          .map(_.toAddress)
          .flatMap(getBalance)
          .map(_.map(_ >= config.amount).getOrElse(true))

      private def getBalance(address: Address): F[Option[Amount]] =
        latestBalances.getLatestBalances
          .map(_.map(_.withDefaultValue(Balance.empty)(address)))
    }
}
