package io.constellationnetwork.dag.l1

import java.security.KeyPair

import cats.Applicative
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.swap.ConsensusInput.OwnerConsensusInput
import io.constellationnetwork.currency.swap.{ConsensusInput, ConsensusOutput}
import io.constellationnetwork.dag.l1.http.p2p.L0BlockOutputClient
import io.constellationnetwork.dag.l1.modules.{Queues, Services}
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import io.constellationnetwork.node.shared.domain.consensus.config.SwapConsensusConfig
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.domain.swap.AllowSpendValidator
import io.constellationnetwork.node.shared.domain.swap.consensus.Validator.{
  canStartOwnSwapConsensus,
  isLastGlobalSnapshotPresent,
  isPeerInputValid
}
import io.constellationnetwork.node.shared.domain.swap.consensus.{ConsensusClient, ConsensusState, Engine}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo, GlobalSnapshotStateProof}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Swap {
  def run[
    F[_]: Async: Hasher: SecurityProvider: Random,
    R <: CliMethod
  ](
    swapConsensusCfg: SwapConsensusConfig,
    clusterStorage: ClusterStorage[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    nodeStorage: NodeStorage[F],
    blockOutputClient: L0BlockOutputClient[F],
    consensusClient: ConsensusClient[F],
    services: Services[F, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo, R],
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
          canStartOwnSwapConsensus(nodeStorage, clusterStorage, lastGlobalSnapshot, swapConsensusCfg.peersCount).handleErrorWith { e =>
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
            queues.allowSpends,
            allowSpendValidator,
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

    blockConsensusInputs
      .through(runConsensus)
      .through(sendBlockToL0)
      .void

  }
}
