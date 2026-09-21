package io.constellationnetwork.node.shared.domain.swap

import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.BurnAction
import io.constellationnetwork.schema.balance.{Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.swap.SwapAmount

/** One checked fold both validates and applies native self-burns. The input must be the balance after accepted transactions, locks,
  * allowances, spends and authorized adjustments. There is no second applier and no access to AllowSpend authority or Global/DAG balances.
  */
object BurnActionValidator {
  sealed trait Rejection
  case object Disabled extends Rejection
  case object PendingGlobalChanges extends Rejection
  case object InvalidSource extends Rejection
  case object InvalidCurrency extends Rejection
  case class InsufficientBalance(error: BalanceArithmeticError) extends Rejection

  case class Result(
    balances: SortedMap[Address, Balance],
    accepted: SortedSet[BurnAction],
    rejected: List[(BurnAction, Rejection)]
  )

  def isEnabled(parentGlobalOrdinal: SnapshotOrdinal, activation: SnapshotOrdinal): Boolean =
    activation =!= SnapshotOrdinal.MaxValue && parentGlobalOrdinal >= activation

  def accept(
    actions: SortedSet[BurnAction],
    metagraph: Address,
    balances: SortedMap[Address, Balance],
    parentGlobalOrdinal: SnapshotOrdinal,
    activation: SnapshotOrdinal,
    pendingGlobalChanges: Boolean = false
  ): Result = {
    val enabled = isEnabled(parentGlobalOrdinal, activation)
    val result = actions.foldLeft(Result(balances, SortedSet.empty, List.empty)) { (result, action) =>
      // Each action is atomic; failed later transactions cannot leave a partially burned balance.
      val checked = action.burnTransactions.toList.foldLeft[Either[Rejection, Balance]](
        if (!enabled) Left(Disabled)
        else if (pendingGlobalChanges) Left(PendingGlobalChanges)
        else Right(result.balances.getOrElse(metagraph, Balance.empty))
      ) { (current, transaction) =>
        for {
          balance <- current
          _ <- Either.cond(transaction.source == metagraph, (), InvalidSource: Rejection)
          _ <- Either.cond(transaction.currencyId.value == metagraph, (), InvalidCurrency: Rejection)
          updated <- balance.minus(SwapAmount.toAmount(transaction.amount)).leftMap(InsufficientBalance(_): Rejection)
        } yield updated
      }
      checked match {
        case Right(balance) =>
          result.copy(balances = result.balances.updated(metagraph, balance), accepted = result.accepted + action)
        case Left(error) => result.copy(rejected = (action -> error) :: result.rejected)
      }
    }
    result.copy(rejected = result.rejected.reverse)
  }
}
