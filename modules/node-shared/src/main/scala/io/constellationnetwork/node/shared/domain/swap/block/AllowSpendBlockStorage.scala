package io.constellationnetwork.node.shared.domain.swap.block

import cats.Show
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.all._

import scala.util.control.NoStackTrace

import io.constellationnetwork.ext.collection.MapRefUtils.MapRefOps
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockStorage._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.swap.{AllowSpend, AllowSpendBlock}
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.ProofsHash
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import io.chrisdavenport.mapref.MapRef

class AllowSpendBlockStorage[F[_]: Sync: Random](blocks: MapRef[F, ProofsHash, Option[AllowSpendStoredBlock]]) {

  def getState(): F[Map[ProofsHash, AllowSpendStoredBlock]] =
    blocks.toMap

  def accept(hashedBlock: Hashed[AllowSpendBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case Some(WaitingBlock(_)) => (AcceptedBlock(hashedBlock).some, hashedBlock.asRight)
      case other                 => (other, BlockAcceptanceError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F]).void

  def postpone(hashedBlock: Hashed[AllowSpendBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case Some(WaitingBlock(_)) => (PostponedBlock(hashedBlock.signed).some, hashedBlock.asRight)
      case other                 => (other, BlockPostponementError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F]).void

  def adjustToMajority(
    toAdd: Set[Hashed[AllowSpendBlock]] = Set.empty,
    toMarkMajority: Set[ProofsHash] = Set.empty,
    acceptedToRemove: Set[ProofsHash] = Set.empty,
    obsoleteToRemove: Set[ProofsHash] = Set.empty,
    toReset: Set[ProofsHash] = Set.empty,
    postponedToWaiting: Set[ProofsHash] = Set.empty
  ): F[Unit] = {

    def addMajorityBlocks: F[Unit] =
      toAdd.toList.traverse { block =>
        val reference = block.proofsHash
        blocks(block.proofsHash).modify {
          case Some(WaitingBlock(_)) | Some(PostponedBlock(_)) | None =>
            (MajorityBlock(reference).some, block.asRight)
          case other => (other, UnexpectedBlockStateWhenAddingMajorityBlock(block.proofsHash, other).asLeft)
        }.flatMap(_.liftTo[F])
      }.void

    def markMajorityBlocks: F[Unit] =
      toMarkMajority.toList.traverse {
        case hash =>
          blocks(hash).modify {
            case Some(AcceptedBlock(block)) =>
              val reference = block.proofsHash
              (MajorityBlock(reference).some, ().asRight)
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
      resetPostponedBlocks
  }

  def store(hashedBlock: Hashed[AllowSpendBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case None  => (WaitingBlock(hashedBlock.signed).some, ().asRight)
      case other => (other, BlockAlreadyStoredError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F])

  def restoreDependent(isDependent: Signed[AllowSpendBlock] => F[Boolean]): F[Unit] =
    blocks.toMap
      .flatMap(_.collect { case (hash, PostponedBlock(block)) => hash -> block }.toList.filterA { case (_, block) => isDependent(block) })
      .flatMap(_.traverse {
        case (proofsHash, block) =>
          blocks(proofsHash).modify {
            case Some(PostponedBlock(_)) => (WaitingBlock(block).some, block.asRight)
            case other                   => (other, BlockRestorationError(proofsHash, other).asLeft)
          }.flatMap(_.liftTo[F])
      }.void)

  def getWaiting: F[Map[ProofsHash, Signed[AllowSpendBlock]]] =
    blocks.toMap.map(_.collect { case (hash, WaitingBlock(block)) => hash -> block })

  def getBlocksForMajorityReconciliation(
    currentEpochProgress: EpochProgress
  ): F[Map[Address, List[Signed[AllowSpend]]]] = for {
    all <- blocks.toMap
    allowSpendBlocks = all.values.flatMap {
      case MajorityBlock(reference) => List.empty // TODO: @mwadon ???
      case WaitingBlock(b)          => b.transactions.toNonEmptyList.toList
      case PostponedBlock(b)        => b.transactions.toNonEmptyList.toList
      case AcceptedBlock(b)         => b.transactions.toNonEmptyList.toList
    }.toList
    allowSpends = allowSpendBlocks
      .filter(as => as.lastValidEpochProgress <= currentEpochProgress)
      .groupBy(_.source)
  } yield allowSpends
}

object AllowSpendBlockStorage {

  def make[F[_]: Sync: Random]: F[AllowSpendBlockStorage[F]] =
    MapRef.ofConcurrentHashMap[F, ProofsHash, AllowSpendStoredBlock]().map(new AllowSpendBlockStorage[F](_))

  def make[F[_]: Sync: Random](blocks: Map[ProofsHash, AllowSpendStoredBlock]): F[AllowSpendBlockStorage[F]] =
    MapRef.ofSingleImmutableMap(blocks).map(new AllowSpendBlockStorage[F](_))

  sealed trait AllowSpendStoredBlock
  case class WaitingBlock(block: Signed[AllowSpendBlock]) extends AllowSpendStoredBlock
  case class PostponedBlock(block: Signed[AllowSpendBlock]) extends AllowSpendStoredBlock
  case class AcceptedBlock(block: Hashed[AllowSpendBlock]) extends AllowSpendStoredBlock
  case class MajorityBlock(blockReference: ProofsHash) extends AllowSpendStoredBlock

  implicit val showAllowSpendStoredBlock: Show[AllowSpendStoredBlock] = {
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

  sealed trait AllowSpendBlockStorageError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

  case class BlockAcceptanceError(hash: ProofsHash, encountered: Option[AllowSpendStoredBlock]) extends AllowSpendBlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Accepted! Encountered state: ${encountered.show}."
  }

  case class BlockPostponementError(hash: ProofsHash, encountered: Option[AllowSpendStoredBlock]) extends AllowSpendBlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Postponed! Encountered state: ${encountered.show}."
  }

  case class BlockRestorationError(hash: ProofsHash, encountered: Option[AllowSpendStoredBlock]) extends AllowSpendBlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Waiting! Encountered state: ${encountered.show}."
  }

  case class BlockAlreadyStoredError(hash: ProofsHash, encountered: Option[AllowSpendStoredBlock]) extends AllowSpendBlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} is already stored. Encountered state: ${encountered.show}."
  }

  sealed trait BlockMajorityUpdateError extends AllowSpendBlockStorageError

  case class UnexpectedBlockStateWhenMarkingAsMajority(hash: ProofsHash, got: Option[AllowSpendStoredBlock])
      extends BlockMajorityUpdateError {
    val errorMessage: String = s"Accepted block to be marked as majority with hash: $hash not found! But got: $got"
  }

  case class UnexpectedBlockStateWhenRemovingAccepted(hash: ProofsHash, got: Option[AllowSpendStoredBlock])
      extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Accepted block to be removed during majority update with hash: $hash not found! But got: $got"
  }

  case class UnexpectedBlockStateWhenRemoving(hash: ProofsHash, got: Option[AllowSpendStoredBlock]) extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be removed during majority update with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenResetting(hash: ProofsHash, got: Option[AllowSpendStoredBlock]) extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be reset during majority update with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenAddingMajorityBlock(hash: ProofsHash, got: Option[AllowSpendStoredBlock])
      extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be added during majority update with hash: $hash not found in expected state! But got: $got"
  }

  sealed trait TipUpdateError extends AllowSpendBlockStorageError

  case class TipDeprecatingError(hash: ProofsHash, got: Option[AllowSpendStoredBlock]) extends TipUpdateError {
    override val errorMessage: String =
      s"Active tip to be deprecated with hash: $hash not found in expected state! But got: $got"
  }

  case class TipRemovalError(hash: ProofsHash, got: Option[AllowSpendStoredBlock]) extends TipUpdateError {
    override val errorMessage: String =
      s"Deprecated tip to be removed with hash: $hash not found in expected state! But got: $got"
  }

  case class ActiveTipAddingError(hash: ProofsHash, got: Option[AllowSpendStoredBlock]) extends TipUpdateError {
    override val errorMessage: String =
      s"Active tip to be added with hash: $hash not found in expected state! But got: $got"
  }

  case class DeprecatedTipAddingError(hash: ProofsHash, got: Option[AllowSpendStoredBlock]) extends TipUpdateError {
    override val errorMessage: String =
      s"Deprecated tip to be added with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenResettingPostponed(hash: ProofsHash, got: Option[AllowSpendStoredBlock]) extends TipUpdateError {
    val errorMessage: String =
      s"Postponed block to be reset to waiting block with hash: $hash not found in expected state! But got: $got"
  }

}
