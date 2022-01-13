package org.tessellation.dag.l1

import java.security.KeyPair

import cats.effect.Async
import cats.effect.std.{Queue, Random}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration.DurationInt

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.l1.domain.block.{BlockService, BlockStorage}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput.{
  OwnRoundTrigger,
  OwnerBlockConsensusInput,
  PeerBlockConsensusInput
}
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusOutput.FinalBlock
import org.tessellation.dag.l1.domain.consensus.block.Validator.{canStartOwnConsensus, isPeerInputValid}
import org.tessellation.dag.l1.domain.consensus.block.config.ConsensusConfig
import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.dag.l1.domain.consensus.block.storage.ConsensusStorage
import org.tessellation.dag.l1.domain.consensus.block.{BlockConsensusCell, BlockConsensusContext, BlockConsensusInput}
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel.{CellError, Ω}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.storage.ClusterStorage
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.node.NodeStorage
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannel[F[_]: Async: KryoSerializer: SecurityProvider: Random](
  blockConsensusClient: BlockConsensusClient[F],
  blockService: BlockService[F],
  blockStorage: BlockStorage[F],
  blockValidator: BlockValidator[F],
  clusterStorage: ClusterStorage[F],
  consensusConfig: ConsensusConfig,
  consensusStorage: ConsensusStorage[F],
  gossip: Gossip[F],
  keyPair: KeyPair,
  myId: PeerId,
  nodeStorage: NodeStorage[F],
  peerBlockConsensusInputQueue: Queue[F, Signed[PeerBlockConsensusInput]],
  peerBlockQueue: Queue[F, Signed[DAGBlock]],
  transactionStorage: TransactionStorage[F]
) {

  private implicit val logger = Slf4jLogger.getLogger[F]

  private val blockConsensusContext =
    BlockConsensusContext[F](
      blockConsensusClient,
      blockStorage,
      blockValidator,
      clusterStorage,
      consensusConfig,
      consensusStorage,
      keyPair,
      myId,
      transactionStorage
    )

  private val ownerBlockConsensusInputs: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .evalFilter(_ => canStartOwnConsensus(consensusStorage, nodeStorage, clusterStorage, consensusConfig.peersCount))
    .as(OwnRoundTrigger)

  private val peerBlockConsensusInputs: Stream[F, PeerBlockConsensusInput] = Stream
    .fromQueueUnterminated(peerBlockConsensusInputQueue)
    .evalFilter(isPeerInputValid(_))
    .map(_.value)

  private val blockConsensusInputs: Stream[F, BlockConsensusInput] =
    ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

  private val runConsensus: Pipe[F, BlockConsensusInput, FinalBlock] =
    _.evalTap(input => logger.debug(s"Received block consensus input to process: $input"))
      .evalMap(
        new BlockConsensusCell[F](_, blockConsensusContext)
          .run()
          .handleErrorWith(e => CellError(e.getMessage).asLeft[Ω].pure[F])
      )
      .flatMap {
        case Left(ce) =>
          Stream.eval(logger.warn(ce)(s"Error occurred during some step of block consensus.")) >>
            Stream.empty
        case Right(ohm) =>
          ohm match {
            case fb @ FinalBlock(hashedBlock) =>
              Stream
                .eval(logger.debug(s"Block created! Hash=${hashedBlock.hash} ProofsHash=${hashedBlock.proofsHash}"))
                .as(fb)
            case NullTerminal => Stream.empty
            case other =>
              Stream.eval(logger.warn(s"Unexpected ohm in block consensus occurred: $other")) >>
                Stream.empty
          }
      }

  private val gossipBlock: Pipe[F, FinalBlock, FinalBlock] =
    _.evalTap { fb =>
      gossip.spread(fb.hashedBlock.signed).handleErrorWith(e => logger.warn(e)("Block gossip spread failed!"))
    }

  private val peerBlocks: Stream[F, FinalBlock] = Stream
    .fromQueueUnterminated(peerBlockQueue)
    .evalMap(_.hashWithSignatureCheck)
    .evalTap {
      case Left(e)  => logger.warn(e)(s"Received an invalidly signed peer block!")
      case Right(_) => Async[F].unit
    }
    .collect {
      case Right(hashedBlock) => FinalBlock(hashedBlock)
    }

  private val storeBlock: Pipe[F, FinalBlock, Unit] =
    _.evalMap(fb => blockStorage.store(fb.hashedBlock))

  private val blockAcceptance: Stream[F, Unit] = Stream
    .awakeEvery(1.seconds)
    .evalMap(_ => blockStorage.getWaiting)
    .evalTap(awaiting => logger.debug(s"Pulled following blocks for acceptance ${awaiting.keySet}"))
    .evalMap(
      _.toList
        .sortBy(_._2.value.height.value)
        .traverse {
          case (hash, signedBlock) =>
            logger.debug(s"Acceptance of a block $hash starts!") >>
              blockService
                .accept(signedBlock)
                .handleErrorWith(logger.warn(_)(s"Failed acceptance of a block with hash=$hash"))

        }
        .void
    )

  private val blockConsensus: Stream[F, Unit] =
    blockConsensusInputs
      .through(runConsensus)
      .through(gossipBlock)
      .merge(peerBlocks)
      .through(storeBlock)

  val runtime: Stream[F, Unit] =
    blockConsensus
      .merge(blockAcceptance)

}
