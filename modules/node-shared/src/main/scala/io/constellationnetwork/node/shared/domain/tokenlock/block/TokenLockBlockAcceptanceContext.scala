package io.constellationnetwork.node.shared.domain.tokenlock.block

import cats.Applicative
import cats.syntax.applicative._

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.tokenLock.TokenLockReference

trait TokenLockBlockAcceptanceContext[F[_]] {

  def getBalance(address: Address): F[Option[Balance]]

  def getLastTxRef(address: Address): F[Option[TokenLockReference]]

  def getInitialTxRef: TokenLockReference

  def getCollateral: Amount

}

object TokenLockBlockAcceptanceContext {

  def fromStaticData[F[_]: Applicative](
    balances: Map[Address, Balance],
    lastTxRefs: Map[Address, TokenLockReference],
    collateral: Amount,
    initialTxRef: TokenLockReference
  ): TokenLockBlockAcceptanceContext[F] =
    new TokenLockBlockAcceptanceContext[F] {

      def getBalance(address: Address): F[Option[Balance]] =
        balances.get(address).pure[F]

      def getLastTxRef(address: Address): F[Option[TokenLockReference]] =
        lastTxRefs.get(address).pure[F]

      def getInitialTxRef: TokenLockReference =
        initialTxRef

      def getCollateral: Amount = collateral
    }

}
