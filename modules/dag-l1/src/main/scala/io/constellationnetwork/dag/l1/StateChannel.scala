package io.constellationnetwork.dag.l1

import java.security.KeyPair

import cats.Applicative
import cats.data.{NonEmptyList, OptionT}
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

import scala.concurrent.duration.DurationInt

import io.constellationnetwork.dag.l1.config.types.AppConfig
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusInput._
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusOutput.{CleanedConsensuses, FinalBlock, NoData}
import io.constellationnetwork.dag.l1.domain.consensus.block.Validator.{canStartOwnConsensus, isPeerInputValid}
import io.constellationnetwork.dag.l1.domain.consensus.block._
import io.constellationnetwork.dag.l1.domain.snapshot.programs.SnapshotProcessor.SnapshotProcessingResult
import io.constellationnetwork.dag.l1.http.p2p.P2PClient
import io.constellationnetwork.dag.l1.modules._
import io.constellationnetwork.ext.fs2.StreamOps
import io.constellationnetwork.kernel.CellError
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.security._

import fs2.{Pipe, Stream}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannel[
  F[_]: Async: HasherSelector: SecurityProvider: Random,
  P <: StateProof,
  S <: Snapshot,
  SI <: SnapshotInfo[P],
  R <: CliMethod
](
  appConfig: AppConfig,
  blockAcceptanceS: Semaphore[F],
  blockCreationS: Semaphore[F],
  blockStoringS: Semaphore[F],
  keyPair: KeyPair,
  p2PClient: P2PClient[F],
  programs: Programs[F, P, S, SI],
  queues: Queues[F],
  selfId: PeerId,
  services: Services[F, P, S, SI, R],
  storages: Storages[F, P, S, SI],
  validators: Validators[F],
  txHasher: Hasher[F]
) {

  private implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

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
      storages.transaction,
      validators.transaction,
      txHasher
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
        case None =>
          storages.l0Cluster.getRandomPeer.flatMap(p => programs.l0PeerDiscovery.discoverFrom(p))
        case Some(latestSnapshot) =>
          programs.l0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
      }
    }

  private val ownerBlockConsensusInputs: Stream[F, OwnerBlockConsensusInput] =
    inspectionTriggerInput.merge(ownRoundTriggerInput)

  private val peerBlockConsensusInputs: Stream[F, PeerBlockConsensusInput] = Stream
    .fromQueueUnterminated(queues.peerBlockConsensusInput)
    .evalFilter(in => HasherSelector[F].withCurrent(implicit hasher => isPeerInputValid(in)))
    .map(_.value)

  private val blockConsensusInputs: Stream[F, BlockConsensusInput] =
    ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

  private val runConsensus: Pipe[F, BlockConsensusInput, FinalBlock] =
    _.evalTap(input => logger.debug(s"Received block consensus input to process: ${input.show}"))
      .evalMap(blockConsensusInput =>
        OptionT(storages.lastSnapshot.getOrdinal)
          .getOrRaise(new IllegalStateException("Could not find the latest snapshot ordinal"))
          .flatMap(ordinal =>
            HasherSelector[F].withCurrent { implicit hasher =>
              new BlockConsensusCell[F](blockConsensusInput, blockConsensusContext, ordinal).run()
            }
          )
          .handleErrorWith(e => CellError(e.getMessage).asLeft[BlockConsensusOutput].pure[F])
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

  private val gossipBlock: Pipe[F, FinalBlock, FinalBlock] =
    _.evalTap { fb =>
      services.gossip
        .spreadCommon(fb.hashedBlock.signed)
        .handleErrorWith(e => logger.warn(e)("Block gossip spread failed!"))
    }

  private val peerBlocks: Stream[F, FinalBlock] = Stream
    .fromQueueUnterminated(queues.peerBlock)
    .evalMap(block => HasherSelector[F].withCurrent(implicit hasher => block.toHashedWithSignatureCheck))
    .evalTap {
      case Left(e)  => logger.warn(e)(s"Received an invalidly signed peer block!")
      case Right(_) => Async[F].unit
    }
    .collect {
      case Right(hashedBlock) => FinalBlock(hashedBlock)
    }

  private val storeBlock: Pipe[F, FinalBlock, Unit] =
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

  private val sendBlockToL0: Pipe[F, FinalBlock, FinalBlock] =
    _.evalTap { fb =>
      storages.l0Cluster.getPeers
        .map(_.toNonEmptyList.toList)
        .flatMap(_.filterA(p => services.collateral.hasCollateral(p.id)))
        .flatMap(peers => Random[F].shuffleList(peers))
        .map(peers => peers.headOption)
        .flatMap { maybeL0Peer =>
          maybeL0Peer.fold(logger.warn("No available L0 peer")) { l0Peer =>
            p2PClient.l0BlockOutputClient
              .sendL1Output(fb.hashedBlock.signed)(l0Peer)
              .ifM(Applicative[F].unit, logger.warn("Sending block to L0 failed."))
          }
        }
        .handleErrorWith { err =>
          logger.error(err)("Error sending block to L0")
        }
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
                HasherSelector[F].withCurrent { implicit hasher =>
                  services.block
                    .accept(signedBlock)
                }
                  .handleErrorWith(logger.warn(_)(s"Failed acceptance of a block with ${hash.show}"))
          }
          .void
      )
    }

  val globalSnapshotProcessing: Stream[F, Unit] = Stream
    .awakeEvery(10.seconds)
    .evalMap(_ => services.globalL0.pullGlobalSnapshots)
    .evalTap { snapshots =>
      def log(snapshot: Hashed[GlobalIncrementalSnapshot]) =
        logger.info(s"Pulled following global snapshot: ${SnapshotReference.fromHashedSnapshot(snapshot).show}")

      snapshots match {
        case Left((snapshot, _)) => log(snapshot)
        case Right(snapshots)    => snapshots.traverse(log).void
      }
    }
    .evalMapLocked(NonEmptyList.of(blockAcceptanceS, blockCreationS, blockStoringS)) {
      case Left((snapshot, state)) =>
        HasherSelector[F].withCurrent { implicit hasher =>
          programs.snapshotProcessor.process((snapshot, state).asLeft[Hashed[GlobalIncrementalSnapshot]]).map(List(_))
        }
      case Right(snapshots) =>
        (snapshots, List.empty[SnapshotProcessingResult]).tailRecM {
          case (snapshot :: nextSnapshots, aggResults) =>
            HasherSelector[F].withCurrent { implicit hasher =>
              programs.snapshotProcessor
                .process(snapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])
            }
              .map(result => (nextSnapshots, aggResults :+ result).asLeft[List[SnapshotProcessingResult]])

          case (Nil, aggResults) =>
            aggResults.asRight[(List[Hashed[GlobalIncrementalSnapshot]], List[SnapshotProcessingResult])].pure[F]
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
    F[_]: Async: HasherSelector: SecurityProvider: Random,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P],
    R <: CliMethod
  ](
    appConfig: AppConfig,
    keyPair: KeyPair,
    p2PClient: P2PClient[F],
    programs: Programs[F, P, S, SI],
    queues: Queues[F],
    selfId: PeerId,
    services: Services[F, P, S, SI, R],
    storages: Storages[F, P, S, SI],
    validators: Validators[F],
    txHasher: Hasher[F]
  ): F[StateChannel[F, P, S, SI, R]] =
    for {
      blockAcceptanceS <- Semaphore(1)
      blockCreationS <- Semaphore(1)
      blockStoringS <- Semaphore(1)
    } yield
      new StateChannel[F, P, S, SI, R](
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
        validators,
        txHasher
      )
}
