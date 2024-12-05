package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshotV1, CurrencySnapshotInfoV1}
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.merkletree.Proof
import io.constellationnetwork.merkletree.syntax._
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.AllowSpend
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.schema.tokenLock.TokenLockAmount.toAmount
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.disjunctionCodecs._

trait GlobalSnapshotAcceptanceManager[F[_]] {
  def accept(
    ordinal: SnapshotOrdinal,
    epochProgress: EpochProgress,
    blocksForAcceptance: List[Signed[Block]],
    scEvents: List[StateChannelOutput],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
    validationType: StateChannelValidationType
  ): F[
    (
      BlockAcceptanceResult,
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput],
      SortedSet[RewardTransaction],
      GlobalSnapshotInfo,
      GlobalSnapshotStateProof
    )
  ]
}

object GlobalSnapshotAcceptanceManager {

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: HasherSelector](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    collateral: Amount
  ) = new GlobalSnapshotAcceptanceManager[F] {

    def accept(
      ordinal: SnapshotOrdinal,
      epochProgress: EpochProgress,
      blocksForAcceptance: List[Signed[Block]],
      scEvents: List[StateChannelOutput],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
      validationType: StateChannelValidationType
    ) = {
      implicit val hasher = HasherSelector[F].getForOrdinal(ordinal)

      for {
        acceptanceResult <- acceptBlocks(blocksForAcceptance, lastSnapshotContext, lastActiveTips, lastDeprecatedTips, ordinal)

        StateChannelAcceptanceResult(scSnapshots, currencySnapshots, returnedSCEvents, currencyAcceptanceBalanceUpdate) <-
          stateChannelEventsProcessor
            .process(
              ordinal,
              lastSnapshotContext.copy(balances = lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances),
              scEvents,
              validationType
            )

        sCSnapshotHashes <- scSnapshots.toList.traverse {
          case (address, nel) => nel.last.toHashed.map(address -> _.hash)
        }
          .map(_.toMap)
        updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
        updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots
        updatedAllowSpends = lastSnapshotContext.activeAllowSpends.map(acceptAllowSpends(epochProgress, currencySnapshots, _))

        acceptedTransactions = acceptanceResult.accepted.flatMap { case (block, _) => block.value.transactions.toSortedSet }.toSortedSet

        transactionsRefs = acceptTransactionRefs(
          lastSnapshotContext.lastTxRefs,
          acceptanceResult.contextUpdate.lastTxRefs,
          acceptedTransactions
        )

        rewards <- calculateRewardsFn(acceptedTransactions)

        (updatedBalancesByRewards, acceptedRewardTxs) = acceptRewardTxs(
          lastSnapshotContext.balances ++ acceptanceResult.contextUpdate.balances ++ currencyAcceptanceBalanceUpdate,
          rewards
        )

        currencyTokenLocks = getTokenLocks(
          currencySnapshots
        )

        currencyTokenUnlocks = getTokenUnlocks(
          currencySnapshots
        )

        updatedTokenLockBalances = updateTokenLockBalances(
          currencyTokenLocks,
          currencyTokenUnlocks,
          lastSnapshotContext.tokenLockBalances
        )

        (maybeMerkleTree, updatedLastCurrencySnapshotProofs) <- hasher.getLogic(ordinal) match {
          case JsonHash =>
            val maybeMerkleTree = updatedLastCurrencySnapshots.merkleTree[F]

            val updatedLastCurrencySnapshotProofs = maybeMerkleTree.flatMap {
              _.traverse { merkleTree =>
                updatedLastCurrencySnapshots.toList.traverse {
                  case (address, state) =>
                    (address, state).hash
                      .map(merkleTree.findPath(_))
                      .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
                      .map((address, _))
                }
              }.map(_.map(SortedMap.from(_)).getOrElse(SortedMap.empty[Address, Proof]))
            }

            (maybeMerkleTree, updatedLastCurrencySnapshotProofs).tupled

          case KryoHash =>
            val updatedLastCurrencySnapshotsCompatible = updatedLastCurrencySnapshots.map {
              case (address, Left(snapshot)) => (address, Left(snapshot))
              case (address, Right((Signed(incrementalSnapshot, proofs), info))) =>
                (
                  address,
                  Right(
                    (
                      Signed(CurrencyIncrementalSnapshotV1.fromCurrencyIncrementalSnapshot(incrementalSnapshot), proofs),
                      CurrencySnapshotInfoV1.fromCurrencySnapshotInfo(info)
                    )
                  )
                )
            }

            val maybeMerkleTree = updatedLastCurrencySnapshotsCompatible.merkleTree[F]

            val updatedLastCurrencySnapshotProofs = maybeMerkleTree.flatMap {
              _.traverse { merkleTree =>
                updatedLastCurrencySnapshotsCompatible.toList.traverse {
                  case (address, state) =>
                    hasher
                      .hash((address, state))
                      .map(merkleTree.findPath(_))
                      .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
                      .map((address, _))
                }
              }.map(_.map(SortedMap.from(_)).getOrElse(SortedMap.empty[Address, Proof]))
            }

            (maybeMerkleTree, updatedLastCurrencySnapshotProofs).tupled
        }

        gsi = GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          transactionsRefs,
          updatedBalancesByRewards,
          updatedLastCurrencySnapshots,
          updatedLastCurrencySnapshotProofs,
          updatedAllowSpends,
          updatedTokenLockBalances.some
        )

        stateProof <- gsi.stateProof(maybeMerkleTree)

      } yield
        (
          acceptanceResult,
          scSnapshots,
          returnedSCEvents,
          acceptedRewardTxs,
          gsi,
          stateProof
        )
    }

    private def acceptAllowSpends(
      epochProgress: EpochProgress,
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState],
      lastActiveAllowSpends: SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): SortedMap[Address, SortedMap[Address, SortedSet[Signed[AllowSpend]]]] = {
      val allowSpendsFromCurrencySnapshots = currencySnapshots.mapFilter(_.toOption.flatMap { case (_, info) => info.activeAllowSpends })

      allowSpendsFromCurrencySnapshots.foldLeft(lastActiveAllowSpends) {
        case (accAllowSpends, (metagraphId, metagraphAllowSpends)) =>
          val lastActiveMetagraphAllowSpends =
            accAllowSpends.getOrElse(metagraphId, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])

          val updatedMetagraphAllowSpends =
            metagraphAllowSpends.foldLeft(lastActiveMetagraphAllowSpends) {
              case (accMetagraphAllowSpends, (address, addressAllowSpends)) =>
                val lastAddressAllowSpends = accMetagraphAllowSpends.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])

                val unexpired = (lastAddressAllowSpends ++ addressAllowSpends)
                  .filter(_.lastValidEpochProgress >= epochProgress)
                accMetagraphAllowSpends + (address -> unexpired)
            }

          accAllowSpends + (metagraphId -> updatedMetagraphAllowSpends)
      }
    }

    private def getTokenLocks(
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState]
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] = currencySnapshots.map {
      case (address, currencySnapshot) =>
        val tokenLocks = currencySnapshot.toOption.map {
          case (snapshot, _) =>
            snapshot.tokenLockBlocks
              .getOrElse(SortedSet.empty[Signed[tokenLock.TokenLockBlock]])
              .flatMap(_.value.tokenLocks.toSortedSet)
        }
          .getOrElse(SortedSet.empty[Signed[TokenLock]])

        address -> tokenLocks
    }

    private def getTokenUnlocks(
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState]
    ): SortedMap[Address, SortedSet[TokenUnlock]] =
      currencySnapshots.map {
        case (address, currencySnapshot) =>
          val tokenUnlocks: SortedSet[TokenUnlock] = currencySnapshot.toOption.map {
            case (snapshot, _) =>
              snapshot.artifacts.getOrElse(SortedSet.empty[SharedArtifact]).collect {
                case tokenUnlock: TokenUnlock => tokenUnlock
              }
          }
            .getOrElse(SortedSet.empty)

          address -> tokenUnlocks
      }

    private def updateTokenLockBalances(
      currencyTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      currencyTokenUnlocks: SortedMap[Address, SortedSet[TokenUnlock]],
      maybeLastTokenLockBalances: Option[SortedMap[Address, SortedMap[Address, Balance]]]
    ): SortedMap[Address, SortedMap[Address, Balance]] = {
      val lastTokenLockBalances = maybeLastTokenLockBalances.getOrElse(SortedMap.empty[Address, SortedMap[Address, Balance]])

      val balancesUpdatedByTokenLocks = currencyTokenLocks.foldLeft(lastTokenLockBalances) {
        case (accTokenLockBalances, (metagraphId, metagraphTokenLocks)) =>
          val lastMetagraphTokenLocksBalances =
            accTokenLockBalances.getOrElse(metagraphId, SortedMap.empty[Address, Balance])

          val updatedMetagraphTokenLocks = metagraphTokenLocks.foldLeft(lastMetagraphTokenLocksBalances) {
            case (accMetagraphTokenLocks, tokenLock) =>
              val lastAddressTokenLockBalance = accMetagraphTokenLocks.getOrElse(tokenLock.source, Balance.empty)
              val updatedBalance = lastAddressTokenLockBalance
                .plus(toAmount(tokenLock.amount))
                .getOrElse(lastAddressTokenLockBalance)

              accMetagraphTokenLocks.updated(tokenLock.source, updatedBalance)
          }

          accTokenLockBalances + (metagraphId -> updatedMetagraphTokenLocks)
      }

      currencyTokenUnlocks.foldLeft(balancesUpdatedByTokenLocks) {
        case (accTokenLockBalances, (metagraphId, metagraphTokenUnlocks)) =>
          val lastMetagraphTokenLocksBalances =
            accTokenLockBalances.getOrElse(metagraphId, SortedMap.empty[Address, Balance])

          val updatedMetagraphTokenLocksBalances = metagraphTokenUnlocks.foldLeft(lastMetagraphTokenLocksBalances) {
            case (accMetagraphTokenLocksBalances, tokenUnlock) =>
              val lastAddressTokenLockBalance = accMetagraphTokenLocksBalances.getOrElse(tokenUnlock.address, Balance.empty)
              val updatedBalance = lastAddressTokenLockBalance
                .minus(toAmount(tokenUnlock.amount))
                .getOrElse(lastAddressTokenLockBalance)

              accMetagraphTokenLocksBalances.updated(tokenUnlock.address, updatedBalance)
          }

          accTokenLockBalances + (metagraphId -> updatedMetagraphTokenLocksBalances)
      }
    }

    private def acceptTransactionRefs(
      lastTxRefs: SortedMap[Address, TransactionReference],
      lastTxRefsContextUpdate: Map[Address, TransactionReference],
      acceptedTransactions: SortedSet[Signed[Transaction]]
    ): SortedMap[Address, TransactionReference] = {
      val updatedRefs = lastTxRefs ++ lastTxRefsContextUpdate
      val newDestinationAddresses = acceptedTransactions.map(_.destination) -- updatedRefs.keySet
      updatedRefs ++ newDestinationAddresses.toList.map(_ -> TransactionReference.empty)
    }

    private def acceptBlocks(
      blocksForAcceptance: List[Signed[Block]],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      ordinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]) = {
      val tipUsages = getTipsUsages(lastActiveTips, lastDeprecatedTips)
      val context = BlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTxRefs,
        tipUsages,
        collateral,
        TransactionReference.empty
      )

      blockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, ordinal)
    }

    private def acceptRewardTxs(
      balances: SortedMap[Address, Balance],
      txs: SortedSet[RewardTransaction]
    ): (SortedMap[Address, Balance], SortedSet[RewardTransaction]) =
      txs.foldLeft((balances, SortedSet.empty[RewardTransaction])) { (acc, tx) =>
        val (updatedBalances, acceptedTxs) = acc

        updatedBalances
          .getOrElse(tx.destination, Balance.empty)
          .plus(tx.amount)
          .map(balance => (updatedBalances.updated(tx.destination, balance), acceptedTxs + tx))
          .getOrElse(acc)
      }

    def getTipsUsages(
      lastActive: Set[ActiveTip],
      lastDeprecated: Set[DeprecatedTip]
    ): Map[BlockReference, NonNegLong] = {
      val activeTipsUsages = lastActive.map(at => (at.block, at.usageCount)).toMap
      val deprecatedTipsUsages = lastDeprecated.map(dt => (dt.block, deprecationThreshold)).toMap

      activeTipsUsages ++ deprecatedTipsUsages
    }

  }

}
