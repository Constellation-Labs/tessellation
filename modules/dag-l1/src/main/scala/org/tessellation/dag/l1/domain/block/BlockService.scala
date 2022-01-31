package org.tessellation.dag.l1.domain.block

import cats.Applicative
import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.semigroup._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.block.BlockValidator.BlockValidationError
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.refineV
import io.estatico.newtype.ops.toCoercibleIdOps

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
        val all = sources.combine(destinations)
        val addressBalanceChanges = all.map {
          case (address, txs) =>
            val change = txs.foldLeft(BigInt(0L))(
              (change, tx) =>
                tx match {
                  case Transaction(`address`, _, amount, fee, _, _) => change - amount.coerce - fee.coerce
                  case Transaction(_, `address`, amount, _, _, _)   => change + amount.coerce
                  case _                                            => change
                }
            )

            address -> change
        }

        addressBalanceChanges.toList.traverse {
          case (address, balanceChange) =>
            for {
              current <- addressStorage.getBalance(address)
              newBalance <- refineV[NonNegative]
                .apply(current.coerce + balanceChange)
                .bimap(BalanceNegative, Balance(_))
                .liftTo[F]
            } yield (address, newBalance)
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
