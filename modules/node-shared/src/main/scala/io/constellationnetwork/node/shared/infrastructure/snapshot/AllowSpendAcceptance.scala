package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SpendTransaction
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.syntax.sortedCollection._

/** Acceptance of allow-spends into the global layer's `activeAllowSpends`, together with the ledger of references the global layer has
  * already retired.
  *
  * Extracted from `GlobalSnapshotAcceptanceManager` so the resurrection guard can be exercised directly.
  */
object AllowSpendAcceptance {

  /** Allow-spend references the global layer has already settled, keyed by currency and then by the address the allow-spend belongs to,
    * mirroring the shape of `activeAllowSpends`. The value is the allow-spend's `lastValidEpochProgress`, which is what later lets the
    * entry be dropped.
    */
  type RetiredAllowSpendRefs = SortedMap[Option[Address], SortedMap[Address, SortedMap[Hash, EpochProgress]]]

  def filterExpiredAllowSpends(
    allowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    epochProgress: EpochProgress
  ): SortedMap[Address, SortedSet[Signed[AllowSpend]]] =
    allowSpends.view.mapValues(_.filter(_.lastValidEpochProgress < epochProgress)).to(SortedMap)

  /** Rebuilds the active allow-spend map for the global layer and for every metagraph that reported one.
    *
    * The per-metagraph map is a mirror of the metagraph's own `info.activeAllowSpends`, which necessarily lags the global layer: a
    * metagraph only learns that its SpendAction was accepted from the global snapshot that accepted it. Unioning that lagging self-report
    * straight back in therefore re-adds references the global layer has already retired, and a re-added reference can be presented and
    * settled a second time (PROT-1691).
    *
    * Guarded by `preventAllowSpendResurrection`, the global layer keeps its own ledger of retired references and refuses to re-add them. An
    * entry is dropped once the allow-spend's `lastValidEpochProgress` has passed, since from then on the unexpired filter rejects the
    * allow-spend anyway - so the ledger stays bounded by allow-spend lifetime rather than growing without limit.
    */
  def acceptAllowSpends[F[_]: Async](
    epochProgress: EpochProgress,
    activeAllowSpendsFromCurrencySnapshots: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
    lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
    allAcceptedSpendTxns: List[SpendTransaction],
    lastRetiredAllowSpendRefs: RetiredAllowSpendRefs,
    preventAllowSpendResurrection: Boolean
  )(implicit hasher: Hasher[F]): F[
    (
      SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      RetiredAllowSpendRefs
    )
  ] = {
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

    val unexpiredGlobalWithoutSpendTransactions =
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
      accAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
      val lastActiveMetagraphAllowSpends =
        accAllowSpends.getOrElse(metagraphId.some, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])

      metagraphAllowSpends.toList.traverse {
        case (address, addressAllowSpends) =>
          val lastAddressAllowSpends = lastActiveMetagraphAllowSpends.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val retiredMetagraphRefs = retiredRefsFor(metagraphId.some, address)

          val unexpired = (lastAddressAllowSpends ++ addressAllowSpends)
            .filter(_.lastValidEpochProgress >= epochProgress)

          val unexpiredWithoutSpendTransactions = unexpired.toList
            .traverse(_.toHashed)
            .map { hashedAllowSpends =>
              hashedAllowSpends.filterNot(h => acceptedSpendTxnsRefSet.contains(h.hash) || retiredMetagraphRefs.contains(h.hash))
            }
            .map(_.map(_.signed).toSortedSet)

          unexpiredWithoutSpendTransactions.map(validAllowSpends => address -> validAllowSpends)
      }.map { updatedMetagraphAllowSpends =>
        accAllowSpends + (metagraphId.some -> SortedMap(updatedMetagraphAllowSpends: _*))
      }
    }

    // Every allow-spend the round can see, keyed by the currency bucket it lives in. A reference is only recorded as
    // retired if we can pair it with its allow-spend, because the expiry we store is what later prunes the entry.
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

    val newlyRetiredAllowSpendRefs: F[RetiredAllowSpendRefs] =
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
      updatedCurrencyAllowSpends <- activeAllowSpendsFromCurrencySnapshots.toList
        .foldLeft(lastActiveAllowSpends.pure[F]) {
          case (accAllowSpendsF, (metagraphId, metagraphAllowSpends)) =>
            for {
              accAllowSpends <- accAllowSpendsF
              updatedAllowSpends <- processMetagraphAllowSpends(metagraphId, metagraphAllowSpends, accAllowSpends)
            } yield updatedAllowSpends
        }
      validGlobalAllowSpends <- unexpiredGlobalWithoutSpendTransactions
      newlyRetired <- newlyRetiredAllowSpendRefs
    } yield {
      val updatedAllowSpends =
        if (validGlobalAllowSpends.nonEmpty)
          updatedCurrencyAllowSpends + (None -> validGlobalAllowSpends)
        else
          updatedCurrencyAllowSpends

      val updatedRetiredRefs =
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

      (updatedAllowSpends, updatedRetiredRefs)
    }
  }
}
