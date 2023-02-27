package org.tessellation.dag.l1.domain.block

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block.HashedOps
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, BlockReference, transaction}
import org.tessellation.sdk.domain.block.processing._
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait BlockService[F[_], T <: Transaction, B <: Block[T]] {
  def accept(signedBlock: Signed[B]): F[Unit]
}

object BlockService {

  def make[F[_]: Async: KryoSerializer, T <: Transaction, B <: Block[T]](
    blockAcceptanceManager: BlockAcceptanceManager[F, T, B],
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F, B],
    transactionStorage: TransactionStorage[F, T],
    collateral: Amount
  ): BlockService[F, T, B] =
    new BlockService[F, T, B] {

      def accept(signedBlock: Signed[B]): F[Unit] =
        signedBlock.toHashed.flatMap { hashedBlock =>
          EitherT(blockAcceptanceManager.acceptBlock(signedBlock, context))
            .leftSemiflatMap(processAcceptanceError(hashedBlock))
            .semiflatMap { case (contextUpdate, _) => processAcceptanceSuccess(hashedBlock)(contextUpdate) }
            .rethrowT
        }

      private val context: BlockAcceptanceContext[F] = new BlockAcceptanceContext[F] {

        def getBalance(address: Address): F[Option[Balance]] =
          addressStorage.getBalance(address).map(_.some)

        def getLastTxRef(address: Address): F[Option[transaction.TransactionReference]] =
          transactionStorage.getLastAcceptedReference(address).map(_.some)

        def getParentUsage(blockReference: BlockReference): F[Option[NonNegLong]] =
          blockStorage.getUsages(blockReference.hash)

        def getCollateral: Amount = collateral
      }

      private def processAcceptanceSuccess(hashedBlock: Hashed[B])(contextUpdate: BlockAcceptanceContextUpdate): F[Unit] = for {
        hashedTransactions <- hashedBlock.signed.transactions.toNonEmptyList
          .sortBy(_.ordinal)
          .traverse(_.toHashed)

        _ <- hashedTransactions.traverse(transactionStorage.accept)
        _ <- blockStorage.accept(hashedBlock)
        _ <- addressStorage.updateBalances(contextUpdate.balances)
        isDependent = (block: Signed[B]) => BlockRelations.dependsOn[F, T, B](hashedBlock)(block)
        _ <- blockStorage.restoreDependent(isDependent)
      } yield ()

      private def processAcceptanceError(hashedBlock: Hashed[B])(reason: BlockNotAcceptedReason): F[BlockAcceptanceError] =
        blockStorage
          .postpone(hashedBlock)
          .as(BlockAcceptanceError(hashedBlock.ownReference, reason))

    }

  case class BlockAcceptanceError(blockReference: BlockReference, reason: BlockNotAcceptedReason) extends NoStackTrace {
    override def getMessage: String = s"Block ${blockReference.show} could not be accepted ${reason.show}"
  }
}
