package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.data.{NonEmptyList, Validated}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.node.shared.domain.transaction.FeeTransactionValidator
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.transaction.RewardTransaction
import io.constellationnetwork.security.signature.Signed

import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class BalanceOpsManager[F[_]: Async](
  feeTransactionValidator: FeeTransactionValidator[F]
) {
  val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLoggerFromName[F](this.getClass.getName)

  def acceptRewardTxs(
    baseBalances: SortedMap[Address, Balance],
    newUpdatedBalance: Map[Address, Balance],
    rewards: SortedSet[RewardTransaction]
  ): F[(SortedMap[Address, Balance], SortedSet[RewardTransaction])] = {
    val mutableBalances = mutable.Map.from(baseBalances)
    newUpdatedBalance.foreach {
      case (addr, delta) =>
        mutableBalances.update(addr, delta)
    }

    val acceptedRewards = mutable.Set.empty[RewardTransaction]
    rewards.foreach { tx =>
      val current = mutableBalances.getOrElse(tx.destination, Balance.empty)
      current.plus(tx.amount) match {
        case Right(newBal) =>
          mutableBalances.update(tx.destination, newBal)
          acceptedRewards += tx
        case Left(error) =>
          logger
            .warn(error)(s"Invalid balance update. Current Balance: $current}, RewardTransaction: $tx}")
            .as(())
      }
    }

    val finalBalances = SortedMap.from(mutableBalances)
    val acceptedTxs = SortedSet.from(acceptedRewards)

    (finalBalances, acceptedTxs).pure
  }

  def validateFeeTxs(
    maybeTxs: Option[SortedSet[Signed[FeeTransaction]]],
    enforceWalletAuthorization: Boolean
  ): F[Unit] =
    NonEmptyList.fromList(maybeTxs.toList.flatMap(_.toList)).fold(().pure[F]) { nonEmptyTxs =>
      feeTransactionValidator.validate(nonEmptyTxs, enforceWalletAuthorization).flatMap {
        case Validated.Valid(_) =>
          ().pure[F]
        case Validated.Invalid(errors) =>
          new Exception(s"FeeTransaction validation failed: ${errors.toList.mkString(", ")}")
            .raiseError[F, Unit]
      }
    }

  def acceptFeeTxs(
    balances: SortedMap[Address, Balance],
    maybeTxs: Option[SortedSet[Signed[FeeTransaction]]]
  ): F[(SortedMap[Address, Balance], Option[SortedSet[Signed[FeeTransaction]]])] =
    maybeTxs match {
      case None => (balances, maybeTxs).pure[F]
      case Some(txs) =>
        val (updatedBalances, acceptedTxs, rejectedTxs) = BalanceOpsManager.applyFeeTransactions(balances, txs)

        rejectedTxs.traverse_ { signedTx =>
          logger.warn(
            s"Rejected fee transaction from ${signedTx.value.source} to ${signedTx.value.destination} of " +
              s"${signedTx.value.amount.value.value}: source balance insufficient or destination balance overflow"
          )
        }
          .as((updatedBalances, acceptedTxs.some))
    }
}

object BalanceOpsManager {

  /** Applies fee transactions sequentially, dropping any transaction whose source cannot cover it.
    *
    * Every debit and credit goes through `Balance.minus`/`Balance.plus`, which reject underflow and overflow. Accumulating raw `Long`s and
    * checking only the final per-address total allowed a group of transactions to wrap a source balance past `Long.MinValue` back to a
    * non-negative value, crediting destinations with supply that was never debited from anywhere.
    *
    * Unaffordable transactions are dropped rather than failing the snapshot: fee transactions are user-supplied, so raising here would let
    * anyone halt a metagraph.
    *
    * @return
    *   the updated balances, the accepted transactions, and the rejected ones in iteration order
    */
  def applyFeeTransactions(
    balances: SortedMap[Address, Balance],
    txs: SortedSet[Signed[FeeTransaction]]
  ): (SortedMap[Address, Balance], SortedSet[Signed[FeeTransaction]], List[Signed[FeeTransaction]]) = {
    val (feeReferredBalances, rejected) =
      txs.foldLeft((SortedMap.empty[Address, Balance], List.empty[Signed[FeeTransaction]])) {
        case ((acc, rejected), signedTx) =>
          val tx = signedTx.value

          def balanceOf(address: Address, current: SortedMap[Address, Balance]): Balance =
            current.getOrElse(address, balances.getOrElse(address, Balance.empty))

          val applied = for {
            debited <- balanceOf(tx.source, acc).minus(tx.amount)
            afterDebit = acc.updated(tx.source, debited)
            credited <- balanceOf(tx.destination, afterDebit).plus(tx.amount)
          } yield afterDebit.updated(tx.destination, credited)

          applied match {
            case Right(updated) => (updated, rejected)
            case Left(_)        => (acc, signedTx :: rejected)
          }
      }

    (balances ++ feeReferredBalances, txs -- rejected, rejected.reverse)
  }

  def make[F[_]: Async](
    feeTransactionValidator: FeeTransactionValidator[F]
  ): BalanceOpsManager[F] =
    new BalanceOpsManager[F](feeTransactionValidator)
}
