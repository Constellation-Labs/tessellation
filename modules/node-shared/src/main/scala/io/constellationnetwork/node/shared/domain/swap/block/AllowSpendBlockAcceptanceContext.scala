package io.constellationnetwork.node.shared.domain.swap.block

import cats.Applicative
import cats.syntax.applicative._

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.swap.AllowSpendReference

trait AllowSpendBlockAcceptanceContext[F[_]] {

  def getBalance(address: Address): F[Option[Balance]]

  def getLastTxRef(address: Address): F[Option[AllowSpendReference]]

  def getInitialTxRef: AllowSpendReference

  def getCollateral: Amount

}

object AllowSpendBlockAcceptanceContext {

  def fromStaticData[F[_]: Applicative](
    balances: Map[Address, Balance],
    lastTxRefs: Map[Address, AllowSpendReference],
    collateral: Amount,
    initialTxRef: AllowSpendReference
  ): AllowSpendBlockAcceptanceContext[F] =
    new AllowSpendBlockAcceptanceContext[F] {

      def getBalance(address: Address): F[Option[Balance]] =
        balances.get(address).pure[F]

      def getLastTxRef(address: Address): F[Option[AllowSpendReference]] =
        lastTxRefs.get(address).pure[F]

      def getInitialTxRef: AllowSpendReference =
        initialTxRef

      def getCollateral = collateral
    }

}
