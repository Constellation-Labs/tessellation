package org.tessellation.dag.l1

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration.DurationInt

import org.tessellation.dag.domain.block.L1Output
import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusOutput.{CleanedConsensuses, FinalBlock}
import org.tessellation.dag.l1.domain.consensus.block.Validator.{canStartOwnConsensus, isPeerInputValid}
import org.tessellation.dag.l1.domain.consensus.block.{BlockConsensusCell, BlockConsensusContext, BlockConsensusInput}
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.modules._
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel.{CellError, Ω}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannel[F[_]: Async: KryoSerializer: SecurityProvider: Random](
  appConfig: AppConfig,
  keyPair: KeyPair,
  p2PClient: P2PClient[F],
  programs: Programs[F],
  queues: Queues[F],
  selfId: PeerId,
  services: Services[F],
  storages: Storages[F],
  validators: Validators[F]
) {

  private implicit val logger = Slf4jLogger.getLogger[F]

  private val blockConsensusContext =
    BlockConsensusContext[F](
      p2PClient.blockConsensus,
      storages.block,
      validators.block,
      storages.cluster,
      appConfig.consensus,
      storages.consensus,
      keyPair,
      selfId,
      storages.transaction
    )

  private val inspectionTriggerInput: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .as(InspectionTrigger)

  private val ownRoundTriggerInput: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .evalFilter { _ =>
      canStartOwnConsensus(storages.consensus, storages.node, storages.cluster, appConfig.consensus.peersCount)
    }
    .as(OwnRoundTrigger)

  private val ownerBlockConsensusInputs: Stream[F, OwnerBlockConsensusInput] =
    inspectionTriggerInput.merge(ownRoundTriggerInput)

  private val peerBlockConsensusInputs: Stream[F, PeerBlockConsensusInput] = Stream
    .fromQueueUnterminated(queues.peerBlockConsensusInput)
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
            case CleanedConsensuses(ids) =>
              Stream.eval(logger.warn(s"Cleaned following timed-out consensuses: $ids")) >>
                Stream.empty
            case NullTerminal => Stream.empty
            case other =>
              Stream.eval(logger.warn(s"Unexpected ohm in block consensus occurred: $other")) >>
                Stream.empty
          }
      }

  private val gossipBlock: Pipe[F, FinalBlock, FinalBlock] =
    _.evalTap { fb =>
      services.gossip.spread(fb.hashedBlock.signed).handleErrorWith(e => logger.warn(e)("Block gossip spread failed!"))
    }

  private val peerBlocks: Stream[F, FinalBlock] = Stream
    .fromQueueUnterminated(queues.peerBlock)
    .evalMap(_.hashWithSignatureCheck)
    .evalTap {
      case Left(e)  => logger.warn(e)(s"Received an invalidly signed peer block!")
      case Right(_) => Async[F].unit
    }
    .collect {
      case Right(hashedBlock) => FinalBlock(hashedBlock)
    }

  private val storeBlock: Pipe[F, FinalBlock, Unit] =
    _.evalMap { fb =>
      storages.block.store(fb.hashedBlock).handleErrorWith(e => logger.debug(e)("Block storing failed."))
    }

  private val sendBlockToL0: Pipe[F, FinalBlock, Unit] =
    _.evalMap { fb =>
      for {
        tips <- storages.block
          .pullTips(appConfig.tips.minimumTipsCount)

        l0Peer <- storages.l0Cluster.getPeers
          .flatMap(peers => Random[F].shuffleList(peers.toNonEmptyList.toList))
          .map(peers => peers.head)

        signedOutput <- Signed.forAsyncKryo[F, L1Output](L1Output(fb.hashedBlock.signed, tips), keyPair)

        _ <- p2PClient.l0DAGCluster
          .sendL1Output(signedOutput)(l0Peer)
          .ifM(Applicative[F].unit, logger.warn("Sending block to L0 failed."))

      } yield ()
    }

  private val blockAcceptance: Stream[F, Unit] = Stream
    .awakeEvery(1.seconds)
    .evalMap(_ => storages.block.getWaiting)
    .evalTap { awaiting =>
      if (awaiting.nonEmpty) logger.debug(s"Pulled following blocks for acceptance ${awaiting.keySet}")
      else Async[F].unit
    }
    .evalMap(
      _.toList
        .sortBy(_._2.value.height.value)
        .traverse {
          case (hash, signedBlock) =>
            logger.debug(s"Acceptance of a block $hash starts!") >>
              services.block
                .accept(signedBlock)
                .handleErrorWith(logger.warn(_)(s"Failed acceptance of a block with hash=$hash"))

        }
        .void
    )

  private val globalSnapshotProcessing: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap(_ => services.l0.pullGlobalSnapshots)
    .flatMap(snapshots => Stream.fromIterator(snapshots.iterator, 1))
    .evalMap(snapshot => logger.info(s"Pulled following global snapshot: $snapshot"))

  private val blockConsensus: Stream[F, Unit] =
    blockConsensusInputs
      .through(runConsensus)
      .through(gossipBlock)
      .merge(peerBlocks)
      .through(fb => storeBlock(fb).merge(sendBlockToL0(fb)))

  val runtime: Stream[F, Unit] =
    blockConsensus
      .merge(blockAcceptance)
      .merge(globalSnapshotProcessing)

}
