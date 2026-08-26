package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendTransaction
import io.constellationnetwork.schema.balance.{Amount, Balance, BalanceArithmeticError}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

/** Result of allow spend acceptance containing full state, deltas, and removed keys */
case class AllowSpendAcceptanceResult(
  fullState: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
  deltas: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
  removedKeys: Set[(Option[Address], Address)] = Set.empty,
  // Allow-spend references the global layer has already settled, keyed by currency then by the address the
  // allow-spend belongs to, mirroring `activeAllowSpends`. The value is the allow-spend's lastValidEpochProgress,
  // which is what later lets the entry be dropped.
  retiredRefs: SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]] = SortedMap.empty,
  retiredRefsDeltas: SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]] = SortedMap.empty,
  removedRetiredRefKeys: Set[(Option[Address], Address)] = Set.empty
)

trait AllowSpendStateManager[F[_]] {
  def acceptAllowSpends(
    epochProgress: EpochProgress,
    activeAllowSpendsFromCurrencySnapshots: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allAcceptedSpendTxns: List[SpendTransaction],
    lastRetiredAllowSpendRefs: SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]],
    preventAllowSpendResurrection: Boolean
  )(implicit hasher: Hasher[F]): F[AllowSpendAcceptanceResult]

  def acceptAllowSpendRefs(
    lastAllowSpendRefs: SortedMap[Address, AllowSpendReference],
    lastAllowSpendContextUpdate: Map[Address, AllowSpendReference]
  ): SortedMap[Address, AllowSpendReference]

  def filterExpiredAllowSpends(
    allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    epochProgress: EpochProgress
  ): SortedMap[Address, SortedSet[Signed[AllowSpend]]]

  def updateGlobalBalancesByAllowSpends(
    epochProgress: EpochProgress,
    currentBalances: SortedMap[Address, Balance],
    globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
  ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]
}

object AllowSpendStateManager {

  def make[F[_]: Async](): AllowSpendStateManager[F] = new AllowSpendStateManager[F] {

    def acceptAllowSpends(
      epochProgress: EpochProgress,
      activeAllowSpendsFromCurrencySnapshots: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allAcceptedSpendTxns: List[SpendTransaction],
      lastRetiredAllowSpendRefs: SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]],
      preventAllowSpendResurrection: Boolean
    )(implicit hasher: Hasher[F]): F[AllowSpendAcceptanceResult] = {
      val allAcceptedSpendTxnsAllowSpendsRefs =
        allAcceptedSpendTxns
          .flatMap(_.allowSpendRef)

      val acceptedSpendTxnsRefSet = allAcceptedSpendTxnsAllowSpendsRefs.toSet

      def retiredRefsFor(currencyId: Option[Address], address: Address): Set[Hash] =
        if (preventAllowSpendResurrection)
          lastRetiredAllowSpendRefs
            .getOrElse(currencyId, SortedMap.empty[Address, SortedMap[Hash, EpochProgress]])
            .getOrElse(address, SortedMap.empty[Hash, EpochProgress])
            .keySet
            .toSet
        else
          Set.empty[Hash]

      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      val unexpiredGlobalAllowSpends = (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft(lastActiveGlobalAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.lastValidEpochProgress >= epochProgress)
          acc + (address -> unexpired)
      }

      val unexpiredGlobalWithoutSpendTransactionsF =
        unexpiredGlobalAllowSpends.toList.foldLeftM(unexpiredGlobalAllowSpends) {
          case (acc, (address, allowSpends)) =>
            val retiredGlobalRefs = retiredRefsFor(None, address)

            allowSpends.toList.traverse(_.toHashed).map { hashedAllowSpends =>
              val validAllowSpends = hashedAllowSpends
                .filterNot(h => acceptedSpendTxnsRefSet.contains(h.hash) || retiredGlobalRefs.contains(h.hash))
                .map(_.signed)
                .to(SortedSet)

              acc + (address -> validAllowSpends)
            }
        }

      def processMetagraphAllowSpends(
        metagraphId: Address,
        metagraphAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
        accAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
        accDeltas: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      ): F[
        (
          SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
          SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
        )
      ] = {
        val lastActiveMetagraphAllowSpends =
          accAllowSpends.getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])

        metagraphAllowSpends.toList.traverse {
          case (address, addressAllowSpends) =>
            val lastAddressAllowSpends = lastActiveMetagraphAllowSpends.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])

            val unexpired = (lastAddressAllowSpends ++ addressAllowSpends)
              .filter(_.lastValidEpochProgress >= epochProgress)

            val retiredMetagraphRefs = retiredRefsFor(metagraphId.some, address)

            val unexpiredWithoutSpendTransactions = unexpired.toList
              .traverse(_.toHashed)
              .map { hashedAllowSpends =>
                hashedAllowSpends.filterNot(h => acceptedSpendTxnsRefSet.contains(h.hash) || retiredMetagraphRefs.contains(h.hash))
              }
              .map(_.map(_.signed).toSortedSet)

            unexpiredWithoutSpendTransactions.map { validAllowSpends =>
              val hasChanged = lastAddressAllowSpends != validAllowSpends
              (address, validAllowSpends, hasChanged)
            }
        }.map { updatedMetagraphAllowSpends =>
          val fullStateMap = SortedMap(updatedMetagraphAllowSpends.map { case (addr, spends, _) => addr -> spends }: _*)
          // Filter out empty sets - those are removals tracked separately in removedKeys
          val deltasMap = SortedMap(updatedMetagraphAllowSpends.collect {
            case (addr, spends, true) if spends.nonEmpty => addr -> spends
          }: _*)

          val updatedFullState = accAllowSpends + (metagraphId.some -> fullStateMap)
          val updatedDeltas = if (deltasMap.nonEmpty) {
            accDeltas + (metagraphId.some -> deltasMap)
          } else {
            accDeltas
          }

          (updatedFullState, updatedDeltas)
        }
      }

      // Process metagraph allow spends and track deltas
      val processedMetagraphsF = activeAllowSpendsFromCurrencySnapshots.toList
        .foldLeft((lastActiveAllowSpends, SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]).pure[F]) {
          case (accF, (metagraphId, metagraphAllowSpends)) =>
            for {
              (accFullState, accDeltas) <- accF
              (updatedFullState, updatedDeltas) <- processMetagraphAllowSpends(metagraphId, metagraphAllowSpends, accFullState, accDeltas)
            } yield (updatedFullState, updatedDeltas)
        }

      // Every allow-spend the round can see, paired with the currency bucket and address it lives under. A reference
      // is only recorded as retired if we can pair it with its allow-spend, because the expiry we store is what
      // later prunes the entry.
      val visibleAllowSpendsByCurrency: List[(Option[Address], Address, Signed[AllowSpend])] = {
        val currencyKeys =
          lastActiveAllowSpends.keySet ++ activeAllowSpendsFromCurrencySnapshots.keySet.map(_.some) + None

        currencyKeys.toList.flatMap { currencyId =>
          val lastForCurrency =
            lastActiveAllowSpends.getOrElse(currencyId, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
          val incomingForCurrency = currencyId match {
            case None => globalAllowSpends
            case Some(metagraphId) =>
              activeAllowSpendsFromCurrencySnapshots.getOrElse(metagraphId, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
          }

          (lastForCurrency |+| incomingForCurrency).toList.flatMap {
            case (address, allowSpends) => allowSpends.toList.map((currencyId, address, _))
          }
        }
      }

      val newlyRetiredAllowSpendRefsF: F[SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]]] =
        if (!preventAllowSpendResurrection)
          SortedMap.empty[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]].pure[F]
        else
          visibleAllowSpendsByCurrency.traverse {
            case (currencyId, address, allowSpend) =>
              allowSpend.toHashed.map(hashed => (currencyId, address, hashed.hash, allowSpend.lastValidEpochProgress))
          }.map { visible =>
            visible.filter { case (_, _, hash, _) => acceptedSpendTxnsRefSet.contains(hash) }.groupBy {
              case (currencyId, _, _, _) => currencyId
            }.view.mapValues { entries =>
              entries.groupBy { case (_, address, _, _) => address }.view
                .mapValues(_.map { case (_, _, hash, lastValid) => hash -> lastValid }.to(SortedMap))
                .to(SortedMap)
            }.to(SortedMap)
          }

      for {
        (updatedCurrencyAllowSpends, currencyDeltas) <- processedMetagraphsF
        validGlobalAllowSpends <- unexpiredGlobalWithoutSpendTransactionsF
        newlyRetired <- newlyRetiredAllowSpendRefsF
      } yield {
        // Compute global deltas by comparing with previous state
        // Filter out empty sets - those are removals tracked separately in removedKeys
        val globalDeltas: SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
          validGlobalAllowSpends.filter {
            case (address, allowSpends) =>
              allowSpends.nonEmpty && !lastActiveGlobalAllowSpends.get(address).contains(allowSpends)
          }

        val fullState = if (validGlobalAllowSpends.nonEmpty) {
          updatedCurrencyAllowSpends + (None -> validGlobalAllowSpends)
        } else {
          updatedCurrencyAllowSpends
        }

        val deltas = if (globalDeltas.nonEmpty) {
          currencyDeltas + (None -> globalDeltas)
        } else {
          currencyDeltas
        }

        // Compute removed keys: addresses that had AllowSpends but now have empty or missing sets
        val removedKeys: Set[(Option[Address], Address)] = lastActiveAllowSpends.flatMap {
          case (metagraphIdOpt, innerMap) =>
            innerMap.collect {
              case (address, spends)
                  if spends.nonEmpty &&
                    !fullState.get(metagraphIdOpt).flatMap(_.get(address)).exists(_.nonEmpty) =>
                (metagraphIdOpt, address)
            }
        }.toSet

        val updatedRetiredRefs: SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]] =
          if (!preventAllowSpendResurrection)
            SortedMap.empty[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]]
          else
            (lastRetiredAllowSpendRefs.keySet ++ newlyRetired.keySet).toList.map { currencyId =>
              val previousByAddress =
                lastRetiredAllowSpendRefs.getOrElse(currencyId, SortedMap.empty[Address, SortedMap[Hash, EpochProgress]])
              val freshByAddress =
                newlyRetired.getOrElse(currencyId, SortedMap.empty[Address, SortedMap[Hash, EpochProgress]])

              val mergedByAddress = (previousByAddress.keySet ++ freshByAddress.keySet).toList.map { address =>
                val previous = previousByAddress.getOrElse(address, SortedMap.empty[Hash, EpochProgress])
                val fresh = freshByAddress.getOrElse(address, SortedMap.empty[Hash, EpochProgress])

                // Keep a retired reference only while its allow-spend could still be presented. Past that epoch the
                // unexpired filter rejects it on its own, so remembering it further would grow the ledger forever.
                address -> (previous ++ fresh).filter { case (_, lastValid) => lastValid >= epochProgress }
              }.filter { case (_, refs) => refs.nonEmpty }.to(SortedMap)

              currencyId -> mergedByAddress
            }.filter { case (_, byAddress) => byAddress.nonEmpty }.to(SortedMap)

        // Deltas: only the (currency, address) ledgers whose contents actually changed.
        val retiredRefsDeltas: SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]] = updatedRetiredRefs.map {
          case (currencyId, byAddress) =>
            val previousByAddress =
              lastRetiredAllowSpendRefs.getOrElse(currencyId, SortedMap.empty[Address, SortedMap[Hash, EpochProgress]])
            currencyId -> byAddress.filter {
              case (address, refs) => !previousByAddress.get(address).contains(refs)
            }
        }.filter { case (_, byAddress) => byAddress.nonEmpty }

        // Removals: ledgers that had entries and are now empty or gone (every reference in them expired).
        val removedRetiredRefKeys: Set[(Option[Address], Address)] = lastRetiredAllowSpendRefs.flatMap {
          case (currencyId, byAddress) =>
            byAddress.collect {
              case (address, refs)
                  if refs.nonEmpty &&
                    !updatedRetiredRefs.get(currencyId).flatMap(_.get(address)).exists(_.nonEmpty) =>
                (currencyId, address)
            }
        }.toSet

        AllowSpendAcceptanceResult(
          fullState,
          deltas,
          removedKeys,
          updatedRetiredRefs,
          retiredRefsDeltas,
          removedRetiredRefKeys
        )
      }
    }

    def acceptAllowSpendRefs(
      lastAllowSpendRefs: SortedMap[Address, AllowSpendReference],
      lastAllowSpendContextUpdate: Map[Address, AllowSpendReference]
    ): SortedMap[Address, AllowSpendReference] =
      lastAllowSpendRefs ++ lastAllowSpendContextUpdate

    def filterExpiredAllowSpends(
      allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      epochProgress: EpochProgress
    ): SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
      allowSpends.view.mapValues(_.filter(_.lastValidEpochProgress < epochProgress)).to(SortedMap)

    def updateGlobalBalancesByAllowSpends(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])] = {
      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = filterExpiredAllowSpends(lastActiveGlobalAllowSpends, epochProgress)

      val result = (globalAllowSpends |+| expiredGlobalAllowSpends)
        .foldLeft[Either[BalanceArithmeticError, (SortedMap[Address, Balance], SortedMap[Address, Balance])]](
          Right((currentBalances, SortedMap.empty[Address, Balance]))
        ) {
          case (accEither, (address, allowSpends)) =>
            for {
              (balances, balancesDelta) <- accEither
              initialBalance = balances.getOrElse(address, Balance.empty)

              unexpiredBalance <- {
                val unexpired = allowSpends.filter(_.lastValidEpochProgress >= epochProgress)

                unexpired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(initialBalance)) { (currentBalanceEither, allowSpend) =>
                  for {
                    currentBalance <- currentBalanceEither
                    balanceAfterAmount <- currentBalance.minus(SwapAmount.toAmount(allowSpend.amount))
                    balanceAfterFee <- balanceAfterAmount.minus(AllowSpendFee.toAmount(allowSpend.fee))
                  } yield balanceAfterFee
                }
              }

              expiredBalance <- {
                val expired = allowSpends.filter(_.lastValidEpochProgress < epochProgress)

                expired.foldLeft[Either[BalanceArithmeticError, Balance]](Right(unexpiredBalance)) { (currentBalanceEither, allowSpend) =>
                  for {
                    currentBalance <- currentBalanceEither
                    balanceAfterExpiredAmount <- currentBalance.plus(SwapAmount.toAmount(allowSpend.amount))
                  } yield balanceAfterExpiredAmount
                }
              }

              updatedAcc = balances.updated(address, expiredBalance)
              updatedBalancesDelta = balancesDelta.updated(address, expiredBalance)
            } yield (updatedAcc, updatedBalancesDelta)
        }
      result
    }
  }
}
