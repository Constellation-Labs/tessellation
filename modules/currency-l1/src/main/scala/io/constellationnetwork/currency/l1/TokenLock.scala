package io.constellationnetwork.currency.l1

import java.security.KeyPair

import cats.Applicative
import cats.effect.kernel.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.currency.l1.cli.method.Run
import io.constellationnetwork.currency.l1.domain.dataApplication.consensus.Validator.isLastGlobalSnapshotPresent
import io.constellationnetwork.currency.l1.modules.{Queues, Services}
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo, CurrencySnapshotStateProof}
import io.constellationnetwork.currency.tokenlock.ConsensusInput.OwnerConsensusInput
import io.constellationnetwork.currency.tokenlock.{ConsensusInput, ConsensusOutput}
import io.constellationnetwork.dag.l1.http.p2p.L0BlockOutputClient
import io.constellationnetwork.node.shared.config.types.SharedConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockStorage
import io.constellationnetwork.node.shared.domain.tokenlock.consensus.Validator._
import io.constellationnetwork.node.shared.domain.tokenlock.consensus.config.TokenLockConsensusConfig
import io.constellationnetwork.node.shared.domain.tokenlock.consensus.{ConsensusClient, ConsensusState, Engine}
import io.constellationnetwork.node.shared.domain.tokenlock.{TokenLockStorage, TokenLockValidator}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import fs2.{Pipe, Stream}
import org.typelevel.log4cats.slf4j.Slf4jLogger

object TokenLock {
  def run[F[_]: Async: Random: Hasher: SecurityProvider](
    sharedCfg: SharedConfig,
    tokenLockConsensusConfig: TokenLockConsensusConfig,
    clusterStorage: ClusterStorage[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastNGlobalSnapshot: LastNGlobalSnapshotStorage[F],
    nodeStorage: NodeStorage[F],
    blockOutputClient: L0BlockOutputClient[F],
    consensusClient: ConsensusClient[F],
    services: Services[F, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, Run],
    tokenLockStorage: TokenLockStorage[F],
    tokenLockBlockStorage: TokenLockBlockStorage[F],
    queues: Queues[F],
    tokenLockValidator: TokenLockValidator[F],
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
          canStartOwnTokenLockConsensus(
            sharedCfg.lastGlobalSnapshotsSync,
            nodeStorage,
            clusterStorage,
            lastGlobalSnapshot,
            lastNGlobalSnapshot,
            tokenLockConsensusConfig.peersCount,
            tokenLockStorage
          ).handleErrorWith { e =>
            logger.warn(e)("Failure checking if own consensus can be kicked off!").as(false)
          }
        }
        .as(ConsensusInput.OwnRoundTrigger)

    def ownerBlockConsensusInputs =
      inspectionTrigger.merge(ownRoundTrigger)

    def peerBlockConsensusInputs =
      Stream
        .fromQueueUnterminated(queues.tokenLockPeerConsensusInput)
        .evalFilter(isPeerInputValid(_))
        .evalFilter(_ => isLastGlobalSnapshotPresent(lastGlobalSnapshot))
        .map(_.value)

    def blockConsensusInputs =
      ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

    def runConsensus: Pipe[F, ConsensusInput, ConsensusOutput.FinalBlock] =
      _.evalMapAccumulate(ConsensusState.Empty) {
        Engine
          .fsm(
            tokenLockConsensusConfig,
            clusterStorage,
            lastGlobalSnapshot,
            consensusClient,
            tokenLockValidator,
            tokenLockStorage,
            selfId,
            selfKeyPair
          )
          .run
      }.handleErrorWith { e =>
        Stream.eval(logger.error(e)("Error during token lock block creation")) >> Stream.empty
      }.flatMap {
        case (_, fb @ ConsensusOutput.FinalBlock(hashedBlock)) =>
          Stream
            .eval(logger.debug(s"Token lock block created! Hash=${hashedBlock.hash}, ProofsHash=${hashedBlock.proofsHash}"))
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
                .sendTokenLockBlock(fb.hashedBlock.signed)(l0Peer)
                .handleErrorWith(e => logger.error(e)("Error when sending block to L0").as(false))
                .ifM(Applicative[F].unit, logger.warn("Sending block to L0 failed"))
            }
          }
      }

    def gossipBlock: Pipe[F, ConsensusOutput.FinalBlock, ConsensusOutput.FinalBlock] =
      _.evalTap { fb =>
        services.gossip
          .spreadCommon(fb.hashedBlock.signed)
          .handleErrorWith(e => logger.warn(e)("TokenLockBlock gossip spread failed!"))
      }

    def peerBlocks: Stream[F, ConsensusOutput.FinalBlock] = Stream
      .fromQueueUnterminated(queues.tokenLocksBlocks)
      .evalMap(_.toHashedWithSignatureCheck)
      .evalTap {
        case Left(e)  => logger.warn(e)("Received an invalidly signed token lock block!")
        case Right(_) => Async[F].unit
      }
      .collect {
        case Right(hashedBlock) => ConsensusOutput.FinalBlock(hashedBlock)
      }

    def storeBlock: Pipe[F, ConsensusOutput.FinalBlock, Unit] =
      _.evalMap { fb =>
        tokenLockBlockStorage.store(fb.hashedBlock).handleErrorWith(e => logger.debug(e)("TokenLockBlock storing failed."))
      }

    def blockAcceptance: Stream[F, Unit] = Stream
      .awakeEvery(1.seconds)
      .evalMap { _ =>
        lastGlobalSnapshot.getOrdinal.flatMap {
          case Some(snapshotOrdinal) =>
            tokenLockBlockStorage.getWaiting.flatTap { awaiting =>
              Applicative[F].whenA(awaiting.nonEmpty) {
                logger.debug(s"Pulled following token lock blocks for acceptance ${awaiting.keySet}")
              }
            }.flatMap {
              _.toList.traverse {
                case (hash, signedBlock) =>
                  services.tokenLockBlock
                    .accept(signedBlock, snapshotOrdinal)
                    .handleErrorWith(logger.warn(_)(s"Failed acceptance of an token lock block with ${hash.show}"))
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
