package org.tessellation.dag.l1.domain.block

import cats.Show
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.effect.std.Random
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import scala.util.control.NoStackTrace

import org.tessellation.dag.domain.block.{BlockReference, DAGBlock, Tips}
import org.tessellation.dag.l1.domain.block.BlockStorage._
import org.tessellation.ext.collection.MapRefUtils.MapRefOps
import org.tessellation.security.Hashed
import org.tessellation.security.hash.ProofsHash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosInt
import io.chrisdavenport.mapref.MapRef
import io.estatico.newtype.ops.toCoercibleIdOps
import monocle.macros.syntax.lens._

class BlockStorage[F[_]: Sync: Random](blocks: MapRef[F, ProofsHash, Option[StoredBlock]]) {

  implicit val showStoredBlock: Show[StoredBlock] = {
    case _: WaitingBlock  => "Waiting"
    case _: AcceptedBlock => "Accepted"
    case _: MajorityBlock => "Majority"
  }

  def areParentsAccepted(block: DAGBlock): F[Map[BlockReference, Boolean]] =
    block.parent.traverse { ref =>
      isBlockAccepted(ref).map(ref -> _)
    }.map(_.toList.toMap)

  private[block] def accept(hashedBlock: Hashed[DAGBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case Some(_: WaitingBlock) => (AcceptedBlock(hashedBlock).some, ().asRight)
      case other                 => (other, BlockAcceptanceError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F])
      .flatMap(_ => updateParentUsages(hashedBlock))

  def store(hashedBlock: Hashed[DAGBlock]): F[Unit] =
    blocks(hashedBlock.proofsHash).modify {
      case None  => (WaitingBlock(hashedBlock.signed).some, ().asRight)
      case other => (other, BlockAlreadyStoredError(hashedBlock.proofsHash, other).asLeft)
    }.flatMap(_.liftTo[F])

  def getWaiting: F[Map[ProofsHash, Signed[DAGBlock]]] =
    blocks.toMap.map(_.collect { case (hash, WaitingBlock(block)) => hash -> block })

  def getTips(tipsCount: PosInt): F[Option[Tips]] =
    blocks.toMap
      .map(_.collect { case (_, MajorityBlock(blockReference, _, Active)) => blockReference })
      .map(_.toList)
      .flatMap(Random[F].shuffleList)
      .map(_.sortBy(_.height.coerce))
      .map {
        case tips if tips.size >= tipsCount =>
          NonEmptyList
            .fromList(tips.take(tipsCount))
            .map(Tips(_))
        case _ => None
      }

  private def isBlockAccepted(blockReference: BlockReference): F[Boolean] =
    blocks(blockReference.hash).get.map(_.exists(_.isInstanceOf[MajorityBlock]))

  private def updateParentUsages(hashedBlock: Hashed[DAGBlock]): F[Unit] = {
    val hash = hashedBlock.proofsHash
    hashedBlock.signed.value.parent.toList.traverse { blockReference =>
      blocks(blockReference.hash).modify {
        case Some(majority: MajorityBlock) => (majority.addUsage(hash).some, ().asRight)
        case other                         => (other, TipUsageUpdateError(hash, blockReference.hash, other).asLeft)
      }.flatMap(_.liftTo[F])
    }
  }.void
}

object BlockStorage {

  def make[F[_]: Sync: Random]: F[BlockStorage[F]] =
    MapRef.ofConcurrentHashMap[F, ProofsHash, StoredBlock]().map(new BlockStorage[F](_))

  sealed trait StoredBlock
  case class WaitingBlock(block: Signed[DAGBlock]) extends StoredBlock
  case class AcceptedBlock(block: Hashed[DAGBlock]) extends StoredBlock
  case class MajorityBlock(blockReference: BlockReference, usages: Set[ProofsHash], tipStatus: TipStatus)
      extends StoredBlock {
    def addUsage(hash: ProofsHash): MajorityBlock = this.focus(_.usages).modify(_ + hash)
  }

  sealed trait TipStatus
  case object Active extends TipStatus
  case object Deprecated extends TipStatus

  sealed trait BlockStorageError extends NoStackTrace {
    val errorMessage: String
    override def getMessage: String = errorMessage
  }
  case class TipUsageUpdateError(child: ProofsHash, parent: ProofsHash, encountered: Option[StoredBlock])(
    implicit s: Show[StoredBlock]
  ) extends BlockStorageError {

    val errorMessage: String =
      s"Parent block with hash=${parent.show} not found in majority when updating usage! Child hash=${child.show}. Encountered state: ${encountered.show}"
  }
  case class BlockAcceptanceError(hash: ProofsHash, encountered: Option[StoredBlock])(implicit s: Show[StoredBlock])
      extends BlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} failed to transition state to Accepted! Encountered state: ${encountered.show}."
  }
  case class BlockAlreadyStoredError(hash: ProofsHash, encountered: Option[StoredBlock])(implicit s: Show[StoredBlock])
      extends BlockStorageError {

    val errorMessage: String =
      s"Block with hash=${hash.show} is already stored. Encountered state: ${encountered.show}."
  }
}
