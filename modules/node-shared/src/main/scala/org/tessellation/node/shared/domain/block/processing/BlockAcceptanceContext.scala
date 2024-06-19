package org.tessellation.node.shared.domain.block.processing

import cats.Applicative
import cats.syntax.all._

import org.tessellation.schema.BlockReference
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.swap._
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong

trait BlockAcceptanceContext[F[_]] {

  def getBalance(address: Address): F[Option[Balance]]

  def getLastTxRef(address: Address): F[Option[TransactionReference]]

  def getInitialTxRef: TransactionReference

  def getParentUsage(blockReference: BlockReference): F[Option[NonNegLong]]

  def getCollateral: Amount

  def getActiveAllowSpends(address: Address): F[Option[List[Signed[AllowSpend]]]]

  def getLastAllowSpendRef(address: Address): F[Option[AllowSpendReference]]

  def getInitialAllowSpendRef: AllowSpendReference

}

object BlockAcceptanceContext {

  def fromStaticData[F[_]: Applicative](
    balances: Map[Address, Balance],
    lastTxRefs: Map[Address, TransactionReference],
    parentUsages: Map[BlockReference, NonNegLong],
    collateral: Amount,
    initialTxRef: TransactionReference,
    activeAllowSpends: Option[Map[Address, List[Signed[AllowSpend]]]],
    lastAllowSpendRefs: Option[Map[Address, AllowSpendReference]],
    initialAllowSpendRef: AllowSpendReference
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

      def getActiveAllowSpends(address: Address): F[Option[List[Signed[AllowSpend]]]] =
        activeAllowSpends.traverse(_.get(address)).flatten.pure[F]

      def getLastAllowSpendRef(address: Address): F[Option[AllowSpendReference]] =
        lastAllowSpendRefs.traverse(_.get(address)).flatten.pure[F]

      def getInitialAllowSpendRef: AllowSpendReference = initialAllowSpendRef
    }

}
