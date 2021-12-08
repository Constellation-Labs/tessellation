package org.tessellation.dag.l1

import java.util.UUID

import cats.Order
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.concurrent.duration.DurationInt

import org.tessellation.dag.l1.storage.BlockStorage.{AcceptedBlock, KnownBlock}
import org.tessellation.dag.l1.storage.MapRefUtils.MapRefOps
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel.{CellError, Ω}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.node.NodeState.Ready
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto.autoUnwrap
import fs2.{Pipe, Stream}
import io.estatico.newtype.ops.toCoercibleIdOps
import org.typelevel.log4cats.slf4j.Slf4jLogger

class DAGStateChannel[F[_]: Async: KryoSerializer: SecurityProvider: Random](
  context: DAGStateChannelContext[F]
) {

  private val logger = Slf4jLogger.getLogger[F]

  private def enoughPeersForConsensus: F[Boolean] =
    context.clusterStorage.getPeers
      .map(_.filter(_.state == Ready))
      .map(_.size >= context.consensusConfig.peersCount)

  // TODO: I guess we could have this evaluation happen inside of the cell as coalgebra step and we would only sent Tick from here?
  private val ownerL1Input: Stream[F, L1OwnerDAGBlockData] = Stream
    .repeatEval(
      enoughPeersForConsensus
        .flatMap(enoughPeers => context.consensusStorage.ownConsensus.get.map(rd => (rd.isEmpty, enoughPeers)))
    )
    .delayBy(120.seconds)
    .metered(5.seconds)
    .evalTap {
      case (noRoundInProgress, areEnoughPeers) =>
        logger.debug(s"noRoundInProgress = $noRoundInProgress areEnoughPeers=$areEnoughPeers")
    }
    .map { case (bool, bool1) => if (bool && bool1) OwnRoundTrigger.some else none[OwnRoundTrigger.type] }
    .unNone

  private val peerL1Input: Stream[F, L1PeerDAGBlockData] = Stream.fromQueueUnterminated(context.l1PeerDAGBlockDataQueue)

  private val l1Input: Stream[F, L1DAGBlockData] = ownerL1Input.merge(peerL1Input)

  private val l1Pipeline: Pipe[F, L1DAGBlockData, FinalBlock] =
    _.evalTap(l1Data => logger.debug(s"Received l1Data to process: $l1Data"))
      .evalMap(new L1DAGBlockCell(_, context).run().handleErrorWith(e => CellError(e.getMessage).asLeft[Ω].pure[F]))
      .flatMap {
        case Left(ce) =>
          Stream.eval(logger.warn(ce)(s"Error occurred during some step of L1 consensus.")) >>
            Stream.empty
        case Right(ohm) =>
          ohm match {
            case fb @ FinalBlock(hashedBlock) =>
              Stream
                .eval(logger.debug(s"Block created! Hash=${hashedBlock.hash} ProofsHash=${hashedBlock.proofsHash}"))
                .as(fb)
            // TODO: we could send more output types from the L1DAGCell and log them here, e.g. ProposalProcessed, BlockCreated, RoundIsBeingCancelled
            case NullTerminal => Stream.empty
            case other =>
              Stream.eval(logger.warn(s"Unexpected ohm in L1 consensus occurred: $other")) >>
                Stream.empty
          }
      }

  private val l1GossipBlock: Pipe[F, FinalBlock, FinalBlock] =
    _.evalTap(
      fb =>
        context.gossip.spread(fb.hashedBlock.signed).handleErrorWith(e => logger.warn(e)("Block gossip spread failed!"))
    )

  private val l1PeerDAGBlockInput: Stream[F, FinalBlock] = Stream.fromQueueUnterminated(context.l1PeerDAGBlockQueue)

  private val l1PostPipeline: Pipe[F, FinalBlock, Unit] =
    _.evalMap(fb => context.blockStorage.storeBlock(fb.hashedBlock))

  private val l1Acceptance: Stream[F, Unit] = Stream
    .repeatEval(context.blockStorage.getWaiting)
    .evalTap(awaiting => logger.debug(s"Pulled following blocks for acceptance ${awaiting.keySet}"))
    .evalMap(
      _.toList
      // TODO: what about block at the same height with consecutive transactions where one block at the same height (or even where older block needs to be accepted first) should be accepted after another?
      //  I guess it's fine and roughly sorting based on height is enough. If the block fails to accept all the other blocks will attempt it's acceptance and on the consecutive pull this failing block may succeed.
      //  That's similar to recalculated queue we had before but we are checking conditions during acceptance and not prechecking them before the acceptance. Seems simpler and ok.
        .sortBy(_._2.value.height.value)
        .traverse {
          case (hash, signedBlock) =>
            logger.debug(s"Acceptance of a block $hash starts!") >>
              context.blockValidator
                .accept(signedBlock)
                .handleErrorWith(logger.warn(_)(s"Failed acceptance of a block with hash=$hash"))

        }
        .void
    )
    .metered(1.seconds)

  private val l1Statistics: Stream[F, Unit] = Stream
    .repeatEval(context.blockStorage.getAll)
    .evalMap(blocks => context.consensusStorage.peerConsensuses.toMap.map((blocks, _)))
    .metered(10.seconds)
    .evalMap {
      case (blocks, consensuses) =>
        for {
          _ <- logger.debug(s"Total blocks count is ${blocks.size}")
          _ <- logger.debug(s"Known blocks count is ${blocks.count(_._2.isInstanceOf[KnownBlock])}")
          acceptedBlocks = blocks.collect { case (_, ab @ AcceptedBlock(_, _, _, _)) => ab }.toSeq
          _ <- logger.debug(s"Accepted blocks count is ${blocks.count(_._2.isInstanceOf[AcceptedBlock])}")
          _ <- logger.debug(
            s"Max accepted block height is ${acceptedBlocks.maxByOption(_.block.signed.value.height.value).map(_.block.signed.value.height)}"
          )
          _ <- logger.debug(s"Tips count is ${acceptedBlocks.count(_.isTip)}")
          _ <- logger.debug(
            s"Tips heights are ${acceptedBlocks.collect {
              case AcceptedBlock(block, _, _, true) => block.signed.value.height
            }.sortBy(_.value)}"
          )
          _ <- logger.debug(
            s"Tips are ${acceptedBlocks.collect { case AcceptedBlock(block, _, _, true) => block.proofsHash }}"
          )
          _ <- logger.debug(s"Consensuses in progress: ${consensuses.size}")
        } yield ()
    }

  val runtimePipeline: Stream[F, Unit] =
    l1Input
      .through(l1Pipeline)
      .through(l1GossipBlock)
      .merge(l1PeerDAGBlockInput)
      .through(l1PostPipeline)
      .merge(l1Acceptance)
      .merge(l1Statistics)

}

object DAGStateChannel {
  implicit val ordinalNumberOrder: Order[Signed[Transaction]] = (x: Signed[Transaction], y: Signed[Transaction]) =>
    implicitly[Order[BigInt]].compare(x.value.ordinal.coerce, y.value.ordinal.coerce)

  implicit val ordinalNumberOrdering: Ordering[Signed[Transaction]] =
    (x: Signed[Transaction], y: Signed[Transaction]) =>
      implicitly[Ordering[BigInt]].compare(x.value.ordinal.coerce, y.value.ordinal.coerce)

  @derive(encoder, decoder)
  case class RoundId(uuid: UUID)
}
