package io.constellationnetwork.node.shared.domain.block.processing

import cats.Applicative
import cats.syntax.applicative._

import io.constellationnetwork.schema.BlockReference
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.transaction.TransactionReference

import eu.timepit.refined.types.numeric.NonNegLong

trait BlockAcceptanceContext[F[_]] {

  def getBalance(address: Address): F[Option[Balance]]

  def getLastTxRef(address: Address): F[Option[TransactionReference]]

  def getInitialTxRef: TransactionReference

  def getParentUsage(blockReference: BlockReference): F[Option[NonNegLong]]

  def getCollateral: Amount

}

object BlockAcceptanceContext {

  def fromStaticData[F[_]: Applicative](
    balances: Map[Address, Balance],
    lastTxRefs: Map[Address, TransactionReference],
    parentUsages: Map[BlockReference, NonNegLong],
    collateral: Amount,
    initialTxRef: TransactionReference
  ): BlockAcceptanceContext[F] =
    new BlockAcceptanceContext[F] {

      def getBalance(address: Address): F[Option[Balance]] =
        balances.get(address).pure[F]

      def getLastTxRef(address: Address): F[Option[TransactionReference]] =
        lastTxRefs.get(address).pure[F]

      def getInitialTxRef: TransactionReference =
        initialTxRef

      def getParentUsage(blockReference: BlockReference): F[Option[NonNegLong]] =
        parentUsages.get(blockReference).pure[F]

      def getCollateral = collateral
    }

}
