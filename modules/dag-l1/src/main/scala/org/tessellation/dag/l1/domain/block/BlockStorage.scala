package org.tessellation.dag.l1.domain.block

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.l1.config.TipsConfig
import org.tessellation.dag.l1.domain.block
import org.tessellation.dag.l1.domain.block.BlockStorage._
import org.tessellation.ext.collection.MapRefUtils.MapRefOps
import org.tessellation.security.Hashed
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import io.chrisdavenport.mapref.MapRef
import io.estatico.newtype.ops._
import monocle.macros.syntax.lens._

class BlockStorage[F[_]: Sync](blocks: MapRef[F, ProofsHash, Option[StoredBlock]], tipsConfig: TipsConfig) {

  def areParentsAccepted(block: DAGBlock): F[Map[BlockReference, Boolean]] =
    block.parent.traverse { ref =>
      isBlockAccepted(ref).map(ref -> _)
    }.map(_.toNem.toSortedMap)

  def accept(hashedBlock: Hashed[DAGBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash)
      .modify[Either[BlockAcceptanceError, Unit]] {
        case Some(storedBlock) =>
          storedBlock match {
            case unknown: UnknownBlock => (unknown.some, BlockIsUnknown(hashedBlock.proofsHash).asLeft)
            case WaitingBlock(_, usages) =>
              (AcceptedBlock(hashedBlock, Accepted, usages, isTip = false).some, ().asRight)
            case accepted: AcceptedBlock => (accepted.some, BlockAlreadyAccepted(hashedBlock.proofsHash).asLeft)
          }
        case None =>
          (None, BlockDoesNotExist(hashedBlock.proofsHash).asLeft)
      }
      .flatMap(_.liftTo[F])

  def store(hashedBlock: Hashed[DAGBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash)
      .modify[Either[BlockStoringError, Unit]] {
        case Some(storedBlock) =>
          storedBlock match {
            case UnknownBlock(usages) => (WaitingBlock(hashedBlock.signed, usages).some, ().asRight)
            case waiting: WaitingBlock =>
              (waiting.some, BlockAlreadyStoredAndWaiting(hashedBlock.proofsHash).asLeft)
            case accepted: AcceptedBlock =>
              (accepted.some, BlockAlreadyStoredAndAccepted(hashedBlock.proofsHash).asLeft)
          }
        case None => (WaitingBlock(hashedBlock.signed, Set.empty[ProofsHash]).some, ().asRight)
      }
      .flatMap(_.liftTo[F]) >> updateParentUsages(hashedBlock)

  def getWaiting: F[Map[ProofsHash, Signed[DAGBlock]]] =
    blocks.toMap.map(_.collect { case (hash, WaitingBlock(block, _)) => hash -> block })

  def handleTipsUpdate(hashedBlock: Hashed[DAGBlock]): F[Unit] =
    for {
      allBlocks <- blocks.toMap
      allTips = allBlocks.collect { case (hash, ab @ AcceptedBlock(_, _, _, true)) => hash -> ab }
      parentHashToTip = hashedBlock.signed.value.parent.map(ref => ref.hash -> allTips.get(ref.hash)).toList.toMap
      tipParents = parentHashToTip.collect { case (hash, Some(accepted)) => hash -> accepted }
      notTipParents = (parentHashToTip -- tipParents.keySet).keySet
      tipParentsSize = tipParents.size
      tipParentsToRemove = tipParents.filter {
        case (_, block) =>
          val enoughTips = allTips.size >= tipsConfig.minimumTipsCount + tipParentsSize
          val tooManyTips = allTips.size >= tipsConfig.maximumTipsCount + tipParentsSize
          val parentReachedUsageLimit = (block.usages + hashedBlock.proofsHash).size >= tipsConfig.maximumTipUsages
          enoughTips && (tooManyTips || parentReachedUsageLimit)
      }
      tipParentsToRemain = tipParents -- tipParentsToRemove.keySet
      remainingTips = allTips -- tipParentsToRemove.keySet
      minTipHeight = remainingTips.values.map(_.block.signed.value.height.coerce).minOption.getOrElse(0L)
      addAsTipCondition = remainingTips.size < tipsConfig.maximumTipsCount && minTipHeight < hashedBlock.signed.value.height.coerce
      _ <- updateNoTipParents(notTipParents.toList, hashedBlock)
      _ <- updateTipParentsToRemove(tipParentsToRemove.keySet.toList, hashedBlock)
      _ <- updateTipParentsToRemain(tipParentsToRemain.keySet.toList, hashedBlock)
      _ <- tryAddAsATip(addAsTipCondition, hashedBlock)
    } yield ()

  def pullTips(tipsCount: PosInt): F[Option[Tips]] =
    for {
      allBlocks <- blocks.toMap
      allTips = allBlocks.collect { case (_, AcceptedBlock(block, _, _, true)) => block }.toList
        .sortBy(_.signed.value.height.coerce)
      pulledTips = allTips match {
        case tips if tips.size >= tipsCount =>
          NonEmptyList
            .fromList(tips.take(tipsCount))
            .map(parents => block.Tips(parents.map(p => BlockReference(p.proofsHash, p.signed.value.height))))
        case _ => None
      }
    } yield pulledTips

  private def isBlockAccepted(blockReference: BlockReference): F[Boolean] =
    blocks(blockReference.hash).get.map(_.exists(_.isInstanceOf[AcceptedBlock]))

  private def updateNoTipParents(parents: List[ProofsHash], hashedBlock: Hashed[DAGBlock]): F[Unit] =
    parents.traverse { hash =>
      blocks(hash)
        .modify[Either[TipsUpdateError, Unit]] {
          case Some(accepted @ AcceptedBlock(_, _, _, false)) =>
            (accepted.addUsage(hashedBlock.proofsHash).some, ().asRight)
          case unexpected =>
            (unexpected, NoTipAcceptedBlockNotFound(hash, unexpected).asLeft)
        }
        .flatMap(_.liftTo[F])
    }.void

  private def updateTipParentsToRemove(parents: List[ProofsHash], hashedBlock: Hashed[DAGBlock]): F[Unit] =
    parents.traverse { hash =>
      blocks(hash)
        .modify[Either[TipsUpdateError, Unit]] {
          case Some(accepted @ AcceptedBlock(_, _, _, true)) =>
            (accepted.addUsage(hashedBlock.proofsHash).unsetAsTip.some, ().asRight)
          case unexpected =>
            (unexpected, TipBlockToRemoveAsTipNotFound(hash, unexpected).asLeft)
        }
        .flatMap(_.liftTo[F])
    }.void

  private def updateTipParentsToRemain(parents: List[ProofsHash], hashedBlock: Hashed[DAGBlock]): F[Unit] =
    parents.traverse { hash =>
      blocks(hash)
        .modify[Either[TipsUpdateError, Unit]] {
          case Some(accepted @ AcceptedBlock(_, _, _, true)) =>
            (accepted.addUsage(hashedBlock.proofsHash).some, ().asRight)
          case unexpected =>
            (unexpected, TipBlockToRemainAsTipNotFound(hash, unexpected).asLeft)
        }
        .flatMap(_.liftTo[F])
    }.void

  private def tryAddAsATip(condition: Boolean, hashedBlock: Hashed[DAGBlock]): F[Unit] =
    if (condition)
      blocks(hashedBlock.proofsHash).modify {
        case Some(accepted @ AcceptedBlock(_, _, usages, false)) if usages.size < tipsConfig.maximumTipUsages =>
          (accepted.setAsTip.some, ())
        case other => (other, ())
      } else Applicative[F].unit

  private def updateParentUsages(hashedBlock: Hashed[DAGBlock]): F[Unit] = {
    val hash = hashedBlock.proofsHash
    hashedBlock.signed.value.parent.toList.traverse { blockReference =>
      blocks(blockReference.hash).update {
        case Some(storedBlock) =>
          storedBlock match {
            case unknown: UnknownBlock   => unknown.addUsage(hash).some
            case waiting: WaitingBlock   => waiting.addUsage(hash).some
            case accepted: AcceptedBlock => accepted.addUsage(hash).some
          }
        case None => UnknownBlock(Set(hash)).some
      }
    }
  }.void
}

object BlockStorage {

  def make[F[_]: Sync](tipsConfig: TipsConfig): F[BlockStorage[F]] =
    MapRef.ofConcurrentHashMap[F, ProofsHash, StoredBlock]().map(new BlockStorage[F](_, tipsConfig))

  sealed trait AcceptedBlockState
  case object Accepted extends AcceptedBlockState
  case object InMajority extends AcceptedBlockState
  sealed trait StoredBlock
  case class UnknownBlock(usages: Set[ProofsHash]) extends StoredBlock {
    def addUsage(hash: ProofsHash): UnknownBlock = this.focus(_.usages).modify(_ + hash)
  }
  case class WaitingBlock(block: Signed[DAGBlock], usages: Set[ProofsHash]) extends StoredBlock {
    def addUsage(hash: ProofsHash): WaitingBlock = this.focus(_.usages).modify(_ + hash)
  }
  case class AcceptedBlock(block: Hashed[DAGBlock], state: AcceptedBlockState, usages: Set[ProofsHash], isTip: Boolean)
      extends StoredBlock {
    def addUsage(hash: ProofsHash): AcceptedBlock = this.focus(_.usages).modify(_ + hash)
    def setAsTip: AcceptedBlock = this.focus(_.isTip).replace(true)
    def unsetAsTip: AcceptedBlock = this.focus(_.isTip).replace(false)
  }

  sealed trait BlockStorageError extends NoStackTrace
  sealed trait TipsUpdateError extends BlockStorageError
  case class TipBlockToRemoveAsTipNotFound(hash: ProofsHash, got: Option[StoredBlock]) extends TipsUpdateError {
    override def getMessage: String = s"Tip block to remove as tip with hash=$hash not found! But got: $got"
  }
  case class TipBlockToRemainAsTipNotFound(hash: ProofsHash, got: Option[StoredBlock]) extends TipsUpdateError {
    override def getMessage: String = s"Tip block to remain as tip with hash=$hash not found! But got: $got"
  }
  case class NoTipAcceptedBlockNotFound(hash: ProofsHash, got: Option[StoredBlock]) extends TipsUpdateError {
    override def getMessage: String = s"Accepted block that's not a tip with hash=$hash not found! But got: $got"
  }
  sealed trait BlockAcceptanceError extends BlockStorageError
  case class BlockAlreadyAccepted(hash: ProofsHash) extends BlockAcceptanceError {
    override def getMessage: String = s"Block with hash=$hash is already accepted!"
  }
  case class BlockIsUnknown(hash: ProofsHash) extends BlockAcceptanceError {
    override def getMessage: String = s"Block with hash=$hash is unknown so it can't be accepted!"
  }
  case class BlockDoesNotExist(hash: ProofsHash) extends BlockAcceptanceError {
    override def getMessage: String = s"Block with hash=$hash doesn't exist so it can't be accepted!"
  }
  sealed trait BlockStoringError extends BlockStorageError
  case class BlockAlreadyStoredAndWaiting(hash: ProofsHash) extends BlockStoringError {
    override def getMessage: String = s"Block with hash=$hash is already stored and waiting."
  }
  case class BlockAlreadyStoredAndAccepted(hash: ProofsHash) extends BlockStoringError {
    override def getMessage: String = s"Block with hash=$hash is already stored and accepted."
  }
}
