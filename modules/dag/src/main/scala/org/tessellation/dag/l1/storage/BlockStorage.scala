package org.tessellation.dag.l1.storage

import cats.data.NonEmptyList
import cats.effect.Sync
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import org.tessellation.dag.l1.storage.BlockStorage.{PulledTips, StoredBlock}
import org.tessellation.dag.l1.storage.MapRefUtils._
import org.tessellation.dag.l1.{BlockReference, DAGBlock}
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.chrisdavenport.mapref.MapRef
import io.estatico.newtype.ops.toCoercibleIdOps

trait BlockStorage[F[_]] {
  def areParentsAccepted(block: DAGBlock): F[Boolean]
  def acceptBlock(hashedBlock: Hashed[DAGBlock]): F[Unit]
  def storeBlock(hashedBlock: Hashed[DAGBlock]): F[Unit]
  def getWaiting: F[Map[Hash, Signed[DAGBlock]]]
  def getAll: F[Map[Hash, StoredBlock]]
  def handleTipsUpdate(hashedBlock: Hashed[DAGBlock]): F[Unit]
  def pullTips(tipsCount: Int): F[Option[PulledTips]]
}

object BlockStorage {

  sealed trait KnownBlockState
  case object Waiting extends KnownBlockState
  case object InAcceptance extends KnownBlockState
  //case object WaitingForAcceptanceAfterRedownload extends KnownBlockState
  sealed trait AcceptedBlockState
  case object Accepted extends AcceptedBlockState
  case object InMajority extends AcceptedBlockState // was InSnapshot but I'm not sure we will subscribe to snapshots but rather to Majority snapshots???

  sealed trait StoredBlock
  // TODO: do we need it? intended for resolver
  case class UnknownBlock(usages: Set[Hash]) extends StoredBlock
  case class KnownBlock(block: Signed[DAGBlock], state: KnownBlockState, usages: Set[Hash]) extends StoredBlock
  case class AcceptedBlock(
    block: Hashed[DAGBlock],
    state: AcceptedBlockState,
    usages: Set[Hash],
    isTip: Boolean
  ) extends StoredBlock

  // Assumption that we want at least one tip
  @derive(encoder, decoder)
  case class PulledTips(value: NonEmptyList[BlockReference])

  // TODO: used for testing for now
//  def make[F[_]: Sync](genesisBlocks: Seq[Hashed[DAGBlock]]): F[BlockStorage[F]] =
//    for {
//      blocks <- MapRef.ofConcurrentHashMap[F, Hash, StoredBlock]()
//      _ <- genesisBlocks.traverse(
//        hashedBlock =>
//          blocks(hashedBlock.proofsHash).set(AcceptedBlock(hashedBlock, Accepted, Set.empty, isTip = true).some)
//      )
//    } yield make(blocks)

  def make[F[_]: Sync]: F[BlockStorage[F]] =
    MapRef.ofConcurrentHashMap[F, Hash, StoredBlock]().map(make(_))

  def make[F[_]: Sync](blocks: MapRef[F, Hash, Option[StoredBlock]]): BlockStorage[F] =
    new BlockStorage[F] {

      def areParentsAccepted(block: DAGBlock): F[Boolean] =
        blocks.toMap
          .map(_.collect { case (hash, ab: AcceptedBlock) => hash -> ab })
          .map(acceptedBlocks => block.parent.forall(ref => acceptedBlocks.contains(ref.hash)))

      def acceptBlock(hashedBlock: Hashed[DAGBlock]): F[Unit] =
        // TODO: we could also expect the correct state, so block should be Known and InAcceptance to state shift, otherwise no state update and error, hmmmm
        // TODO: we could also fetch the whole map before the update and check if we should accept it as a Tip or not
        blocks(hashedBlock.proofsHash).modify {
          case Some(value) =>
            value match {
              case UnknownBlock(usages)     => (AcceptedBlock(hashedBlock, Accepted, usages, isTip = false).some, ())
              case KnownBlock(_, _, usages) => (AcceptedBlock(hashedBlock, Accepted, usages, isTip = false).some, ())
              case ab: AcceptedBlock        => (ab.some, ())
            }
          case None => (AcceptedBlock(hashedBlock, Accepted, Set.empty, isTip = false).some, ())
        }

      def storeBlock(hashedBlock: Hashed[DAGBlock]): F[Unit] =
        blocks(hashedBlock.proofsHash).modify {
          case None                       => (KnownBlock(hashedBlock.signed, Waiting, Set.empty[Hash]).some, ())
          case Some(UnknownBlock(usages)) => (KnownBlock(hashedBlock.signed, Waiting, usages).some, ())
          // I guess that's not an error case
          case other => (other, ())
        }

      def getWaiting: F[Map[Hash, Signed[DAGBlock]]] =
        blocks.toMap.map(_.collect { case (hash, KnownBlock(block, Waiting, _)) => hash -> block })

      def getAll: F[Map[Hash, StoredBlock]] = blocks.toMap

      case class UnexpectedStateShift(message: String) extends Throwable(message)

      def handleTipsUpdate(hashedBlock: Hashed[DAGBlock]): F[Unit] =
        for {
          allBlocks <- blocks.toMap
          allTips = allBlocks.collect { case (hash, ab @ AcceptedBlock(_, _, _, true)) => hash -> ab }
          parentHashToTip = hashedBlock.signed.value.parent.map(br => br.hash -> allTips.get(br.hash)).toList.toMap
          notTipParents = parentHashToTip.collect { case (hash, None) => hash }.toList
          tipParents = parentHashToTip.collect { case (hash, Some(value)) => hash -> value }
          tipParentsSize = tipParents.size
          tipParentsToRemove = tipParents.filter {
            case (_, block) =>
              allTips.size >= 2 + tipParentsSize && (allTips.size > 10 + tipParentsSize || block.usages.size >= 1)
          }
          tipParentsToLeave = tipParents -- tipParentsToRemove.keySet
          remainingTips = allTips -- tipParentsToRemove.keySet
          minTipHeight = remainingTips.values.map(_.block.signed.value.height.coerce).minOption.getOrElse(0L)
          tryAddAsATip = remainingTips.size < 10 && minTipHeight < hashedBlock.signed.value.height.coerce
          _ <- notTipParents.traverse(blocks(_).modify {
            case Some(ap @ AcceptedBlock(_, _, usages, false)) =>
              (ap.copy(usages = usages + hashedBlock.proofsHash).some, ())
            case unexpected =>
              (unexpected, UnexpectedStateShift(s"Expected Accepted block that IS NOT a tip! Got $unexpected."))
          })
          _ <- tipParentsToRemove.keys.toList.traverse(blocks(_).modify {
            case Some(ap @ AcceptedBlock(_, _, usages, true)) =>
              (ap.copy(usages = usages + hashedBlock.proofsHash, isTip = false).some, ())
            case unexpected =>
              (unexpected, UnexpectedStateShift(s"Expected Accepted block that IS a tip! Got $unexpected."))
          })
          _ <- tipParentsToLeave.keys.toList.traverse(blocks(_).modify {
            case Some(ap @ AcceptedBlock(_, _, usages, true)) =>
              (ap.copy(usages = usages + hashedBlock.proofsHash).some, ())
            case unexpected =>
              (
                unexpected,
                UnexpectedStateShift(s"Expected Accepted block that IS a tip and will remain a tip! Got $unexpected")
              )
          })
          _ <- if (tryAddAsATip)
            blocks(hashedBlock.proofsHash).modify {
              case Some(ap @ AcceptedBlock(_, _, usages, false)) if usages.size <= 1 => (ap.copy(isTip = true).some, ())
              case other                                                             => (other, ())
            } else Sync[F].unit
        } yield ()

      def pullTips(tipsCount: Int): F[Option[PulledTips]] =
        for {
          allBlocks <- blocks.toMap
          allTips = allBlocks.collect { case (_, AcceptedBlock(block, _, _, true)) => block }.toList
            .sortBy(_.signed.value.height.coerce)
          pulledTips = allTips match {
            case tips if tips.size >= tipsCount =>
              NonEmptyList
                .fromList(tips.take(tipsCount))
                .map(parents => PulledTips(parents.map(p => BlockReference(p.proofsHash, p.signed.value.height))))
            case _ => None
          }
        } yield pulledTips
    }
}
