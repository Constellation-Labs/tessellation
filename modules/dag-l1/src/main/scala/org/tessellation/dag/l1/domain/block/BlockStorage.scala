package org.tessellation.dag.l1.domain.block

import cats.Show
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import scala.util.control.NoStackTrace

import org.tessellation.dag.l1.domain.block.BlockStorage._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.collection.MapRefUtils.MapRefOps
import org.tessellation.schema.block.Tips
import org.tessellation.schema.height.Height
import org.tessellation.schema.{ActiveTip, Block, BlockReference}
import org.tessellation.security.Hashed
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import io.chrisdavenport.mapref.MapRef
import monocle.macros.syntax.lens._

class BlockStorage[F[_]: Sync: Random](blocks: MapRef[F, ProofsHash, Option[StoredBlock]]) {

  def getState(): F[Map[ProofsHash, StoredBlock]] =
    blocks.toMap

  private[block] def accept(hashedBlock: Hashed[Block]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case Some(WaitingBlock(_)) => (AcceptedBlock(hashedBlock).some, hashedBlock.asRight)
      case other                 => (other, BlockAcceptanceError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F])
      .flatMap(_ => addParentUsages(hashedBlock))

  private[block] def postpone(hashedBlock: Hashed[Block]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case Some(WaitingBlock(_)) => (PostponedBlock(hashedBlock.signed).some, hashedBlock.asRight)
      case other                 => (other, BlockPostponementError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F]).void

  def adjustToMajority(
    toAdd: Set[(Hashed[Block], NonNegLong)] = Set.empty,
    toMarkMajority: Set[(ProofsHash, NonNegLong)] = Set.empty,
    acceptedToRemove: Set[ProofsHash] = Set.empty,
    obsoleteToRemove: Set[ProofsHash] = Set.empty,
    toReset: Set[ProofsHash] = Set.empty,
    tipsToDeprecate: Set[ProofsHash] = Set.empty,
    tipsToRemove: Set[ProofsHash] = Set.empty,
    activeTipsToAdd: Set[ActiveTip] = Set.empty,
    deprecatedTipsToAdd: Set[BlockReference] = Set.empty,
    postponedToWaiting: Set[ProofsHash] = Set.empty
  ): F[Unit] = {

    def addMajorityBlocks: F[Unit] =
      toAdd.toList.traverse {
        case (block, initialUsages) =>
          val reference = BlockReference(block.height, block.proofsHash)
          blocks(block.proofsHash).modify {
            case Some(WaitingBlock(_)) | Some(PostponedBlock(_)) | None =>
              (MajorityBlock(reference, initialUsages, Active).some, block.asRight)
            case other => (other, UnexpectedBlockStateWhenAddingMajorityBlock(block.proofsHash, other).asLeft)
          }.flatMap(_.liftTo[F])
            .flatMap(addParentUsagesAfterRedownload)
      }.void

    def markMajorityBlocks: F[Unit] =
      toMarkMajority.toList.traverse {
        case (hash, initialUsages) =>
          blocks(hash).modify {
            case Some(AcceptedBlock(block)) =>
              val reference = BlockReference(block.height, block.proofsHash)
              (MajorityBlock(reference, initialUsages, Active).some, ().asRight)
            case other =>
              (other, UnexpectedBlockStateWhenMarkingAsMajority(hash, other).asLeft)
          }.flatMap(_.liftTo[F])
      }.void

    def removeAcceptedNonMajorityBlocks: F[Unit] =
      acceptedToRemove.toList.traverse { hash =>
        blocks(hash).modify {
          case Some(AcceptedBlock(block)) => (None, block.asRight)
          case other                      => (None, UnexpectedBlockStateWhenRemovingAccepted(hash, other).asLeft)
        }.flatMap(_.liftTo[F])
          .flatMap(hb => removeParentUsages(hb))
      }.void

    def removeObsoleteBlocks: F[Unit] =
      obsoleteToRemove.toList.traverse { hash =>
        blocks(hash).modify {
          case Some(WaitingBlock(_)) | Some(PostponedBlock(_)) => (None, ().asRight)
          case other                                           => (other, UnexpectedBlockStateWhenRemoving(hash, other).asLeft)
        }.flatMap(_.liftTo[F])
      }.void

    def resetBlocks: F[Unit] =
      toReset.toList.traverse { hash =>
        blocks(hash).modify {
          case Some(AcceptedBlock(block)) => (WaitingBlock(block.signed).some, ().asRight)
          case other                      => (None, UnexpectedBlockStateWhenResetting(hash, other).asLeft)
        }.flatMap(_.liftTo[F])
      }.void

    def deprecateTips: F[Unit] =
      tipsToDeprecate.toList.traverse { hash =>
        blocks(hash).modify {
          case Some(mb @ MajorityBlock(_, _, Active)) => (mb.deprecate.some, ().asRight)
          case other                                  => (other, TipDeprecatingError(hash, other).asLeft)
        }.flatMap(_.liftTo[F])
      }.void

    def removeTips: F[Unit] =
      tipsToRemove.toList.traverse { hash =>
        blocks(hash).modify {
          case Some(MajorityBlock(_, _, Deprecated)) => (None, ().asRight)
          case other                                 => (other, TipRemovalError(hash, other).asLeft)
        }.flatMap(_.liftTo[F])
      }.void

    def addActiveTips: F[Unit] =
      activeTipsToAdd.toList.traverse { activeTip =>
        blocks(activeTip.block.hash).modify {
          case Some(WaitingBlock(_)) | Some(PostponedBlock(_)) | None =>
            (MajorityBlock(activeTip.block, activeTip.usageCount, Active).some, ().asRight)
          case other => (other, ActiveTipAddingError(activeTip.block.hash, other).asLeft)
        }.flatMap(_.liftTo[F])
      }.void

    def addDeprecatedTips: F[Unit] =
      deprecatedTipsToAdd.toList.traverse { blockReference =>
        blocks(blockReference.hash).modify {
          case Some(WaitingBlock(_)) | Some(PostponedBlock(_)) | None =>
            (MajorityBlock(blockReference, 0L, Deprecated).some, ().asRight)
          case other => (other, DeprecatedTipAddingError(blockReference.hash, other).asLeft)
        }.flatMap(_.liftTo[F])
      }.void

    def resetPostponedBlocks: F[Unit] =
      postponedToWaiting.toList.traverse { hash =>
        blocks(hash).modify {
          case Some(PostponedBlock(block)) => (WaitingBlock(block).some, ().asRight)
          case other                       => (other, UnexpectedBlockStateWhenResettingPostponed(hash, other).asLeft)
        }.flatMap(_.liftTo[F])
      }.void

    addMajorityBlocks >>
      markMajorityBlocks >>
      removeAcceptedNonMajorityBlocks >>
      removeObsoleteBlocks >>
      resetBlocks >>
      deprecateTips >>
      removeTips >>
      addActiveTips >>
      addDeprecatedTips >>
      resetPostponedBlocks
  }

  def store(hashedBlock: Hashed[Block]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case None  => (WaitingBlock(hashedBlock.signed).some, ().asRight)
      case other => (other, BlockAlreadyStoredError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F])

  def restoreDependent(isDependent: Signed[Block] => F[Boolean]): F[Unit] =
    blocks.toMap
      .flatMap(_.collect { case (hash, PostponedBlock(block)) => hash -> block }.toList.filterA { case (_, block) => isDependent(block) })
      .flatMap(_.traverse {
        case (proofsHash, block) =>
          blocks(proofsHash).modify {
            case Some(PostponedBlock(_)) => (WaitingBlock(block).some, block.asRight)
            case other                   => (other, BlockRestorationError(proofsHash, other).asLeft)
          }.flatMap(_.liftTo[F])
      }.void)

  def getWaiting: F[Map[ProofsHash, Signed[Block]]] =
    blocks.toMap.map(_.collect { case (hash, WaitingBlock(block)) => hash -> block })

  def getUsages(hash: ProofsHash): F[Option[NonNegLong]] =
    blocks(hash).get.map {
      case Some(block: MajorityBlock) => block.usages.some
      case _                          => none[NonNegLong]
    }

  def getBlocksForMajorityReconciliation(
    lastHeight: Height,
    currentHeight: Height,
    isDependent: Signed[Block] => F[Boolean]
  ): F[MajorityReconciliationData] =
    for {
      all <- blocks.toMap
      deprecatedTips = all.collect { case (hash, MajorityBlock(_, _, Deprecated)) => hash }.toSet
      activeTips = all.collect { case (hash, MajorityBlock(_, _, Active)) => hash }.toSet
      waitingInRange = all.collect {
        case (hash, WaitingBlock(b)) if b.height.inRangeInclusive(lastHeight.next, currentHeight) => hash
      }.toSet
      postponedInRange = all.collect {
        case (hash, PostponedBlock(b)) if b.height.inRangeInclusive(lastHeight.next, currentHeight) => hash
      }.toSet
      relatedPostponed <- all.toList.filterA {
        case (_, PostponedBlock(b)) => isDependent(b)
        case _                      => false.pure[F]
      }.map(_.map {
        case (hash, _) => hash
      }.toSet)
      acceptedInRange = all.collect {
        case (hash, AcceptedBlock(b)) if b.height.inRangeInclusive(lastHeight.next, currentHeight) => hash
      }.toSet
      acceptedAbove = all.collect {
        case (hash, AcceptedBlock(block)) if block.height.value > currentHeight.value => hash
      }.toSet
    } yield
      MajorityReconciliationData(
        deprecatedTips,
        activeTips,
        waitingInRange,
        postponedInRange,
        relatedPostponed,
        acceptedInRange,
        acceptedAbove
      )

  def getTips(tipsCount: PosInt): F[Option[Tips]] =
    blocks.toMap
      .map(_.collect { case (_, MajorityBlock(blockReference, _, Active)) => blockReference })
      .map(_.toList)
      .flatMap(Random[F].shuffleList)
      .map(_.sortBy(_.height))
      .map {
        case tips if tips.size >= tipsCount =>
          NonEmptyList
            .fromList(tips.take(tipsCount))
            .map(Tips(_))
        case _ => None
      }

  private def addParentUsages(hashedBlock: Hashed[Block]): F[Unit] =
    hashedBlock.parent.toList.traverse { blockReference =>
      blocks(blockReference.hash).modify {
        case Some(majority: MajorityBlock) => (majority.addUsage.some, ().asRight)
        case other                         => (other, TipUsageUpdateError(hashedBlock.proofsHash, blockReference.hash, other).asLeft)
      }.flatMap(_.liftTo[F])
    }.void

  private def addParentUsagesAfterRedownload(hashedBlock: Hashed[Block]): F[Unit] =
    hashedBlock.parent.toList.traverse { blockReference =>
      blocks(blockReference.hash).update {
        case Some(majority: MajorityBlock) => majority.addUsage.some
        case other                         => other
      }
    }.void

  private def removeParentUsages(hashedBlock: Hashed[Block]): F[Unit] =
    hashedBlock.parent.toList.traverse { blockReference =>
      blocks(blockReference.hash).modify {
        case Some(majorityBlock: MajorityBlock) =>
          (majorityBlock.removeUsage.some, ().asRight)
        case other => (other, TipUsageUpdateError(hashedBlock.proofsHash, blockReference.hash, other).asLeft)
      }
    }.void
}

object BlockStorage {

  def make[F[_]: Sync: Random]: F[BlockStorage[F]] =
    MapRef.ofConcurrentHashMap[F, ProofsHash, StoredBlock]().map(new BlockStorage[F](_))

  def make[F[_]: Sync: Random](blocks: Map[ProofsHash, StoredBlock]): F[BlockStorage[F]] =
    MapRef.ofSingleImmutableMap(blocks).map(new BlockStorage[F](_))

  sealed trait StoredBlock
  case class WaitingBlock(block: Signed[Block]) extends StoredBlock
  case class PostponedBlock(block: Signed[Block]) extends StoredBlock
  case class AcceptedBlock(block: Hashed[Block]) extends StoredBlock
  case class MajorityBlock(blockReference: BlockReference, usages: NonNegLong, tipStatus: TipStatus) extends StoredBlock {
    def addUsage: MajorityBlock = this.focus(_.usages).modify(usages => NonNegLong.unsafeFrom(usages + 1L))

    def removeUsage: MajorityBlock =
      this.focus(_.usages).modify(usages => NonNegLong.from(usages - 1L).toOption.getOrElse(NonNegLong.MinValue))
    def deprecate: MajorityBlock = this.focus(_.tipStatus).replace(Deprecated)
  }

  implicit val showStoredBlock: Show[StoredBlock] = {
    case _: WaitingBlock   => "Waiting"
    case _: PostponedBlock => "Postponed"
    case _: AcceptedBlock  => "Accepted"
    case _: MajorityBlock  => "Majority"
  }

  case class MajorityReconciliationData(
    deprecatedTips: Set[ProofsHash],
    activeTips: Set[ProofsHash],
    waitingInRange: Set[ProofsHash],
    postponedInRange: Set[ProofsHash],
    relatedPostponed: Set[ProofsHash],
    acceptedInRange: Set[ProofsHash],
    acceptedAbove: Set[ProofsHash]
  )

  sealed trait TipStatus
  case object Active extends TipStatus
  case object Deprecated extends TipStatus

  sealed trait BlockStorageError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

  case class TipUsageUpdateError(
    child: ProofsHash,
    parent: ProofsHash,
    encountered: Option[StoredBlock]
  ) extends BlockStorageError {

    val errorMessage: String =
      s"Parent block with hash=${parent.show} not found in majority when updating usage! Child hash=${child.show}. Encountered state: ${encountered.show}"
  }

  case class BlockAcceptanceError(hash: ProofsHash, encountered: Option[StoredBlock]) extends BlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Accepted! Encountered state: ${encountered.show}."
  }

  case class BlockPostponementError(hash: ProofsHash, encountered: Option[StoredBlock]) extends BlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Postponed! Encountered state: ${encountered.show}."
  }

  case class BlockRestorationError(hash: ProofsHash, encountered: Option[StoredBlock]) extends BlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Waiting! Encountered state: ${encountered.show}."
  }

  case class BlockAlreadyStoredError(hash: ProofsHash, encountered: Option[StoredBlock]) extends BlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} is already stored. Encountered state: ${encountered.show}."
  }

  sealed trait BlockMajorityUpdateError extends BlockStorageError

  case class UnexpectedBlockStateWhenMarkingAsMajority(hash: ProofsHash, got: Option[StoredBlock]) extends BlockMajorityUpdateError {
    val errorMessage: String = s"Accepted block to be marked as majority with hash: $hash not found! But got: $got"
  }

  case class UnexpectedBlockStateWhenRemovingAccepted(hash: ProofsHash, got: Option[StoredBlock]) extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Accepted block to be removed during majority update with hash: $hash not found! But got: $got"
  }

  case class UnexpectedBlockStateWhenRemoving(hash: ProofsHash, got: Option[StoredBlock]) extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be removed during majority update with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenResetting(hash: ProofsHash, got: Option[StoredBlock]) extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be reset during majority update with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenAddingMajorityBlock(hash: ProofsHash, got: Option[StoredBlock]) extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be added during majority update with hash: $hash not found in expected state! But got: $got"
  }

  sealed trait TipUpdateError extends BlockStorageError

  case class TipDeprecatingError(hash: ProofsHash, got: Option[StoredBlock]) extends TipUpdateError {
    override val errorMessage: String =
      s"Active tip to be deprecated with hash: $hash not found in expected state! But got: $got"
  }

  case class TipRemovalError(hash: ProofsHash, got: Option[StoredBlock]) extends TipUpdateError {
    override val errorMessage: String =
      s"Deprecated tip to be removed with hash: $hash not found in expected state! But got: $got"
  }

  case class ActiveTipAddingError(hash: ProofsHash, got: Option[StoredBlock]) extends TipUpdateError {
    override val errorMessage: String =
      s"Active tip to be added with hash: $hash not found in expected state! But got: $got"
  }

  case class DeprecatedTipAddingError(hash: ProofsHash, got: Option[StoredBlock]) extends TipUpdateError {
    override val errorMessage: String =
      s"Deprecated tip to be added with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenResettingPostponed(hash: ProofsHash, got: Option[StoredBlock]) extends TipUpdateError {
    val errorMessage: String =
      s"Postponed block to be reset to waiting block with hash: $hash not found in expected state! But got: $got"
  }

}
