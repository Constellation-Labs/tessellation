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

import eu.timepit.refined.types.numeric.NonNegLong
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
        val feeReferredAddresses = txs.flatMap(tx => Set(tx.value.source, tx.value.destination))
        val feeReferredBalances = feeReferredAddresses.foldLeft(SortedMap.empty[Address, Long]) {
          case (acc, address) =>
            acc.updated(address, balances.getOrElse(address, Balance.empty).value.value)
        }
        val updatedFeeReferredBalances = txs
          .foldLeft(feeReferredBalances) {
            case (balances, tx) =>
              balances
                .updatedWith(tx.source)(existing => (existing.getOrElse(Balance.empty.value.value) - tx.amount.value.value).some)
                .updatedWith(tx.destination)(existing => (existing.getOrElse(Balance.empty.value.value) + tx.amount.value.value).some)
          }

        updatedFeeReferredBalances.toList
          .foldLeftM(SortedMap.empty[Address, Balance]) {
            case (acc, (address, balance)) =>
              NonNegLong
                .from(balance)
                .map(Balance(_))
                .map(acc.updated(address, _))
                .leftMap(e => new ArithmeticException(s"Unexpected state when applying fee transactions: $e"))
                .liftTo[F]
          }
          .map { updates =>
            (balances ++ updates, txs.some)
          }
    }
}

object BalanceOpsManager {
  def make[F[_]: Async](
    feeTransactionValidator: FeeTransactionValidator[F]
  ): BalanceOpsManager[F] =
    new BalanceOpsManager[F](feeTransactionValidator)
}
