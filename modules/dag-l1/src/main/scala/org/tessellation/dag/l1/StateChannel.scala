package org.tessellation.dag.l1

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.{Random, Semaphore}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._
import cats.{Applicative, Order}

import scala.concurrent.duration.DurationInt
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.dag.l1.config.types.AppConfig
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusOutput.{CleanedConsensuses, FinalBlock, NoData}
import org.tessellation.dag.l1.domain.consensus.block.Validator.{canStartOwnConsensus, isPeerInputValid}
import org.tessellation.dag.l1.domain.consensus.block._
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor.SnapshotProcessingResult
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.modules._
import org.tessellation.ext.fs2.StreamOps
import org.tessellation.kernel.CellError
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block.BlockConstructor
import org.tessellation.schema._
import org.tessellation.schema.height.Height
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.{Hashed, SecurityProvider}

import fs2.{Pipe, Stream}
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannel[
  F[_]: Async: KryoSerializer: SecurityProvider: Random,
  T <: Transaction: Encoder: Order: Ordering,
  B <: Block[T]: Encoder: TypeTag,
  S <: Snapshot[T, B],
  SI <: SnapshotInfo
](
  appConfig: AppConfig,
  blockAcceptanceS: Semaphore[F],
  blockCreationS: Semaphore[F],
  blockStoringS: Semaphore[F],
  keyPair: KeyPair,
  p2PClient: P2PClient[F, T, B, S, SI],
  programs: Programs[F, T, B, S, SI],
  queues: Queues[F, T, B],
  selfId: PeerId,
  services: Services[F, T, B, S, SI],
  storages: Storages[F, T, B, S, SI],
  validators: Validators[F, T, B]
)(implicit blockConstructor: BlockConstructor[T, B]) {

  private implicit val logger = Slf4jLogger.getLogger[F]

  private val blockConsensusContext =
    BlockConsensusContext[F, T, B](
      p2PClient.blockConsensus,
      storages.block,
      validators.block,
      storages.cluster,
      appConfig.consensus,
      storages.consensus,
      keyPair,
      selfId,
      storages.transaction,
      validators.transaction
    )

  private val inspectionTriggerInput: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .as(InspectionTrigger)

  private val ownRoundTriggerInput: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .evalMapLocked(blockCreationS) { _ =>
      canStartOwnConsensus(
        storages.consensus,
        storages.node,
        storages.cluster,
        storages.block,
        storages.transaction,
        appConfig.consensus.peersCount,
        appConfig.consensus.tipsCount
      ).handleErrorWith { e =>
        logger.warn(e)("Failure checking if own consensus can be kicked off!").map(_ => false)
      }
    }
    .filter(identity)
    .as(OwnRoundTrigger)

  private val l0PeerDiscovery: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap { _ =>
      storages.lastSnapshot.get.flatMap {
        _.fold(Applicative[F].unit) { latestSnapshot =>
          programs.l0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
        }
      }
    }

  private val ownerBlockConsensusInputs: Stream[F, OwnerBlockConsensusInput] =
    inspectionTriggerInput.merge(ownRoundTriggerInput)

  private val peerBlockConsensusInputs: Stream[F, PeerBlockConsensusInput[T]] = Stream
    .fromQueueUnterminated(queues.peerBlockConsensusInput)
    .evalFilter(isPeerInputValid(_))
    .map(_.value)

  private val blockConsensusInputs: Stream[F, BlockConsensusInput[T]] =
    ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

  private val runConsensus: Pipe[F, BlockConsensusInput[T], FinalBlock[B]] =
    _.evalTap(input => logger.debug(s"Received block consensus input to process: ${input.show}"))
      .evalMap(
        new BlockConsensusCell[F, T, B](_, blockConsensusContext)
          .run()
          .handleErrorWith(e => CellError(e.getMessage).asLeft[BlockConsensusOutput[B]].pure[F])
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
            case NoData => Stream.empty
          }
      }

  private val gossipBlock: Pipe[F, FinalBlock[B], FinalBlock[B]] =
    _.evalTap { fb =>
      services.gossip
        .spreadCommon(fb.hashedBlock.signed)
        .handleErrorWith(e => logger.warn(e)("Block gossip spread failed!"))
    }

  private val peerBlocks: Stream[F, FinalBlock[B]] = Stream
    .fromQueueUnterminated(queues.peerBlock)
    .evalMap(_.toHashedWithSignatureCheck)
    .evalTap {
      case Left(e)  => logger.warn(e)(s"Received an invalidly signed peer block!")
      case Right(_) => Async[F].unit
    }
    .collect {
      case Right(hashedBlock) => FinalBlock(hashedBlock)
    }

  private val storeBlock: Pipe[F, FinalBlock[B], Unit] =
    _.evalMapLocked(blockStoringS) { fb =>
      storages.lastSnapshot.getHeight.map(_.getOrElse(Height.MinValue)).flatMap { lastSnapshotHeight =>
        if (lastSnapshotHeight < fb.hashedBlock.height)
          storages.block.store(fb.hashedBlock).handleErrorWith(e => logger.debug(e)("Block storing failed."))
        else
          logger.debug(
            s"Block can't be stored! Block height not above last snapshot height! block:${fb.hashedBlock.height} <= snapshot: $lastSnapshotHeight"
          )
      }
    }

  private val sendBlockToL0: Pipe[F, FinalBlock[B], FinalBlock[B]] =
    _.evalTap { fb =>
      for {
        l0PeerOpt <- storages.l0Cluster.getPeers
          .map(_.toNonEmptyList.toList)
          .flatMap(_.filterA(p => services.collateral.hasCollateral(p.id)))
          .flatMap(peers => Random[F].shuffleList(peers))
          .map(peers => peers.headOption)

        _ <- l0PeerOpt.fold(logger.warn("No available L0 peer")) { l0Peer =>
          p2PClient.l0CurrencyCluster
            .sendL1Output(fb.hashedBlock.signed)(l0Peer)
            .ifM(Applicative[F].unit, logger.warn("Sending block to L0 failed."))
        }
      } yield ()
    }

  private val blockAcceptance: Stream[F, Unit] = Stream
    .awakeEvery(1.seconds)
    .evalMapLocked(blockAcceptanceS) { _ =>
      storages.block.getWaiting.flatTap { awaiting =>
        if (awaiting.nonEmpty) logger.debug(s"Pulled following blocks for acceptance ${awaiting.keySet}")
        else Async[F].unit
      }.flatMap(
        _.toList
          .sortBy(_._2.value.height)
          .traverse {
            case (hash, signedBlock) =>
              logger.debug(s"Acceptance of a block $hash starts!") >>
                services.block
                  .accept(signedBlock)
                  .handleErrorWith(logger.warn(_)(s"Failed acceptance of a block with ${hash.show}"))
          }
          .void
      )
    }

  private val globalSnapshotProcessing: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap(_ => services.globalL0.pullGlobalSnapshots)
    .evalTap { snapshots =>
      def log(snapshot: Hashed[IncrementalGlobalSnapshot]) =
        logger.info(s"Pulled following global snapshot: ${SnapshotReference.fromHashedSnapshot(snapshot).show}")

      snapshots match {
        case Left((snapshot, _)) => log(snapshot)
        case Right(snapshots)    => snapshots.traverse(log).void
      }
    }
    .evalMapLocked(NonEmptyList.of(blockAcceptanceS, blockCreationS, blockStoringS)) { snapshots =>
      snapshots match {
        case Left((snapshot, state)) =>
          programs.snapshotProcessor.process((snapshot, state).asLeft[Hashed[IncrementalGlobalSnapshot]]).map(List(_))
        case Right(snapshots) =>
          (snapshots, List.empty[SnapshotProcessingResult]).tailRecM {
            case (snapshot :: nextSnapshots, aggResults) =>
              programs.snapshotProcessor
                .process(snapshot.asRight[(Hashed[IncrementalGlobalSnapshot], GlobalSnapshotInfo)])
                .map(result => (nextSnapshots, aggResults :+ result).asLeft[List[SnapshotProcessingResult]])

            case (Nil, aggResults) =>
              aggResults.asRight[(List[Hashed[IncrementalGlobalSnapshot]], List[SnapshotProcessingResult])].pure[F]
          }
      }
    }
    .evalMap {
      _.traverse(result => logger.info(s"Snapshot processing result: ${result.show}")).void
    }

  private val blockConsensus: Stream[F, Unit] =
    blockConsensusInputs
      .through(runConsensus)
      .through(gossipBlock)
      .through(sendBlockToL0)
      .merge(peerBlocks)
      .through(storeBlock)

  val runtime: Stream[F, Unit] =
    blockConsensus
      .merge(blockAcceptance)
      .merge(globalSnapshotProcessing)
      .merge(l0PeerDiscovery)

}

object StateChannel {

  def make[
    F[_]: Async: KryoSerializer: SecurityProvider: Random,
    T <: Transaction: Encoder: Order: Ordering,
    B <: Block[T]: Encoder: TypeTag,
    S <: Snapshot[T, B],
    SI <: SnapshotInfo
  ](
    appConfig: AppConfig,
    keyPair: KeyPair,
    p2PClient: P2PClient[F, T, B, S, SI],
    programs: Programs[F, T, B, S, SI],
    queues: Queues[F, T, B],
    selfId: PeerId,
    services: Services[F, T, B, S, SI],
    storages: Storages[F, T, B, S, SI],
    validators: Validators[F, T, B]
  )(implicit blockConstructor: BlockConstructor[T, B]): F[StateChannel[F, T, B, S, SI]] =
    for {
      blockAcceptanceS <- Semaphore(1)
      blockCreationS <- Semaphore(1)
      blockStoringS <- Semaphore(1)
    } yield
      new StateChannel[F, T, B, S, SI](
        appConfig,
        blockAcceptanceS,
        blockCreationS,
        blockStoringS,
        keyPair,
        p2PClient,
        programs,
        queues,
        selfId,
        services,
        storages,
        validators
      )
}
