package io.constellationnetwork.node.shared.infrastructure.collateral

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._

import io.constellationnetwork.node.shared.config.DelegatedRewardsEra
import io.constellationnetwork.node.shared.config.types.CollateralConfig
import io.constellationnetwork.node.shared.domain.collateral.{Collateral, LatestBalances}
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.key.ops.PublicKeyOps

import eu.timepit.refined.auto._

object Collateral {

  // Live checks follow the finalized epoch; snapshot acceptance checks the candidate artifact epoch.
  def makeForGlobalSnapshots[F[_]: Async: SecurityProvider](
    config: CollateralConfig,
    latestState: F[Option[(EpochProgress, Map[Address, Balance])]],
    era: DelegatedRewardsEra
  ): Collateral[F] =
    new Collateral[F] {
      def hasCollateral(peerId: PeerId): F[Boolean] =
        peerId.value.toPublicKey.map(_.toAddress).flatMap { address =>
          latestState.map(_.forall {
            case (epoch, balances) =>
              balances.getOrElse(address, Balance.empty).satisfiesCollateral(era.collateralAt(epoch, config.amount))
          })
        }
    }

  // Fixed policy for currency networks; native DAG must use makeForGlobalSnapshots.
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
