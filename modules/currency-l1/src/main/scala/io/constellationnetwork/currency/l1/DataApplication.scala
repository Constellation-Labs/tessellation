package io.constellationnetwork.currency.l1

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication.ConsensusInput.OwnerConsensusInput
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.l1.cli.method.Run
import io.constellationnetwork.currency.l1.domain.dataApplication.consensus.Validator._
import io.constellationnetwork.currency.l1.domain.dataApplication.consensus.{ConsensusClient, ConsensusState, Engine}
import io.constellationnetwork.currency.l1.modules.{Queues, Services}
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.dag.l1.domain.consensus.block.config.DataConsensusConfig
import io.constellationnetwork.dag.l1.http.p2p.L0BlockOutputClient
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import fs2.{Pipe, Stream}
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

object DataApplication {
  def run[F[_]: Async: Random: Hasher: JsonSerializer: SecurityProvider: L1NodeContext](
    dataConsensusCfg: DataConsensusConfig,
    clusterStorage: ClusterStorage[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastCurrencySnapshot: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    nodeStorage: NodeStorage[F],
    blockOutputClient: L0BlockOutputClient[F],
    consensusClient: ConsensusClient[F],
    services: Services[
      F,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo,
      Run
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
        .evalFilter { _ =>
          for {
            lastGlobalSnapshotPresent <- isLastGlobalSnapshotPresent(lastGlobalSnapshot)
            lastCurrencySnapshotPresent <- isLastCurrencySnapshotPresent(lastCurrencySnapshot)
          } yield lastGlobalSnapshotPresent && lastCurrencySnapshotPresent
        }
        .as(ConsensusInput.InspectionTrigger)

    def ownRoundTrigger: Stream[F, OwnerConsensusInput] =
      Stream
        .awakeEvery(5.seconds)
        .evalFilter { _ =>
          canStartOwnDataConsensus(
            nodeStorage,
            clusterStorage,
            lastGlobalSnapshot,
            lastCurrencySnapshot,
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
        .evalFilter { _ =>
          for {
            lastGlobalSnapshotPresent <- isLastGlobalSnapshotPresent(lastGlobalSnapshot)
            lastCurrencySnapshotPresent <- isLastCurrencySnapshotPresent(lastCurrencySnapshot)
          } yield lastGlobalSnapshotPresent && lastCurrencySnapshotPresent
        }
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
            queues.dataTransactions,
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
            implicit val dataUpdateEncoder: Encoder[DataUpdate] = dataApplicationService.dataEncoder
            maybeL0Peer.fold(logger.warn("No available L0 peer")) { l0Peer =>
              blockOutputClient
                .sendDataApplicationBlock(fb.hashedBlock.signed)
                .run(l0Peer)
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
