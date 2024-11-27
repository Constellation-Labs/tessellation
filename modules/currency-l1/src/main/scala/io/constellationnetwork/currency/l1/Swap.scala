package io.constellationnetwork.currency.l1

import java.security.KeyPair

import cats.Applicative
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.l1.cli.method.Run
import io.constellationnetwork.currency.l1.modules.{Queues, Services}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo, CurrencySnapshotStateProof}
import io.constellationnetwork.currency.swap.ConsensusInput.OwnerConsensusInput
import io.constellationnetwork.currency.swap.{ConsensusInput, ConsensusOutput}
import io.constellationnetwork.dag.l1.http.p2p.L0BlockOutputClient
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import io.constellationnetwork.node.shared.domain.consensus.config.SwapConsensusConfig
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockStorage
import io.constellationnetwork.node.shared.domain.swap.consensus.Validator._
import io.constellationnetwork.node.shared.domain.swap.consensus.{ConsensusClient, ConsensusState, Engine}
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendStorage, AllowSpendValidator}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Swap {
  def run[F[_]: Async: Random: Hasher: SecurityProvider](
    swapConsensusCfg: SwapConsensusConfig,
    clusterStorage: ClusterStorage[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    nodeStorage: NodeStorage[F],
    blockOutputClient: L0BlockOutputClient[F],
    consensusClient: ConsensusClient[F],
    services: Services[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, Run],
    allowSpendStorage: AllowSpendStorage[F],
    allowSpendBlockStorage: AllowSpendBlockStorage[F],
    queues: Queues[F],
    allowSpendValidator: AllowSpendValidator[F],
    selfKeyPair: KeyPair,
    selfId: PeerId
  ): Stream[F, Unit] = {

    def logger = Slf4jLogger.getLogger[F]

    def inspectionTrigger: Stream[F, OwnerConsensusInput] =
      Stream
        .awakeEvery(5.seconds)
        .evalFilter(_ => isLastGlobalSnapshotPresent(lastGlobalSnapshot))
        .as(ConsensusInput.InspectionTrigger)

    def ownRoundTrigger: Stream[F, OwnerConsensusInput] =
      Stream
        .awakeEvery(5.seconds)
        .evalFilter { _ =>
          canStartOwnSwapConsensus(
            nodeStorage,
            clusterStorage,
            lastGlobalSnapshot,
            swapConsensusCfg.peersCount,
            allowSpendStorage
          ).handleErrorWith { e =>
            logger.warn(e)("Failure checking if own consensus can be kicked off!").as(false)
          }
        }
        .as(ConsensusInput.OwnRoundTrigger)

    def ownerBlockConsensusInputs =
      inspectionTrigger.merge(ownRoundTrigger)

    def peerBlockConsensusInputs =
      Stream
        .fromQueueUnterminated(queues.swapPeerConsensusInput)
        .evalFilter(isPeerInputValid(_))
        .evalFilter(_ => isLastGlobalSnapshotPresent(lastGlobalSnapshot))
        .map(_.value)

    def blockConsensusInputs =
      ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

    def runConsensus: Pipe[F, ConsensusInput, ConsensusOutput.FinalBlock] =
      _.evalMapAccumulate(ConsensusState.Empty) {
        Engine
          .fsm(
            swapConsensusCfg,
            clusterStorage,
            lastGlobalSnapshot,
            consensusClient,
            allowSpendValidator,
            allowSpendStorage,
            selfId,
            selfKeyPair
          )
          .run
      }.handleErrorWith { e =>
        Stream.eval(logger.error(e)("Error during swap block creation")) >> Stream.empty
      }.flatMap {
        case (_, fb @ ConsensusOutput.FinalBlock(hashedBlock)) =>
          Stream
            .eval(logger.debug(s"Swap block created! Hash=${hashedBlock.hash}, ProofsHash=${hashedBlock.proofsHash}"))
            .as(fb)
        case (_, ConsensusOutput.CleanedConsensuses(ids)) =>
          Stream.eval(logger.debug(s"Cleaned consensuses ids=$ids")) >> Stream.empty
        case (_, ConsensusOutput.Noop) => Stream.empty
      }

    def sendBlockToL0: Pipe[F, ConsensusOutput.FinalBlock, ConsensusOutput.FinalBlock] =
      _.evalTap { fb =>
        l0ClusterStorage.getPeers
          .map(_.toNonEmptyList.toList)
          .flatMap(_.filterA(p => services.collateral.hasCollateral(p.id)))
          .flatMap(peers => Random[F].shuffleList(peers))
          .map(peers => peers.headOption)
          .flatMap { maybeL0Peer =>
            maybeL0Peer.fold(logger.warn("No available L0 peer")) { l0Peer =>
              blockOutputClient
                .sendAllowSpendBlock(fb.hashedBlock.signed)(l0Peer)
                .handleErrorWith(e => logger.error(e)("Error when sending block to L0").as(false))
                .ifM(Applicative[F].unit, logger.warn("Sending block to L0 failed"))
            }
          }
      }

    def gossipBlock: Pipe[F, ConsensusOutput.FinalBlock, ConsensusOutput.FinalBlock] =
      _.evalTap { fb =>
        services.gossip
          .spreadCommon(fb.hashedBlock.signed)
          .handleErrorWith(e => logger.warn(e)("AllowSpendBlock gossip spread failed!"))
      }

    def peerBlocks: Stream[F, ConsensusOutput.FinalBlock] = Stream
      .fromQueueUnterminated(queues.allowSpendBlocks)
      .evalMap(_.toHashedWithSignatureCheck)
      .evalTap {
        case Left(e)  => logger.warn(e)("Received an invalidly signed allow spend block!")
        case Right(_) => Async[F].unit
      }
      .collect {
        case Right(hashedBlock) => ConsensusOutput.FinalBlock(hashedBlock)
      }

    def storeBlock: Pipe[F, ConsensusOutput.FinalBlock, Unit] =
      _.evalMap { fb =>
        allowSpendBlockStorage.store(fb.hashedBlock).handleErrorWith(e => logger.debug(e)("AllowSpendBlock storing failed."))
      }

    def blockAcceptance: Stream[F, Unit] = Stream
      .awakeEvery(1.seconds)
      .evalMap { _ =>
        lastGlobalSnapshot.getOrdinal.flatMap {
          case Some(snapshotOrdinal) =>
            allowSpendBlockStorage.getWaiting.flatTap { awaiting =>
              Applicative[F].whenA(awaiting.nonEmpty) {
                logger.debug(s"Pulled following allow spend blocks for acceptance ${awaiting.keySet}")
              }
            }.flatMap {
              _.toList.traverse {
                case (hash, signedBlock) =>
                  services.allowSpendBlock
                    .accept(signedBlock, snapshotOrdinal)
                    .handleErrorWith(logger.warn(_)(s"Failed acceptance of an allow spend block with ${hash.show}"))
              }
            }.void
          case None => ().pure[F]
        }
      }

    def blockConsensus: Stream[F, Unit] =
      blockConsensusInputs
        .through(runConsensus)
        .through(gossipBlock)
        .through(sendBlockToL0)
        .merge(peerBlocks)
        .through(storeBlock)

    blockConsensus
      .merge(blockAcceptance)

  }
}
