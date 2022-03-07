package org.tessellation.dag.l1.domain.block

import cats.Applicative
import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.block.BlockValidator.BlockValidationError
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Balance, BalanceOutOfRange}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._

trait BlockService[F[_]] {
  def accept(signedBlock: Signed[DAGBlock]): F[Unit]
}

object BlockService {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    blockValidator: BlockValidator[F],
    transactionStorage: TransactionStorage[F]
  ): BlockService[F] =
    new BlockService[F] {

      def accept(signedBlock: Signed[DAGBlock]): F[Unit] =
        for {
          _ <- blockValidator.validate(signedBlock).map {
            case Valid(_)         => Applicative[F].unit
            case Invalid(reasons) => BlockInvalid(reasons).raiseError[F, Unit]
          }

          hashedBlock <- signedBlock.hashWithSignatureCheck.flatMap(_.liftTo[F])
          hashedTransactions <- signedBlock.value.transactions.toList
            .traverse(_.hashWithSignatureCheck.flatMap(_.liftTo[F]))
            .map(_.toSet)

          _ <- acceptTransactions(hashedTransactions)
          _ <- blockStorage.accept(hashedBlock)
          _ <- blockStorage.handleTipsUpdate(hashedBlock)
        } yield ()

      private def prepareBalances(transactions: Set[Transaction]): F[Map[Address, Balance]] = {
        val sources = transactions.groupBy(_.source)
        val destinations = transactions.groupBy(_.destination)

        val addresses = sources ++ destinations

        def applyTransactions(
          balance: Balance,
          address: Address,
          txs: Set[Transaction]
        ): Either[BalanceOutOfRange, Balance] =
          txs.foldLeft(balance.asRight[BalanceOutOfRange]) { (acc, tx) =>
            tx match {
              case Transaction(`address`, _, amount, fee, _, _) => acc.flatMap(_.minus(amount)).flatMap(_.minus(fee))
              case Transaction(_, `address`, amount, _, _, _)   => acc.flatMap(_.plus(amount))
              case _                                            => acc
            }
          }

        addresses.toList.traverse {
          case (address, txs) =>
            addressStorage
              .getBalance(address)
              .flatMap { balance =>
                applyTransactions(balance, address, txs).liftTo[F]
              }
              .map((address, _))
        }.map(_.toMap)
      }
      private def acceptTransactions(hashedTransactions: Set[Hashed[Transaction]]): F[Unit] =
        for {
          preparedBalances <- prepareBalances(hashedTransactions.map(_.signed.value))
          _ <- addressStorage.updateBalances(preparedBalances)
          _ <- hashedTransactions.toList.sorted.traverse(transactionStorage.accept)
        } yield ()
    }

  sealed trait BlockAcceptanceError extends NoStackTrace
  case class BlockInvalid(reasons: NonEmptyList[BlockValidationError]) extends BlockAcceptanceError {
    override def getMessage: String = s"Block failed validation! Reasons are: $reasons"
  }
  case class BalanceNegative(message: String) extends BlockAcceptanceError {
    override def getMessage: String = message
  }
}
