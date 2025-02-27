package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.MonadThrow
import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshotV1, CurrencySnapshotInfoV1}
import io.constellationnetwork.ext.crypto._
import io.constellationnetwork.merkletree.Proof
import io.constellationnetwork.merkletree.syntax._
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersAcceptanceManager
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult
import io.constellationnetwork.node.shared.domain.statechannel.StateChannelAcceptanceResult.CurrencySnapshotWithState
import io.constellationnetwork.node.shared.domain.swap.SpendActionValidator
import io.constellationnetwork.node.shared.domain.swap.block.{
  AllowSpendBlockAcceptanceContext,
  AllowSpendBlockAcceptanceManager,
  AllowSpendBlockAcceptanceResult
}
import io.constellationnetwork.node.shared.domain.tokenlock.block.{
  TokenLockBlockAcceptanceContext,
  TokenLockBlockAcceptanceManager,
  TokenLockBlockAcceptanceResult
}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, SpendAction, TokenUnlock}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.UpdateNodeParameters
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock.TokenLockAmount.toAmount
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction.{RewardTransaction, Transaction, TransactionReference}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}
import io.constellationnetwork.syntax.sortedCollection.{sortedMapSyntax, sortedSetSyntax}

import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.disjunctionCodecs._
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait GlobalSnapshotAcceptanceManager[F[_]] {
  def accept(
    ordinal: SnapshotOrdinal,
    epochProgress: EpochProgress,
    blocksForAcceptance: List[Signed[Block]],
    allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
    tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
    scEvents: List[StateChannelOutput],
    unpEvents: List[Signed[UpdateNodeParameters]],
    lastSnapshotContext: GlobalSnapshotInfo,
    lastActiveTips: SortedSet[ActiveTip],
    lastDeprecatedTips: SortedSet[DeprecatedTip],
    calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
    validationType: StateChannelValidationType,
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
  ): F[
    (
      BlockAcceptanceResult,
      AllowSpendBlockAcceptanceResult,
      TokenLockBlockAcceptanceResult,
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput],
      SortedSet[RewardTransaction],
      GlobalSnapshotInfo,
      GlobalSnapshotStateProof,
      Map[Address, List[SpendAction]],
      SortedMap[Id, Signed[UpdateNodeParameters]]
    )
  ]
}

object GlobalSnapshotAcceptanceManager {

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: HasherSelector](
    blockAcceptanceManager: BlockAcceptanceManager[F],
    allowSpendBlockAcceptanceManager: AllowSpendBlockAcceptanceManager[F],
    tokenLockBlockAcceptanceManager: TokenLockBlockAcceptanceManager[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F],
    updateNodeParametersAcceptanceManager: UpdateNodeParametersAcceptanceManager[F],
    spendActionValidator: SpendActionValidator[F],
    collateral: Amount
  ) = new GlobalSnapshotAcceptanceManager[F] {

    def accept(
      ordinal: SnapshotOrdinal,
      epochProgress: EpochProgress,
      blocksForAcceptance: List[Signed[Block]],
      allowSpendBlocksForAcceptance: List[Signed[AllowSpendBlock]],
      tokenLockBlocksForAcceptance: List[Signed[TokenLockBlock]],
      scEvents: List[StateChannelOutput],
      unpEvents: List[Signed[UpdateNodeParameters]],
      lastSnapshotContext: GlobalSnapshotInfo,
      lastActiveTips: SortedSet[ActiveTip],
      lastDeprecatedTips: SortedSet[DeprecatedTip],
      calculateRewardsFn: SortedSet[Signed[Transaction]] => F[SortedSet[RewardTransaction]],
      validationType: StateChannelValidationType,
      lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]]
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
              validationType,
              lastGlobalSnapshots
            )

        spendActions = currencySnapshots.toList.map {
          case (_, Left(_))             => Map.empty[Address, List[SharedArtifact]]
          case (address, Right((s, _))) => Map(address -> (s.artifacts.getOrElse(SortedSet.empty[SharedArtifact]).toList))
        }
          .foldLeft(Map.empty[Address, List[SharedArtifact]])(_ |+| _)
          .view
          .mapValues(_.collect { case sa: SpendAction => sa })
          .filter { case (_, actions) => actions.nonEmpty }
          .toMap

        lastActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
          SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
        )

        acceptedSpendActions <- spendActions.toList.traverse {
          case (address, actions) =>
            actions.traverse { action =>
              spendActionValidator.validate(action, lastActiveAllowSpends).map(_.toOption)
            }.map(address -> _.flatten)
        }.map(_.filter { case (_, actions) => actions.nonEmpty }.toMap)

        _ <- Slf4jLogger.getLogger[F].debug(s"--- Accepted spend actions: ${acceptedSpendActions.show}")

        allAcceptedSpendTxnsAllowSpendsRefs = acceptedSpendActions.toList.flatMap {
          case (_, actions) =>
            actions.flatMap(action => List(action.input, action.output).flatMap(_.allowSpendRef))
        }

        sCSnapshotHashes <- scSnapshots.toList.traverse {
          case (address, nel) => nel.last.toHashed.map(address -> _.hash)
        }
          .map(_.toMap)
        updatedLastStateChannelSnapshotHashes = lastSnapshotContext.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
        updatedLastCurrencySnapshots = lastSnapshotContext.lastCurrencySnapshots ++ currencySnapshots

        allowSpendBlockAcceptanceResult <- acceptAllowSpendBlocks(
          allowSpendBlocksForAcceptance,
          lastSnapshotContext,
          ordinal
        )

        tokenLockBlockAcceptanceResult <- acceptTokenLockBlocks(
          tokenLockBlocksForAcceptance,
          lastSnapshotContext,
          ordinal
        )

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

        acceptedGlobalAllowSpends = allowSpendBlockAcceptanceResult.accepted.flatMap(_.value.transactions.toList)
        acceptedGlobalTokenLocks = tokenLockBlockAcceptanceResult.accepted.flatMap(_.value.tokenLocks.toList)

        activeAllowSpendsFromCurrencySnapshots = currencySnapshots.map { case (key, value) => (key.some, value) }
          .mapFilter(_.toOption.flatMap { case (_, info) => info.activeAllowSpends })

        globalAllowSpends = acceptedGlobalAllowSpends
          .groupBy(_.value.source)
          .view
          .mapValues(SortedSet.from(_))
          .to(SortedMap)

        globalTokenLocks = acceptedGlobalTokenLocks
          .groupBy(_.value.source)
          .view
          .mapValues(SortedSet.from(_))
          .to(SortedMap)

        allAcceptedSpendTxnsAllowSpendsRefs =
          acceptedSpendActions.values.flatten
            .flatMap(spendTxn => List(spendTxn.input, spendTxn.output))
            .flatMap(_.allowSpendRef)
            .toList

        globalActiveAllowSpends = lastSnapshotContext.activeAllowSpends.getOrElse(
          SortedMap.empty[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
        )
        globalActiveTokenLocks = lastSnapshotContext.activeTokenLocks.getOrElse(
          SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
        )

        globalLastAllowSpendRefs = lastSnapshotContext.lastAllowSpendRefs.getOrElse(
          SortedMap.empty[Address, AllowSpendReference]
        )
        globalLastTokenLockRefs = lastSnapshotContext.lastTokenLockRefs.getOrElse(
          SortedMap.empty[Address, TokenLockReference]
        )

        updatedAllowSpends <- acceptAllowSpends(
          epochProgress,
          activeAllowSpendsFromCurrencySnapshots,
          globalAllowSpends,
          globalActiveAllowSpends,
          allAcceptedSpendTxnsAllowSpendsRefs
        )

        updatedAllowSpendRefs = acceptAllowSpendRefs(
          globalLastAllowSpendRefs,
          allowSpendBlockAcceptanceResult.contextUpdate.lastTxRefs
        )

        updatedBalancesByAllowSpends = updateGlobalBalancesByAllowSpends(
          epochProgress,
          updatedBalancesByRewards,
          globalAllowSpends,
          globalActiveAllowSpends
        )

        updatedGlobalTokenLocks = acceptTokenLocks(
          epochProgress,
          globalTokenLocks,
          globalActiveTokenLocks
        )

        updatedTokenLockRefs = acceptTokenLockRefs(
          globalLastTokenLockRefs,
          tokenLockBlockAcceptanceResult.contextUpdate.lastTokenLocksRefs
        )

        updatedTokenLockBalances = updateTokenLockBalances(
          currencySnapshots,
          lastSnapshotContext.tokenLockBalances
        )

        updatedBalancesByTokenLocks = updateGlobalBalancesByTokenLocks(
          epochProgress,
          updatedBalancesByAllowSpends,
          globalTokenLocks,
          globalActiveTokenLocks
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

        acceptedUpdateNodeParameters <- updateNodeParametersAcceptanceManager
          .acceptUpdateNodeParameters(unpEvents, lastSnapshotContext)
          .map(acceptanceResult =>
            acceptanceResult.accepted.flatMap(signed => signed.proofs.toList.map(proof => (proof.id, signed))).toSortedMap
          )

        lastSnapshotUpdateNodeParameters = lastSnapshotContext.updateNodeParameters.getOrElse(
          SortedMap.empty[Id, (Signed[UpdateNodeParameters], SnapshotOrdinal)]
        )

        updatedUpdateNodeParameters = lastSnapshotUpdateNodeParameters ++ acceptedUpdateNodeParameters.view.mapValues(unp => (unp, ordinal))

        gsi = GlobalSnapshotInfo(
          updatedLastStateChannelSnapshotHashes,
          transactionsRefs,
          updatedBalancesByTokenLocks,
          updatedLastCurrencySnapshots,
          updatedLastCurrencySnapshotProofs,
          updatedAllowSpends.some,
          updatedGlobalTokenLocks.some,
          updatedTokenLockBalances.some,
          updatedAllowSpendRefs.some,
          updatedTokenLockRefs.some,
          updatedUpdateNodeParameters.some
        )

        stateProof <- gsi.stateProof(maybeMerkleTree)

      } yield
        (
          acceptanceResult,
          allowSpendBlockAcceptanceResult,
          tokenLockBlockAcceptanceResult,
          scSnapshots,
          returnedSCEvents,
          acceptedRewardTxs,
          gsi,
          stateProof,
          acceptedSpendActions,
          acceptedUpdateNodeParameters
        )
    }

    private def acceptAllowSpends(
      epochProgress: EpochProgress,
      activeAllowSpendsFromCurrencySnapshots: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]],
      allAcceptedSpendTxnsAllowSpendsRefs: List[Hash]
    )(implicit hasher: Hasher[F]): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = lastActiveGlobalAllowSpends.collect {
        case (address, allowSpends) => address -> allowSpends.filter(_.lastValidEpochProgress < epochProgress)
      }

      val updatedGlobalAllowSpends = (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft(lastActiveGlobalAllowSpends) {
        case (acc, (address, allowSpends)) =>
          val lastAddressAllowSpends = acc.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])
          val unexpired = (lastAddressAllowSpends ++ allowSpends).filter(_.lastValidEpochProgress >= epochProgress)
          acc + (address -> unexpired)
      }

      def processMetagraphAllowSpends(
        metagraphId: Option[Address],
        metagraphAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
        accAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
      ): F[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]] = {
        val lastActiveMetagraphAllowSpends = accAllowSpends.getOrElse(metagraphId, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])

        metagraphAllowSpends.toList.traverse {
          case (address, addressAllowSpends) =>
            val lastAddressAllowSpends = lastActiveMetagraphAllowSpends.getOrElse(address, SortedSet.empty[Signed[AllowSpend]])

            val validAllowSpendsF = (lastAddressAllowSpends ++ addressAllowSpends)
              .filter(_.lastValidEpochProgress >= epochProgress)
              .toList
              .traverse(_.toHashed)
              .map { hashedAllowSpends =>
                hashedAllowSpends.filterNot(h => allAcceptedSpendTxnsAllowSpendsRefs.contains(h.hash))
              }
              .map(_.map(_.signed).toSortedSet)

            validAllowSpendsF.map(validAllowSpends => address -> validAllowSpends)
        }.map { updatedMetagraphAllowSpends =>
          accAllowSpends + (metagraphId -> SortedMap(updatedMetagraphAllowSpends: _*))
        }
      }

      activeAllowSpendsFromCurrencySnapshots.toList
        .foldLeft(lastActiveAllowSpends.pure[F]) {
          case (accAllowSpendsF, (metagraphId, metagraphAllowSpends)) =>
            for {
              accAllowSpends <- accAllowSpendsF
              updatedAllowSpends <- processMetagraphAllowSpends(metagraphId, metagraphAllowSpends, accAllowSpends)
            } yield updatedAllowSpends
        }
        .map { updatedCurrencyAllowSpends =>
          if (updatedGlobalAllowSpends.nonEmpty)
            updatedCurrencyAllowSpends + (None -> updatedGlobalAllowSpends)
          else
            updatedCurrencyAllowSpends
        }
    }

    private def acceptTokenLocks(
      epochProgress: EpochProgress,
      acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    ): SortedMap[Address, SortedSet[Signed[TokenLock]]] = {
      val expiredGlobalTokenLocks = lastActiveGlobalTokenLocks.collect {
        case (address, tokenLocks) => address -> tokenLocks.filter(_.unlockEpoch < epochProgress)
      }

      (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).foldLeft(lastActiveGlobalTokenLocks) {
        case (acc, (address, tokenLocks)) =>
          val lastAddressTokenLocks = acc.getOrElse(address, SortedSet.empty[Signed[TokenLock]])
          val unexpired = (lastAddressTokenLocks ++ tokenLocks).filter(_.unlockEpoch >= epochProgress)
          acc + (address -> unexpired)
      }
    }

    private def updateTokenLockBalances(
      currencySnapshots: SortedMap[Address, CurrencySnapshotWithState],
      maybeLastTokenLockBalances: Option[SortedMap[Address, SortedMap[Address, Balance]]]
    ): SortedMap[Address, SortedMap[Address, Balance]] = {
      val lastTokenLockBalances = maybeLastTokenLockBalances.getOrElse(SortedMap.empty[Address, SortedMap[Address, Balance]])

      currencySnapshots.foldLeft(lastTokenLockBalances) {
        case (accTokenLockBalances, (metagraphId, currencySnapshotWithState)) =>
          val activeTokenLocks = currencySnapshotWithState match {
            case Left(_)          => SortedMap.empty[Address, SortedSet[Signed[TokenLock]]]
            case Right((_, info)) => info.activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
          }

          val metagraphTokenLocksAmounts = activeTokenLocks.foldLeft(SortedMap.empty[Address, Balance]) {
            case (accTokenLockBalances, addressTokenLocks) =>
              val (address, tokenLocks) = addressTokenLocks
              val amount = NonNegLong.unsafeFrom(tokenLocks.toList.map(_.amount.value.value).sum)
              accTokenLockBalances.updated(address, Balance(amount))
          }

          accTokenLockBalances + (metagraphId -> metagraphTokenLocksAmounts)
      }
    }

    private def updateGlobalBalancesByAllowSpends(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      globalAllowSpends: SortedMap[Address, SortedSet[Signed[AllowSpend]]],
      lastActiveAllowSpends: SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]
    ): SortedMap[Address, Balance] = {
      val lastActiveGlobalAllowSpends = lastActiveAllowSpends.getOrElse(None, SortedMap.empty[Address, SortedSet[Signed[AllowSpend]]])
      val expiredGlobalAllowSpends = lastActiveGlobalAllowSpends.collect {
        case (address, allowSpends) => address -> allowSpends.filter(_.lastValidEpochProgress < epochProgress)
      }

      (globalAllowSpends |+| expiredGlobalAllowSpends).foldLeft(currentBalances) {
        case (acc, (address, allowSpends)) =>
          val unexpired = allowSpends.filter(_.lastValidEpochProgress >= epochProgress)
          val expired = allowSpends.filter(_.lastValidEpochProgress < epochProgress)

          val updatedBalanceUnexpired =
            unexpired.foldLeft(acc.getOrElse(address, Balance.empty)) { (currentBalance, allowSpend) =>
              currentBalance
                .minus(SwapAmount.toAmount(allowSpend.amount))
                .getOrElse(currentBalance)
                .minus(AllowSpendFee.toAmount(allowSpend.fee))
                .getOrElse(currentBalance)
            }
          val updatedBalanceExpired = expired.foldLeft(updatedBalanceUnexpired) { (currentBalance, allowSpend) =>
            currentBalance
              .plus(SwapAmount.toAmount(allowSpend.amount))
              .getOrElse(currentBalance)
          }

          acc.updated(address, updatedBalanceExpired)
      }
    }

    private def updateGlobalBalancesByTokenLocks(
      epochProgress: EpochProgress,
      currentBalances: SortedMap[Address, Balance],
      acceptedGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]],
      lastActiveGlobalTokenLocks: SortedMap[Address, SortedSet[Signed[TokenLock]]]
    ): SortedMap[Address, Balance] = {
      val expiredGlobalTokenLocks = lastActiveGlobalTokenLocks.collect {
        case (address, allowSpends) => address -> allowSpends.filter(_.unlockEpoch < epochProgress)
      }

      (acceptedGlobalTokenLocks |+| expiredGlobalTokenLocks).foldLeft(currentBalances) {
        case (acc, (address, allowSpends)) =>
          val unexpired = allowSpends.filter(_.unlockEpoch >= epochProgress)
          val expired = allowSpends.filter(_.unlockEpoch < epochProgress)

          val updatedBalanceUnexpired =
            unexpired.foldLeft(acc.getOrElse(address, Balance.empty)) { (currentBalance, allowSpend) =>
              currentBalance
                .minus(TokenLockAmount.toAmount(allowSpend.amount))
                .getOrElse(currentBalance)
            }
          val updatedBalanceExpired = expired.foldLeft(updatedBalanceUnexpired) { (currentBalance, allowSpend) =>
            currentBalance
              .plus(TokenLockAmount.toAmount(allowSpend.amount))
              .getOrElse(currentBalance)
          }

          acc.updated(address, updatedBalanceExpired)
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

    private def acceptAllowSpendRefs(
      lastAllowSpendRefs: SortedMap[Address, AllowSpendReference],
      lastAllowSpendContextUpdate: Map[Address, AllowSpendReference]
    ): SortedMap[Address, AllowSpendReference] =
      lastAllowSpendRefs ++ lastAllowSpendContextUpdate

    private def acceptTokenLockRefs(
      lastTokenLockRefs: SortedMap[Address, TokenLockReference],
      lastTokenLockContextUpdate: Map[Address, TokenLockReference]
    ): SortedMap[Address, TokenLockReference] =
      lastTokenLockRefs ++ lastTokenLockContextUpdate

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

    private def acceptAllowSpendBlocks(
      blocksForAcceptance: List[Signed[AllowSpendBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      snapshotOrdinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]) = {
      val context = AllowSpendBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastAllowSpendRefs.getOrElse(Map.empty),
        collateral,
        AllowSpendReference.empty
      )

      allowSpendBlockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, snapshotOrdinal)
    }

    private def acceptTokenLockBlocks(
      blocksForAcceptance: List[Signed[TokenLockBlock]],
      lastSnapshotContext: GlobalSnapshotInfo,
      snapshotOrdinal: SnapshotOrdinal
    )(implicit hasher: Hasher[F]) = {
      val context = TokenLockBlockAcceptanceContext.fromStaticData(
        lastSnapshotContext.balances,
        lastSnapshotContext.lastTokenLockRefs.getOrElse(Map.empty),
        collateral,
        TokenLockReference.empty
      )

      tokenLockBlockAcceptanceManager.acceptBlocksIteratively(blocksForAcceptance, context, snapshotOrdinal)
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
