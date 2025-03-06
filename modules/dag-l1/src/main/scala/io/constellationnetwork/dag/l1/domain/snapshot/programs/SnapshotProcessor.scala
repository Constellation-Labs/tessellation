package io.constellationnetwork.dag.l1.domain.snapshot.programs

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.all._
import cats.{Applicative, MonadThrow}

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.util.control.NoStackTrace

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import io.constellationnetwork.dag.l1.domain.block.{BlockRelations, BlockStorage}
import io.constellationnetwork.dag.l1.domain.transaction.TransactionStorage
import io.constellationnetwork.node.shared.domain.snapshot.Validator
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.swap.AllowSpendStorage
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.schema.swap.AllowSpendReference
import io.constellationnetwork.schema.tokenLock.{TokenLockBlock, TokenLockReference}
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class SnapshotProcessor[
  F[_]: Async: SecurityProvider,
  P <: StateProof,
  S <: Snapshot,
  SI <: SnapshotInfo[P]
] {
  import SnapshotProcessor._

  def process(
    snapshot: Either[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo), Hashed[GlobalIncrementalSnapshot]]
  )(implicit hasher: Hasher[F]): F[SnapshotProcessingResult]

  def applyGlobalSnapshotFn(
    lastGlobalState: GlobalSnapshotInfo,
    lastGlobalSnapshot: Signed[GlobalIncrementalSnapshot],
    globalSnapshot: Signed[GlobalIncrementalSnapshot],
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[GlobalSnapshotInfo]

  def applySnapshotFn(
    lastState: SI,
    lastSnapshot: Signed[S],
    snapshot: Signed[S],
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[SI]

  def onDownload(snapshot: Hashed[S], state: SI): F[Unit] = Applicative[F].unit
  def onRedownload(snapshot: Hashed[S], state: SI): F[Unit] = Applicative[F].unit
  def setLastNSnapshots(snapshot: Hashed[S], state: SI): F[Unit] = Applicative[F].unit
  def setInitialLastNSnapshots(snapshot: Hashed[S], state: SI): F[Unit] = Applicative[F].unit

  def processAlignment(
    alignment: Alignment,
    blockStorage: BlockStorage[F],
    transactionStorage: TransactionStorage[F],
    allowSpendStorage: AllowSpendStorage[F],
    tokenLockStorage: TokenLockStorage[F],
    lastSnapshotStorage: LastSnapshotStorage[F, S, SI],
    addressStorage: AddressStorage[F]
  ): F[SnapshotProcessingResult] =
    alignment match {
      case AlignedAtNewOrdinal(
            snapshot,
            state,
            toMarkMajority,
            tipsToDeprecate,
            tipsToRemove,
            txRefsToMarkMajority,
            allowSpendRefsToMarkMajority,
            tokenLockRefsToMarkMajority,
            postponedToWaiting
          ) =>
        val adjustToMajority: F[Unit] =
          blockStorage
            .adjustToMajority(
              toMarkMajority = toMarkMajority,
              tipsToDeprecate = tipsToDeprecate,
              tipsToRemove = tipsToRemove,
              postponedToWaiting = postponedToWaiting
            )

        val markTxRefsAsMajority: F[Unit] =
          transactionStorage.advanceMajorityRefs(txRefsToMarkMajority, snapshot.ordinal) >>
            allowSpendStorage.advanceMajorityRefs(allowSpendRefsToMarkMajority, snapshot.ordinal) >>
            tokenLockStorage.advanceMajorityRefs(tokenLockRefsToMarkMajority, snapshot.ordinal)

        val setSnapshot: F[Unit] =
          lastSnapshotStorage.set(snapshot, state)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot >>
          setLastNSnapshots(snapshot, state).as[SnapshotProcessingResult] {
            Aligned(
              SnapshotReference.fromHashedSnapshot(snapshot),
              Set.empty
            )
          }

      case AlignedAtNewHeight(
            snapshot,
            state,
            toMarkMajority,
            obsoleteToRemove,
            tipsToDeprecate,
            tipsToRemove,
            txRefsToMarkMajority,
            allowSpendRefsToMarkMajority,
            tokenLockRefsToMarkMajority,
            postponedToWaiting
          ) =>
        val adjustToMajority: F[Unit] =
          blockStorage
            .adjustToMajority(
              toMarkMajority = toMarkMajority,
              obsoleteToRemove = obsoleteToRemove,
              tipsToDeprecate = tipsToDeprecate,
              tipsToRemove = tipsToRemove,
              postponedToWaiting = postponedToWaiting
            )

        val markTxRefsAsMajority: F[Unit] =
          transactionStorage.advanceMajorityRefs(txRefsToMarkMajority, snapshot.ordinal) >>
            allowSpendStorage.advanceMajorityRefs(allowSpendRefsToMarkMajority, snapshot.ordinal) >>
            tokenLockStorage.advanceMajorityRefs(tokenLockRefsToMarkMajority, snapshot.ordinal)

        val setSnapshot: F[Unit] =
          lastSnapshotStorage.set(snapshot, state)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot >>
          setLastNSnapshots(snapshot, state).as[SnapshotProcessingResult] {
            Aligned(
              SnapshotReference.fromHashedSnapshot(snapshot),
              obsoleteToRemove
            )
          }

      case DownloadNeeded(snapshot, state, toAdd, obsoleteToRemove, activeTips, deprecatedTips, relatedPostponed) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            obsoleteToRemove = obsoleteToRemove,
            activeTipsToAdd = activeTips,
            deprecatedTipsToAdd = deprecatedTips,
            postponedToWaiting = relatedPostponed
          ) >> onDownload(snapshot, state)

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(state.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.initByRefs(state.lastTxRefs, snapshot.ordinal)

        val setInitialSnapshot: F[Unit] =
          lastSnapshotStorage.setInitial(snapshot, state)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setInitialSnapshot >>
          setInitialLastNSnapshots(snapshot, state).as[SnapshotProcessingResult] {
            DownloadPerformed(
              SnapshotReference.fromHashedSnapshot(snapshot),
              toAdd.map(_._1.proofsHash),
              obsoleteToRemove
            )
          }

      case RedownloadNeeded(
            snapshot,
            state,
            toAdd,
            toMarkMajority,
            acceptedToRemove,
            obsoleteToRemove,
            toReset,
            tipsToDeprecate,
            tipsToRemove,
            postponedToWaiting
          ) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            toMarkMajority = toMarkMajority,
            acceptedToRemove = acceptedToRemove,
            obsoleteToRemove = obsoleteToRemove,
            toReset = toReset,
            tipsToDeprecate = tipsToDeprecate,
            tipsToRemove = tipsToRemove,
            postponedToWaiting = postponedToWaiting
          ) >> onRedownload(snapshot, state)

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(state.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.replaceByRefs(state.lastTxRefs, snapshot.ordinal)

        val setSnapshot: F[Unit] =
          lastSnapshotStorage.set(snapshot, state)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setSnapshot >>
          setLastNSnapshots(snapshot, state).as[SnapshotProcessingResult] {
            RedownloadPerformed(
              SnapshotReference.fromHashedSnapshot(snapshot),
              toAdd.map(_._1.proofsHash),
              acceptedToRemove,
              obsoleteToRemove
            )
          }

      case Ignore(snapshot, lastHeight, lastSubHeight, lastOrdinal, processingHeight, processingSubHeight, processingOrdinal) =>
        Slf4jLogger
          .getLogger[F]
          .warn(
            s"Unexpected case during global snapshot processing - ignoring snapshot! Last: (height: $lastHeight, subHeight: $lastSubHeight, ordinal: $lastOrdinal) processing: (height: $processingHeight, subHeight:$processingSubHeight, ordinal: $processingOrdinal)."
          )
          .as(SnapshotIgnored(SnapshotReference.fromHashedSnapshot(snapshot)))
    }

  def checkAlignment(
    snapshotWithState: Either[(Hashed[S], SI), Hashed[S]],
    blockStorage: BlockStorage[F],
    lastSnapshotStorage: LastSnapshotStorage[F, S, SI],
    txHasher: Hasher[F],
    lastGlobalSnapshots: Option[List[Hashed[GlobalIncrementalSnapshot]]],
    getGlobalSnapshotByOrdinal: SnapshotOrdinal => F[Option[Hashed[GlobalIncrementalSnapshot]]]
  )(implicit hasher: Hasher[F]): F[Alignment] = {
    val snapshot = snapshotWithState.fold({ case (snapshot, _) => snapshot }, identity)
    val (blocks, allowSpendBlocks, tokenLockBlocks) = snapshot.signed.value match {
      case currencySnapshot: CurrencyIncrementalSnapshot =>
        (
          currencySnapshot.blocks,
          currencySnapshot.allowSpendBlocks.getOrElse(SortedSet.empty[Signed[swap.AllowSpendBlock]]),
          currencySnapshot.tokenLockBlocks.getOrElse(SortedSet.empty[Signed[tokenLock.TokenLockBlock]])
        )
      case globalIncrementalSnapshot: GlobalIncrementalSnapshot =>
        (
          globalIncrementalSnapshot.blocks,
          globalIncrementalSnapshot.allowSpendBlocks.getOrElse(SortedSet.empty[Signed[swap.AllowSpendBlock]]),
          globalIncrementalSnapshot.tokenLockBlocks.getOrElse(SortedSet.empty[Signed[tokenLock.TokenLockBlock]])
        )
      case _ =>
        (snapshot.blocks, SortedSet.empty[Signed[swap.AllowSpendBlock]], SortedSet.empty[Signed[tokenLock.TokenLockBlock]])
    }

    val acceptedBlocksF = blocks.toList.traverse {
      case BlockAsActiveTip(block, usageCount) =>
        block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
    }
      .map(_.toMap)

    val acceptedAllowSpendBlocksF = allowSpendBlocks.toList.traverse { block =>
      block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.hash -> b)
    }
      .map(_.toMap)

    val acceptedTokenLockBlocksF = tokenLockBlocks.toList.traverse { block =>
      block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.hash -> b)
    }
      .map(_.toMap)

    (acceptedBlocksF, acceptedAllowSpendBlocksF, acceptedTokenLockBlocksF).mapN {
      (acceptedInMajority, acceptedAllowSpendInMajority, acceptedTokenLockInMajority) =>
        snapshotWithState match {
          case Left((snapshot, state)) =>
            val SnapshotTips(snapshotDeprecatedTips, snapshotRemainedActive) = snapshot.tips

            lastSnapshotStorage.getCombined.flatMap {
              case None =>
                val isDependent = (block: Signed[Block]) =>
                  BlockRelations.dependsOn[F](
                    acceptedInMajority.values.map(_._1).toSet,
                    snapshotDeprecatedTips.map(_.block) ++ snapshotRemainedActive.map(_.block),
                    txHasher
                  )(block)

                blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, snapshot.height, isDependent).flatMap {
                  case MajorityReconciliationData(_, _, waitingInRange, postponedInRange, relatedPostponed, _, _) =>
                    val initialBlocksAndTips =
                      acceptedInMajority.keySet ++ snapshotRemainedActive.map(_.block.hash) ++ snapshotDeprecatedTips.map(_.block.hash)
                    val obsoleteToRemove = waitingInRange ++ postponedInRange -- initialBlocksAndTips
                    val postponedToWaiting = relatedPostponed -- postponedInRange -- initialBlocksAndTips

                    Applicative[F].pure[Alignment](
                      DownloadNeeded(
                        snapshot,
                        state,
                        acceptedInMajority.values.toSet,
                        obsoleteToRemove,
                        snapshotRemainedActive,
                        snapshotDeprecatedTips.map(_.block),
                        postponedToWaiting
                      )
                    )
                }
              case _ => (new Throwable("unexpected state: latest snapshot found")).raiseError[F, Alignment]
            }
          case Right(snapshot) =>
            val SnapshotTips(snapshotDeprecatedTips, snapshotRemainedActive) = snapshot.tips
            lastSnapshotStorage.getCombined.flatMap {
              case Some((lastSnapshot, lastState)) =>
                Validator.compare[S](lastSnapshot, snapshot.signed.value) match {
                  case Validator.NextSubHeight =>
                    val isDependent =
                      (block: Signed[Block]) =>
                        BlockRelations.dependsOn[F](acceptedInMajority.values.map(_._1).toSet, txHasher = txHasher)(block)

                    applySnapshotFn(
                      lastState,
                      lastSnapshot.signed,
                      snapshot.signed,
                      lastGlobalSnapshots,
                      getGlobalSnapshotByOrdinal
                    ).flatMap { state =>
                      blockStorage.getBlocksForMajorityReconciliation(lastSnapshot.height, snapshot.height, isDependent).flatMap {
                        case MajorityReconciliationData(deprecatedTips, activeTips, _, _, relatedPostponed, _, acceptedAbove) =>
                          val onlyInMajority = acceptedInMajority -- acceptedAbove
                          val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
                          lazy val toAdd = onlyInMajority.values.toSet
                          lazy val toReset = acceptedAbove -- toMarkMajority.keySet
                          val tipsToRemove = deprecatedTips -- snapshotDeprecatedTips.map(_.block.hash)
                          val deprecatedTipsToAdd = snapshotDeprecatedTips.map(_.block.hash) -- deprecatedTips
                          val tipsToDeprecate = activeTips -- snapshotRemainedActive.map(_.block.hash)
                          val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
                          lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, state)
                          lazy val allowSpendsToMarkMajority = extractMajorityAllowSpendsTxRefs(acceptedAllowSpendInMajority, state)
                          lazy val tokenLocksToMarkMajority = extractMajorityTokenLocksTxRefs(acceptedTokenLockInMajority, state)
                          lazy val postponedToWaiting = relatedPostponed -- toAdd.map(_._1.proofsHash) -- toReset

                          if (!areTipsAligned)
                            MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
                          else if (onlyInMajority.isEmpty)
                            Applicative[F].pure[Alignment](
                              AlignedAtNewOrdinal(
                                snapshot,
                                state,
                                toMarkMajority.toSet,
                                tipsToDeprecate,
                                tipsToRemove,
                                txRefsToMarkMajority,
                                allowSpendsToMarkMajority,
                                tokenLocksToMarkMajority,
                                postponedToWaiting
                              )
                            )
                          else
                            Applicative[F]
                              .pure[Alignment](
                                RedownloadNeeded(
                                  snapshot,
                                  state,
                                  toAdd,
                                  toMarkMajority.toSet,
                                  Set.empty,
                                  Set.empty,
                                  toReset,
                                  tipsToDeprecate,
                                  tipsToRemove,
                                  postponedToWaiting
                                )
                              )
                      }
                    }

                  case Validator.NextHeight =>
                    val isDependent =
                      (block: Signed[Block]) =>
                        BlockRelations.dependsOn[F](acceptedInMajority.values.map(_._1).toSet, txHasher = txHasher)(block)

                    applySnapshotFn(
                      lastState,
                      lastSnapshot.signed,
                      snapshot.signed,
                      lastGlobalSnapshots,
                      getGlobalSnapshotByOrdinal
                    ).flatMap { state =>
                      blockStorage.getBlocksForMajorityReconciliation(lastSnapshot.height, snapshot.height, isDependent).flatMap {
                        case MajorityReconciliationData(
                              deprecatedTips,
                              activeTips,
                              waitingInRange,
                              postponedInRange,
                              relatedPostponed,
                              acceptedInRange,
                              acceptedAbove
                            ) =>
                          val acceptedLocally = acceptedInRange ++ acceptedAbove
                          val onlyInMajority = acceptedInMajority -- acceptedLocally
                          val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedLocally.contains).mapValues(_._2)
                          val acceptedToRemove = acceptedInRange -- acceptedInMajority.keySet
                          lazy val toAdd = onlyInMajority.values.toSet
                          lazy val toReset = acceptedLocally -- toMarkMajority.keySet -- acceptedToRemove
                          val obsoleteToRemove = waitingInRange ++ postponedInRange -- onlyInMajority.keySet
                          val tipsToRemove = deprecatedTips -- snapshotDeprecatedTips.map(_.block.hash)
                          val deprecatedTipsToAdd = snapshotDeprecatedTips.map(_.block.hash) -- deprecatedTips
                          val tipsToDeprecate = activeTips -- snapshotRemainedActive.map(_.block.hash)
                          val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
                          lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, state)
                          lazy val allowSpendsToMarkMajority = extractMajorityAllowSpendsTxRefs(acceptedAllowSpendInMajority, state)
                          lazy val tokenLocksToMarkMajority = extractMajorityTokenLocksTxRefs(acceptedTokenLockInMajority, state)
                          lazy val postponedToWaiting = relatedPostponed -- obsoleteToRemove -- toAdd.map(_._1.proofsHash) -- toReset

                          if (!areTipsAligned)
                            MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
                          else if (onlyInMajority.isEmpty && acceptedToRemove.isEmpty)
                            Applicative[F].pure[Alignment](
                              AlignedAtNewHeight(
                                snapshot,
                                state,
                                toMarkMajority.toSet,
                                obsoleteToRemove,
                                tipsToDeprecate,
                                tipsToRemove,
                                txRefsToMarkMajority,
                                allowSpendsToMarkMajority,
                                tokenLocksToMarkMajority,
                                postponedToWaiting
                              )
                            )
                          else
                            Applicative[F].pure[Alignment](
                              RedownloadNeeded(
                                snapshot,
                                state,
                                toAdd,
                                toMarkMajority.toSet,
                                acceptedToRemove,
                                obsoleteToRemove,
                                toReset,
                                tipsToDeprecate,
                                tipsToRemove,
                                postponedToWaiting
                              )
                            )
                      }
                    }

                  case Validator.NotNext =>
                    Applicative[F].pure[Alignment](
                      Ignore(
                        snapshot,
                        lastSnapshot.height,
                        lastSnapshot.subHeight,
                        lastSnapshot.ordinal,
                        snapshot.height,
                        snapshot.subHeight,
                        snapshot.ordinal
                      )
                    )
                }
              case None => (new Throwable("unexpected state: latest snapshot not found")).raiseError[F, Alignment]

            }
        }
    }.flatten
  }

  private def extractMajorityTxRefs(
    acceptedInMajority: Map[ProofsHash, (Hashed[Block], NonNegLong)],
    state: SI
  ): Map[Address, TransactionReference] = {
    val transactions = acceptedInMajority.values.flatMap(_._1.transactions.toSortedSet)
    val sourceAddresses = transactions.map(_.source).toSet
    val newDestinationAddresses = transactions.map(_.destination).toSet -- sourceAddresses

    state.lastTxRefs.view.filterKeys(address => sourceAddresses.contains(address) || newDestinationAddresses.contains(address)).toMap
  }

  private def extractMajorityAllowSpendsTxRefs(
    acceptedInMajority: Map[Hash, Hashed[swap.AllowSpendBlock]],
    state: SI
  ): Map[Address, AllowSpendReference] = {
    val transactions = acceptedInMajority.values.flatMap(_.transactions.toSortedSet)
    val sourceAddresses = transactions.map(_.source).toSet
    val newDestinationAddresses = transactions.map(_.destination).toSet -- sourceAddresses

    state match {
      case currencyState: CurrencySnapshotInfo =>
        currencyState.lastAllowSpendRefs
          .getOrElse(SortedMap.empty[Address, AllowSpendReference])
          .view
          .filterKeys(address => sourceAddresses.contains(address) || newDestinationAddresses.contains(address))
          .toMap
      case globalState: GlobalSnapshotInfo =>
        globalState.lastAllowSpendRefs
          .getOrElse(SortedMap.empty[Address, AllowSpendReference])
          .view
          .filterKeys(address => sourceAddresses.contains(address) || newDestinationAddresses.contains(address))
          .toMap
      case _ => Map.empty
    }

  }

  private def extractMajorityTokenLocksTxRefs(
    acceptedInMajority: Map[Hash, Hashed[TokenLockBlock]],
    state: SI
  ): Map[Address, TokenLockReference] = {
    val transactions = acceptedInMajority.values.flatMap(_.tokenLocks.toSortedSet)
    val sourceAddresses = transactions.map(_.source).toSet

    state match {
      case currencyState: CurrencySnapshotInfo =>
        currencyState.lastTokenLockRefs
          .getOrElse(SortedMap.empty[Address, TokenLockReference])
          .view
          .filterKeys(address => sourceAddresses.contains(address))
          .toMap
      case globalState: GlobalSnapshotInfo =>
        globalState.lastTokenLockRefs
          .getOrElse(SortedMap.empty[Address, TokenLockReference])
          .view
          .filterKeys(address => sourceAddresses.contains(address))
          .toMap
      case _ => Map.empty
    }

  }
  sealed trait Alignment

  case class AlignedAtNewOrdinal(
    snapshot: Hashed[S],
    state: SI,
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    txRefsToMarkMajority: Map[Address, TransactionReference],
    allowSpendRefsToMarkMajority: Map[Address, AllowSpendReference],
    tokenLockRefsToMarkMajority: Map[Address, TokenLockReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment

  case class AlignedAtNewHeight(
    snapshot: Hashed[S],
    state: SI,
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    txRefsToMarkMajority: Map[Address, TransactionReference],
    allowSpendRefsToMarkMajority: Map[Address, AllowSpendReference],
    tokenLockRefsToMarkMajority: Map[Address, TokenLockReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment

  case class DownloadNeeded(
    snapshot: Hashed[S],
    state: SI,
    toAdd: Set[(Hashed[Block], NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    activeTips: Set[ActiveTip],
    deprecatedTips: Set[BlockReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment

  case class RedownloadNeeded(
    snapshot: Hashed[S],
    state: SI,
    toAdd: Set[(Hashed[Block], NonNegLong)],
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    acceptedToRemove: Set[ProofsHash],
    obsoleteToRemove: Set[ProofsHash],
    toReset: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment

  case class Ignore(
    snapshot: Hashed[S],
    lastHeight: Height,
    lastSubHeight: SubHeight,
    lastOrdinal: SnapshotOrdinal,
    processingHeight: Height,
    processingSubHeight: SubHeight,
    processingOrdinal: SnapshotOrdinal
  ) extends Alignment
}

object SnapshotProcessor {
  @derive(show)
  sealed trait SnapshotProcessingResult

  case class Aligned(
    reference: SnapshotReference,
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult

  case class DownloadPerformed(
    reference: SnapshotReference,
    addedBlock: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult

  case class RedownloadPerformed(
    reference: SnapshotReference,
    addedBlocks: Set[ProofsHash],
    removedBlocks: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult

  case class BatchResult(globalSnapshotResult: SnapshotProcessingResult, results: NonEmptyList[SnapshotProcessingResult])
      extends SnapshotProcessingResult

  case class SnapshotIgnored(
    reference: SnapshotReference
  ) extends SnapshotProcessingResult

  sealed trait SnapshotProcessingError extends NoStackTrace
  case class TipsGotMisaligned(deprecatedToAdd: Set[ProofsHash], activeToDeprecate: Set[ProofsHash]) extends SnapshotProcessingError {
    override def getMessage: String =
      s"Tips got misaligned! Check the implementation! deprecatedToAdd -> $deprecatedToAdd not equal activeToDeprecate -> $activeToDeprecate"
  }
}
