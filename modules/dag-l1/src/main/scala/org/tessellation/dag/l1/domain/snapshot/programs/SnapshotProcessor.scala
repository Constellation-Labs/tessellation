package org.tessellation.dag.l1.domain.snapshot.programs

import cats.data.NonEmptyList
import cats.effect.Async
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._
import cats.{Applicative, MonadThrow}

import scala.util.control.NoStackTrace

import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage.MajorityReconciliationData
import org.tessellation.dag.l1.domain.block.{BlockRelations, BlockStorage}
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.snapshot.Snapshot
import org.tessellation.schema.transaction.{Transaction, TransactionReference}
import org.tessellation.sdk.domain.snapshot.Validator
import org.tessellation.sdk.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hashed, SecurityProvider}

import derevo.cats.show
import derevo.derive
import eu.timepit.refined.types.numeric.NonNegLong
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class SnapshotProcessor[F[_]: Async: KryoSerializer: SecurityProvider, T <: Transaction, B <: Block[T], S <: Snapshot[T, B]] {
  import SnapshotProcessor._

  def process(globalSnapshot: Hashed[GlobalSnapshot]): F[SnapshotProcessingResult]

  def processAlignment(
    snapshot: Hashed[S],
    alignment: Alignment,
    blockStorage: BlockStorage[F, B],
    transactionStorage: TransactionStorage[F, T],
    lastSnapshotStorage: LastSnapshotStorage[F, S],
    addressStorage: AddressStorage[F]
  ): F[SnapshotProcessingResult] =
    alignment match {
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
          lastSnapshotStorage.set(snapshot)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot.as[SnapshotProcessingResult] {
            Aligned(
              SnapshotReference.fromHashedSnapshot(snapshot),
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
          lastSnapshotStorage.set(snapshot)

        adjustToMajority >>
          markTxRefsAsMajority >>
          setSnapshot.as[SnapshotProcessingResult] {
            Aligned(
              SnapshotReference.fromHashedSnapshot(snapshot),
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
            addressStorage.updateBalances(snapshot.info.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(snapshot.info.lastTxRefs)

        val setInitialSnapshot: F[Unit] =
          lastSnapshotStorage.setInitial(snapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setInitialSnapshot.as[SnapshotProcessingResult] {
            DownloadPerformed(
              SnapshotReference.fromHashedSnapshot(snapshot),
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
            addressStorage.updateBalances(snapshot.info.balances)

        val setTransactionRefs: F[Unit] =
          transactionStorage.setLastAccepted(snapshot.info.lastTxRefs)

        val setSnapshot: F[Unit] =
          lastSnapshotStorage.set(snapshot)

        adjustToMajority >>
          setBalances >>
          setTransactionRefs >>
          setSnapshot.as[SnapshotProcessingResult] {
            RedownloadPerformed(
              SnapshotReference.fromHashedSnapshot(snapshot),
              toAdd.map(_._1.proofsHash),
              acceptedToRemove,
              obsoleteToRemove
            )
          }

      case Ignore(lastHeight, lastSubHeight, lastOrdinal, processingHeight, processingSubHeight, processingOrdinal) =>
        Slf4jLogger
          .getLogger[F]
          .warn(
            s"Unexpected case during global snapshot processing - ignoring snapshot! Last: (height: $lastHeight, subHeight: $lastSubHeight, ordinal: $lastOrdinal) processing: (height: $processingHeight, subHeight:$processingSubHeight, ordinal: $processingOrdinal)."
          )
          .as(SnapshotIgnored(SnapshotReference.fromHashedSnapshot(snapshot)))
    }

  def checkAlignment(
    snapshot: S,
    blockStorage: BlockStorage[F, B],
    lastSnapshotStorage: LastSnapshotStorage[F, S]
  ): F[Alignment] =
    for {
      acceptedInMajority <- snapshot.blocks.toList.traverse {
        case BlockAsActiveTip(block, usageCount) =>
          block.toHashedWithSignatureCheck.flatMap(_.liftTo[F]).map(b => b.proofsHash -> (b, usageCount))
      }.map(_.toMap)

      SnapshotTips(snapshotDeprecatedTips, snapshotRemainedActive) = snapshot.tips

      result <- lastSnapshotStorage.get.flatMap {
        case Some(last) =>
          Validator.compare[S](last, snapshot) match {
            case Validator.NextSubHeight =>
              val isDependent =
                (block: Signed[B]) => BlockRelations.dependsOn[F, T, B](acceptedInMajority.values.map(_._1).toSet)(block)

              blockStorage.getBlocksForMajorityReconciliation(last.height, snapshot.height, isDependent).flatMap {
                case MajorityReconciliationData(deprecatedTips, activeTips, _, _, relatedPostponed, _, acceptedAbove) =>
                  val onlyInMajority = acceptedInMajority -- acceptedAbove
                  val toMarkMajority = acceptedInMajority.view.filterKeys(acceptedAbove.contains).mapValues(_._2)
                  lazy val toAdd = onlyInMajority.values.toSet
                  lazy val toReset = acceptedAbove -- toMarkMajority.keySet
                  val tipsToRemove = deprecatedTips -- snapshotDeprecatedTips.map(_.block.hash)
                  val deprecatedTipsToAdd = snapshotDeprecatedTips.map(_.block.hash) -- deprecatedTips
                  val tipsToDeprecate = activeTips -- snapshotRemainedActive.map(_.block.hash)
                  val areTipsAligned = deprecatedTipsToAdd == tipsToDeprecate
                  lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, snapshot)
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

            case Validator.NextHeight =>
              val isDependent =
                (block: Signed[B]) => BlockRelations.dependsOn[F, T, B](acceptedInMajority.values.map(_._1).toSet)(block)

              blockStorage.getBlocksForMajorityReconciliation(last.height, snapshot.height, isDependent).flatMap {
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
                  lazy val txRefsToMarkMajority = extractMajorityTxRefs(acceptedInMajority, snapshot)
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

            case Validator.NotNext =>
              Applicative[F].pure[Alignment](
                Ignore(last.height, last.subHeight, last.ordinal, snapshot.height, snapshot.subHeight, snapshot.ordinal)
              )
          }

        case None =>
          val isDependent = (block: Signed[B]) =>
            BlockRelations.dependsOn[F, T, B](
              acceptedInMajority.values.map(_._1).toSet,
              snapshotDeprecatedTips.map(_.block) ++ snapshotRemainedActive.map(_.block)
            )(block)

          blockStorage.getBlocksForMajorityReconciliation(Height.MinValue, snapshot.height, isDependent).flatMap {
            case MajorityReconciliationData(_, _, waitingInRange, postponedInRange, relatedPostponed, _, _) =>
              val initialBlocksAndTips =
                acceptedInMajority.keySet ++ snapshotRemainedActive.map(_.block.hash) ++ snapshotDeprecatedTips.map(_.block.hash)
              val obsoleteToRemove = waitingInRange ++ postponedInRange -- initialBlocksAndTips
              val postponedToWaiting = relatedPostponed -- postponedInRange -- initialBlocksAndTips

              Applicative[F].pure[Alignment](
                DownloadNeeded(
                  acceptedInMajority.values.toSet,
                  obsoleteToRemove,
                  snapshotRemainedActive,
                  snapshotDeprecatedTips.map(_.block),
                  postponedToWaiting
                )
              )
          }
      }
    } yield result

  private def extractMajorityTxRefs(
    acceptedInMajority: Map[ProofsHash, (Hashed[B], NonNegLong)],
    snapshot: Snapshot[T, B]
  ): Map[Address, TransactionReference] = {
    val sourceAddresses =
      acceptedInMajority.values
        .flatMap(_._1.transactions.toSortedSet)
        .map(_.source)
        .toSet

    snapshot.info.lastTxRefs.view.filterKeys(sourceAddresses.contains).toMap
  }

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
    toAdd: Set[(Hashed[B], NonNegLong)],
    obsoleteToRemove: Set[ProofsHash],
    activeTips: Set[ActiveTip],
    deprecatedTips: Set[BlockReference],
    postponedToWaiting: Set[ProofsHash]
  ) extends Alignment

  case class RedownloadNeeded(
    toAdd: Set[(Hashed[B], NonNegLong)],
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
