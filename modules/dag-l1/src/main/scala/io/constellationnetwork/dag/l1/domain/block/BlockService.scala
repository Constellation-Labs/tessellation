package io.constellationnetwork.dag.l1.domain.block

import cats.data.EitherT
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._

import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.transaction.TransactionStorage
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.schema.Block.HashedOps
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait BlockService[F[_]] {
  def accept(signedBlock: Signed[Block])(implicit hasher: Hasher[F]): F[Unit]
}

object BlockService {

  def make[F[_]: Async](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    transactionStorage: TransactionStorage[F],
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    collateral: Amount,
    txHasher: Hasher[F]
  ): BlockService[F] =
    new BlockService[F] {

      def accept(signedBlock: Signed[Block])(implicit hasher: Hasher[F]): F[Unit] =
        signedBlock.toHashed.flatMap { hashedBlock =>
          EitherT
            .fromOptionF(lastGlobalSnapshotStorage.getOrdinal, SnapshotOrdinalUnavailable)
            .flatMapF(blockAcceptanceManager.acceptBlock(signedBlock, context, _))
            .leftSemiflatMap(processAcceptanceError(hashedBlock))
            .semiflatMap { case (contextUpdate, _) => processAcceptanceSuccess(hashedBlock)(contextUpdate) }
            .rethrowT
        }
      private val context: BlockAcceptanceContext[F] = new BlockAcceptanceContext[F] {

        def getBalance(address: Address): F[Option[Balance]] =
          addressStorage.getBalance(address).map(_.some)

        def getLastTxRef(address: Address): F[Option[transaction.TransactionReference]] =
          transactionStorage.getLastProcessedTransaction(address).map(_.ref.some)

        def getInitialTxRef: TransactionReference =
          transactionStorage.getInitialTx.ref

        def getParentUsage(blockReference: BlockReference): F[Option[NonNegLong]] =
          blockStorage.getUsages(blockReference.hash)

        def getCollateral: Amount = collateral
      }

      private def processAcceptanceSuccess(
        hashedBlock: Hashed[Block]
      )(contextUpdate: BlockAcceptanceContextUpdate): F[Unit] = {
        implicit val hasher = txHasher

        for {
          hashedTransactions <- hashedBlock.signed.transactions.toNonEmptyList
            .sortBy(_.ordinal)
            .traverse(_.toHashed)

          _ <- hashedTransactions.traverse(transactionStorage.accept)
          _ <- blockStorage.accept(hashedBlock)
          _ <- addressStorage.updateBalances(contextUpdate.balances)
          isDependent = (block: Signed[Block]) => BlockRelations.dependsOn[F](hashedBlock, txHasher = txHasher)(block)
          _ <- blockStorage.restoreDependent(isDependent)
        } yield ()
      }

      private def processAcceptanceError(hashedBlock: Hashed[Block])(reason: BlockNotAcceptedReason): F[BlockAcceptanceError] =
        blockStorage
          .postpone(hashedBlock)
          .as(BlockAcceptanceError(hashedBlock.ownReference, reason))

    }

  case class BlockAcceptanceError(blockReference: BlockReference, reason: BlockNotAcceptedReason) extends NoStackTrace {
    override def getMessage: String = s"Block ${blockReference.show} could not be accepted ${reason.show}"
  }
}
