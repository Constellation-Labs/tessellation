package org.tessellation.dag.l1.domain.snapshot.programs

import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.{DAGBlock, DAGBlockAsActiveTip}
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import org.tessellation.dag.l1.domain.block.{BlockRelations, BlockStorage}
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.snapshot._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.transaction.TransactionReference
import org.tessellation.sdk.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

object SnapshotProcessor {

  def make[F[_]: Async: KryoSerializer: SecurityProvider](
    addressStorage: AddressStorage[F],
    blockStorage: BlockStorage[F],
    lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
    transactionStorage: TransactionStorage[F]
  ): SnapshotProcessor[F] =
    new SnapshotProcessor[F](addressStorage, blockStorage, lastGlobalSnapshotStorage, transactionStorage) {}

  sealed trait Alignment
  case class AlignedAtNewOrdinal(
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    txRefsToMarkMajority: Map[Address, TransactionReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment
  case class AlignedAtNewHeight(
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    txRefsToMarkMajority: Map[Address, TransactionReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment
  case class DownloadNeeded(
    toAdd: Set[(Hashed[DAGBlock], NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    activeTips: Set[ActiveTip],
    deprecatedTips: Set[BlockReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment
  case class RedownloadNeeded(
    toAdd: Set[(Hashed[DAGBlock], NonNegLong)],
    toMarkMajority: Set[(ProofsHash, NonNegLong)],
    acceptedToRemove: Set[ProofsHash],
    obsoleteToRemove: Set[ProofsHash],
    toReset: Set[ProofsHash],
    tipsToDeprecate: Set[ProofsHash],
    tipsToRemove: Set[ProofsHash],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment
  case class Ignore(
    lastHeight: Height,
    lastSubHeight: SubHeight,
    lastOrdinal: SnapshotOrdinal,
    processingHeight: Height,
    processingSubHeight: SubHeight,
    processingOrdinal: SnapshotOrdinal
  ) extends Alignment

  @derive(show)
  sealed trait SnapshotProcessingResult
  case class Aligned(
    reference: GlobalSnapshotReference,
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class DownloadPerformed(
    reference: GlobalSnapshotReference,
    addedBlock: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class RedownloadPerformed(
    reference: GlobalSnapshotReference,
    addedBlocks: Set[ProofsHash],
    removedBlocks: Set[ProofsHash],
    removedObsoleteBlocks: Set[ProofsHash]
  ) extends SnapshotProcessingResult
  case class SnapshotIgnored(
    reference: GlobalSnapshotReference
  ) extends SnapshotProcessingResult

  sealed trait SnapshotProcessingError extends NoStackTrace
  case class TipsGotMisaligned(deprecatedToAdd: Set[ProofsHash], activeToDeprecate: Set[ProofsHash]) extends SnapshotProcessingError {
    override def getMessage: String =
      s"Tips got misaligned! Check the implementation! deprecatedToAdd -> $deprecatedToAdd not equal activeToDeprecate -> $activeToDeprecate"
  }
}

sealed abstract class SnapshotProcessor[F[_]: Async: KryoSerializer: SecurityProvider] private (
  addressStorage: AddressStorage[F],
  blockStorage: BlockStorage[F],
  lastGlobalSnapshotStorage: LastGlobalSnapshotStorage[F],
  transactionStorage: TransactionStorage[F]
) {

  import SnapshotProcessor._

  def logger = Slf4jLogger.getLogger[F]

  def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult] =
    checkAlignment(globalSnapshot).flatMap {
      case AlignedAtNewOrdinal(toMarkMajority, tipsToDeprecate, tipsToRemove, txRefsToMarkMajority, postponedToWaiting) =>
        val adjustToMajority: F[Unit] =
          blockStorage
            .adjustToMajority(
              toMarkMajority = toMarkMajority,
              tipsToDeprecate = tipsToDeprecate,
              tipsToRemove = tipsToRemove,
              postponedToWaiting = postponedToWaiting
            )

        val markTxRefsAsMajority: F[Unit] =
          transactionStorage.markMajority(txRefsToMarkMajority)

        val setSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.set(globalSnapshot)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot.map { _ =>
            Aligned(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
              Set.empty
            )
          }

      case AlignedAtNewHeight(toMarkMajority, obsoleteToRemove, tipsToDeprecate, tipsToRemove, txRefsToMarkMajority, postponedToWaiting) =>
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
          transactionStorage.markMajority(txRefsToMarkMajority)

        val setSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.set(globalSnapshot)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot.map { _ =>
            Aligned(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
              obsoleteToRemove
            )
          }

      case DownloadNeeded(toAdd, obsoleteToRemove, activeTips, deprecatedTips, relatedPostponed) =>
        val adjustToMajority: F[Unit] =
          blockStorage.adjustToMajority(
            toAdd = toAdd,
            obsoleteToRemove = obsoleteToRemove,
            activeTipsToAdd = activeTips,
            deprecatedTipsToAdd = deprecatedTips,
            postponedToWaiting = relatedPostponed
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(globalSnapshot.info.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(globalSnapshot.info.lastTxRefs)

        val setInitialSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.setInitial(globalSnapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setInitialSnapshot.map { _ =>
            DownloadPerformed(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
              toAdd.map(_._1.proofsHash),
              obsoleteToRemove
            )
          }

      case RedownloadNeeded(
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
          )

        val setBalances: F[Unit] =
          addressStorage.clean >>
            addressStorage.updateBalances(globalSnapshot.info.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(globalSnapshot.info.lastTxRefs)

        val setSnapshot: F[Unit] =
          lastGlobalSnapshotStorage.set(globalSnapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setSnapshot.map { _ =>
            RedownloadPerformed(
              GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot),
              toAdd.map(_._1.proofsHash),
              acceptedToRemove,
              obsoleteToRemove
            )
          }

      case Ignore(lastHeight, lastSubHeight, lastOrdinal, processingHeight, processingSubHeight, processingOrdinal) =>
        logger
          .warn(
            s"Unexpected case during global snapshot processing - ignoring snapshot! Last: (height: $lastHeight, subHeight: $lastSubHeight, ordinal: $lastOrdinal) processing: (height: $processingHeight, subHeight:$processingSubHeight, ordinal: $processingOrdinal)."
          )
          .as(SnapshotIgnored(GlobalSnapshotReference.fromHashedGlobalSnapshot(globalSnapshot)))
    }

  private def checkAlignment(globalSnapshot: GlobalSnapshot): F[Alignment] =
    for {
      acceptedInMajority <- globalSnapshot.blocks.toList.traverse {
        case DAGBlockAsActiveTip(block, usageCount) =>
          block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
      }.map(_.toMap)

      SnapshotTips(gsDeprecatedTips, gsRemainedActive) = globalSnapshot.tips

      result <- lastGlobalSnapshotStorage.get.flatMap {
        case Some(last)
            if last.ordinal.next === globalSnapshot.ordinal && (last.height === globalSnapshot.height && last.subHeight.next === globalSnapshot.subHeight) && last.hash === globalSnapshot.lastSnapshotHash =>
          val isDependent =
            (block: Signed[DAGBlock]) => BlockRelations.dependsOn(acceptedInMajority.values.map(_._1).toSet, Set.empty)(block)
          blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height, isDependent).flatMap {
            case MajorityReconciliationData(deprecatedTips, activeTips, _, _, relatedPostponed, _, acceptedAbove) =>
              val onlyInMajority = acceptedInMajority -- acceptedAbove
              val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
              lazy val toAdd = onlyInMajority.values.toSet
              lazy val toReset = acceptedAbove -- toMarkMajority.keySet
              val tipsToRemove = deprecatedTips -- gsDeprecatedTips.map(_.block.hash)
              val deprecatedTipsToAdd = gsDeprecatedTips.map(_.block.hash) -- deprecatedTips
              val tipsToDeprecate = activeTips -- gsRemainedActive.map(_.block.hash)
              val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
              lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, globalSnapshot)
              lazy val postponedToWaiting = relatedPostponed -- toAdd.map(_._1.proofsHash) -- toReset

              if (!areTipsAligned)
                MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
              else if (onlyInMajority.isEmpty)
                Applicative[F].pure[Alignment](
                  AlignedAtNewOrdinal(toMarkMajority.toSet, tipsToDeprecate, tipsToRemove, txRefsToMarkMajority, postponedToWaiting)
                )
              else
                Applicative[F]
                  .pure[Alignment](
                    RedownloadNeeded(
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

        case Some(last)
            if last.ordinal.next === globalSnapshot.ordinal && (last.height < globalSnapshot.height && globalSnapshot.subHeight === SubHeight.MinValue) && last.hash === globalSnapshot.lastSnapshotHash =>
          val isDependent =
            (block: Signed[DAGBlock]) => BlockRelations.dependsOn(acceptedInMajority.values.map(_._1).toSet, Set.empty)(block)
          blockStorage.getBlocksForMajorityReconciliation(last.height, globalSnapshot.height, isDependent).flatMap {
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
              val tipsToRemove = deprecatedTips -- gsDeprecatedTips.map(_.block.hash)
              val deprecatedTipsToAdd = gsDeprecatedTips.map(_.block.hash) -- deprecatedTips
              val tipsToDeprecate = activeTips -- gsRemainedActive.map(_.block.hash)
              val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
              lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, globalSnapshot)
              lazy val postponedToWaiting = relatedPostponed -- obsoleteToRemove -- toAdd.map(_._1.proofsHash) -- toReset

              if (!areTipsAligned)
                MonadThrow[F].raiseError[Alignment](TipsGotMisaligned(deprecatedTipsToAdd, tipsToDeprecate))
              else if (onlyInMajority.isEmpty && acceptedToRemove.isEmpty)
                Applicative[F].pure[Alignment](
                  AlignedAtNewHeight(
                    toMarkMajority.toSet,
                    obsoleteToRemove,
                    tipsToDeprecate,
                    tipsToRemove,
                    txRefsToMarkMajority,
                    postponedToWaiting
                  )
                )
              else
                Applicative[F].pure[Alignment](
                  RedownloadNeeded(
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

        case Some(last) =>
          Applicative[F].pure[Alignment](
            Ignore(last.height, last.subHeight, last.ordinal, globalSnapshot.height, globalSnapshot.subHeight, globalSnapshot.ordinal)
          )

        case None =>
          val isDependent = (block: Signed[DAGBlock]) =>
            BlockRelations.dependsOn(
              acceptedInMajority.values.map(_._1).toSet,
              gsDeprecatedTips.map(_.block) ++ gsRemainedActive.map(_.block)
            )(block)
          blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, globalSnapshot.height, isDependent).flatMap {
            case MajorityReconciliationData(_, _, waitingInRange, postponedInRange, relatedPostponed, _, _) =>
              val initialBlocksAndTips =
                acceptedInMajority.keySet ++ gsRemainedActive.map(_.block.hash) ++ gsDeprecatedTips.map(_.block.hash)
              val obsoleteToRemove = waitingInRange ++ postponedInRange -- initialBlocksAndTips
              val postponedToWaiting = relatedPostponed -- postponedInRange -- initialBlocksAndTips

              Applicative[F].pure[Alignment](
                DownloadNeeded(
                  acceptedInMajority.values.toSet,
                  obsoleteToRemove,
                  gsRemainedActive,
                  gsDeprecatedTips.map(_.block),
                  postponedToWaiting
                )
              )
          }
      }
    } yield result

  private def extractMajorityTxRefs(
    acceptedInMajority: Map[ProofsHash, (Hashed[DAGBlock], NonNegLong)],
    globalSnapshot: GlobalSnapshot
  ): Map[Address, TransactionReference] = {
    val sourceAddresses =
      acceptedInMajority.values
        .flatMap(_._1.transactions.toSortedSet)
        .map(_.source)
        .toSet

    globalSnapshot.info.lastTxRefs.view.filterKeys(sourceAddresses.contains).toMap
  }
}
