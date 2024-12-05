package io.constellationnetwork.node.shared.domain.tokenlock.block

import cats.Show
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import io.constellationnetwork.ext.collection.MapRefUtils.MapRefOps
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockStorage._
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.security.Hashed
import io.constellationnetwork.security.hash.ProofsHash
import io.constellationnetwork.security.signature.Signed

import io.chrisdavenport.mapref.MapRef

class TokenLockBlockStorage[F[_]: Sync: Random](blocks: MapRef[F, ProofsHash, Option[TokenLockStoredBlock]]) {

  def getState(): F[Map[ProofsHash, TokenLockStoredBlock]] =
    blocks.toMap

  def accept(hashedBlock: Hashed[TokenLockBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case Some(WaitingBlock(_)) => (AcceptedBlock(hashedBlock).some, hashedBlock.asRight)
      case other                 => (other, BlockAcceptanceError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F]).void

  def postpone(hashedBlock: Hashed[TokenLockBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case Some(WaitingBlock(_)) => (PostponedBlock(hashedBlock.signed).some, hashedBlock.asRight)
      case other                 => (other, BlockPostponementError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F]).void

  def adjustToMajority(
    toAdd: Set[Hashed[TokenLockBlock]] = Set.empty,
    toMarkMajority: Set[ProofsHash] = Set.empty,
    acceptedToRemove: Set[ProofsHash] = Set.empty,
    obsoleteToRemove: Set[ProofsHash] = Set.empty,
    toReset: Set[ProofsHash] = Set.empty,
    postponedToWaiting: Set[ProofsHash] = Set.empty
  ): F[Unit] = {

    def addMajorityBlocks: F[Unit] =
      toAdd.toList.traverse {
        case tokenLockBlock =>
          val reference = tokenLockBlock.proofsHash
          blocks(tokenLockBlock.proofsHash).modify {
            case Some(WaitingBlock(_)) | Some(PostponedBlock(_)) | None =>
              (MajorityBlock(reference).some, tokenLockBlock.asRight)
            case other => (other, UnexpectedBlockStateWhenAddingMajorityBlock(tokenLockBlock.proofsHash, other).asLeft)
          }.flatMap(_.liftTo[F])
      }.void

    def markMajorityBlocks: F[Unit] =
      toMarkMajority.toList.traverse {
        case proofsHash =>
          blocks(proofsHash).modify {
            case Some(AcceptedBlock(block)) =>
              val reference = block.proofsHash
              (MajorityBlock(reference).some, ().asRight)
            case other =>
              (other, UnexpectedBlockStateWhenMarkingAsMajority(proofsHash, other).asLeft)
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

  def store(hashedBlock: Hashed[TokenLockBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case None  => (WaitingBlock(hashedBlock.signed).some, ().asRight)
      case other => (other, BlockAlreadyStoredError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F])

  def getWaiting: F[Map[ProofsHash, Signed[TokenLockBlock]]] =
    blocks.toMap.map(_.collect { case (hash, WaitingBlock(block)) => hash -> block })
}

object TokenLockBlockStorage {

  def make[F[_]: Sync: Random]: F[TokenLockBlockStorage[F]] =
    MapRef.ofConcurrentHashMap[F, ProofsHash, TokenLockStoredBlock]().map(new TokenLockBlockStorage[F](_))

  def make[F[_]: Sync: Random](blocks: Map[ProofsHash, TokenLockStoredBlock]): F[TokenLockBlockStorage[F]] =
    MapRef.ofSingleImmutableMap(blocks).map(new TokenLockBlockStorage[F](_))

  sealed trait TokenLockStoredBlock
  case class WaitingBlock(block: Signed[TokenLockBlock]) extends TokenLockStoredBlock
  case class PostponedBlock(block: Signed[TokenLockBlock]) extends TokenLockStoredBlock
  case class AcceptedBlock(block: Hashed[TokenLockBlock]) extends TokenLockStoredBlock
  case class MajorityBlock(blockReference: ProofsHash) extends TokenLockStoredBlock

  implicit val showTokenLockStoredBlock: Show[TokenLockStoredBlock] = {
    case _: WaitingBlock   => "Waiting"
    case _: PostponedBlock => "Postponed"
    case _: AcceptedBlock  => "Accepted"
    case _: MajorityBlock  => "Majority"
  }

  case class MajorityReconciliationData(
    waitingInRange: Set[ProofsHash],
    postponedInRange: Set[ProofsHash],
    relatedPostponed: Set[ProofsHash],
    acceptedInRange: Set[ProofsHash],
    acceptedAbove: Set[ProofsHash]
  )

  sealed trait TokenLockBlockStorageError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }

  case class BlockAcceptanceError(hash: ProofsHash, encountered: Option[TokenLockStoredBlock]) extends TokenLockBlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Accepted! Encountered state: ${encountered.show}."
  }

  case class BlockPostponementError(hash: ProofsHash, encountered: Option[TokenLockStoredBlock]) extends TokenLockBlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Postponed! Encountered state: ${encountered.show}."
  }

  case class BlockRestorationError(hash: ProofsHash, encountered: Option[TokenLockStoredBlock]) extends TokenLockBlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Waiting! Encountered state: ${encountered.show}."
  }

  case class BlockAlreadyStoredError(hash: ProofsHash, encountered: Option[TokenLockStoredBlock]) extends TokenLockBlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} is already stored. Encountered state: ${encountered.show}."
  }

  sealed trait BlockMajorityUpdateError extends TokenLockBlockStorageError

  case class UnexpectedBlockStateWhenMarkingAsMajority(hash: ProofsHash, got: Option[TokenLockStoredBlock])
      extends BlockMajorityUpdateError {
    val errorMessage: String = s"Accepted block to be marked as majority with hash: $hash not found! But got: $got"
  }

  case class UnexpectedBlockStateWhenRemovingAccepted(hash: ProofsHash, got: Option[TokenLockStoredBlock])
      extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Accepted block to be removed during majority update with hash: $hash not found! But got: $got"
  }

  case class UnexpectedBlockStateWhenRemoving(hash: ProofsHash, got: Option[TokenLockStoredBlock]) extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be removed during majority update with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenResetting(hash: ProofsHash, got: Option[TokenLockStoredBlock]) extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be reset during majority update with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenAddingMajorityBlock(hash: ProofsHash, got: Option[TokenLockStoredBlock])
      extends BlockMajorityUpdateError {

    val errorMessage: String =
      s"Block to be added during majority update with hash: $hash not found in expected state! But got: $got"
  }

  case class UnexpectedBlockStateWhenResettingPostponed(hash: ProofsHash, got: Option[TokenLockStoredBlock])
      extends TokenLockBlockStorageError {
    val errorMessage: String =
      s"Postponed block to be reset to waiting block with hash: $hash not found in expected state! But got: $got"
  }
}
