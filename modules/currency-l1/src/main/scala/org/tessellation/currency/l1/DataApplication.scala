package org.tessellation.currency.l1

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.currency.dataApplication.ConsensusInput.OwnerConsensusInput
import org.tessellation.currency.dataApplication._
import org.tessellation.currency.l1.domain.dataApplication.consensus.Validator.{
  canStartOwnDataConsensus,
  isLastGlobalSnapshotPresent,
  isPeerInputValid
}
import org.tessellation.currency.l1.domain.dataApplication.consensus.{ConsensusClient, ConsensusState, Engine}
import org.tessellation.currency.l1.modules.{Queues, Services}
import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.domain.consensus.block.config.DataConsensusConfig
import org.tessellation.dag.l1.http.p2p.L0BlockOutputClient
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import org.tessellation.node.shared.domain.node.NodeStorage
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.{Hasher, SecurityProvider}

import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object DataApplication {
  def run[F[_]: Async: Random: Hasher: SecurityProvider: L1NodeContext](
    dataConsensusCfg: DataConsensusConfig,
    clusterStorage: ClusterStorage[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    nodeStorage: NodeStorage[F],
    blockOutputClient: L0BlockOutputClient[F],
    consensusClient: ConsensusClient[F],
    services: Services[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    queues: Queues[F],
    dataApplicationService: BaseDataApplicationL1Service[F],
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
          canStartOwnDataConsensus(
            nodeStorage,
            clusterStorage,
            lastGlobalSnapshot,
            dataConsensusCfg.peersCount
          ).handleErrorWith { e =>
            logger.warn(e)("Failure checking if own consensus can be kicked off!").as(false)
          }
        }
        .as(ConsensusInput.OwnRoundTrigger)

    def ownerBlockConsensusInputs =
      inspectionTrigger.merge(ownRoundTrigger)

    def peerBlockConsensusInputs =
      Stream
        .fromQueueUnterminated(queues.dataApplicationPeerConsensusInput)
        .evalFilter(isPeerInputValid(_, dataApplicationService))
        .evalFilter(_ => isLastGlobalSnapshotPresent(lastGlobalSnapshot))
        .map(_.value)

    def blockConsensusInputs =
      ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

    def runConsensus: Pipe[F, ConsensusInput, ConsensusOutput.FinalBlock] =
      _.evalMapAccumulate(ConsensusState.Empty) {
        Engine
          .fsm(
            dataConsensusCfg,
            dataApplicationService,
            clusterStorage,
            lastGlobalSnapshot,
            consensusClient,
            queues.dataUpdates,
            selfId,
            selfKeyPair
          )
          .run
      }.handleErrorWith { e =>
        Stream.eval(logger.error(e)("Error during data block creation")) >> Stream.empty
      }.flatMap {
        case (_, fb @ ConsensusOutput.FinalBlock(hashedBlock)) =>
          Stream
            .eval(logger.debug(s"Data application block created! Hash=${hashedBlock.hash}, ProofsHash=${hashedBlock.proofsHash}"))
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
                .sendDataApplicationBlock(fb.hashedBlock.signed)(dataApplicationService.dataEncoder)(l0Peer)
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
