package org.tessellation.currency.l1

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.currency.BaseDataApplicationL1Service
import org.tessellation.currency.l1.modules.{Queues, Services}
import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.domain.dataApplication.consensus.ConsensusInput.OwnerConsensusInput
import org.tessellation.dag.l1.domain.dataApplication.consensus._
import org.tessellation.dag.l1.http.p2p.L0CurrencyClusterClient
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.programs.L0PeerDiscovery
import org.tessellation.sdk.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import fs2._
import org.typelevel.log4cats.slf4j.Slf4jLogger

object DataApplication {
  def run[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    clusterStorage: ClusterStorage[F],
    l0ClusterStorage: L0ClusterStorage[F],
    // lastSnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo],
    currencyClusterClient: L0CurrencyClusterClient[F, CurrencyBlock],
    consensusClient: ConsensusClient[F],
    l0PeerDiscoveryProgram: L0PeerDiscovery[F],
    services: Services[
      F,
      CurrencyTransaction,
      CurrencyBlock,
      CurrencySnapshotStateProof,
      CurrencyIncrementalSnapshot,
      CurrencySnapshotInfo
    ],
    queues: Queues[F, CurrencyTransaction, CurrencyBlock],
    dataApplicationService: BaseDataApplicationL1Service[F],
    selfKeyPair: KeyPair,
    selfId: PeerId
  ): Stream[F, Unit] = {

    def logger = Slf4jLogger.getLogger[F]

    def inspectionTrigger: Stream[F, OwnerConsensusInput] =
      Stream.awakeEvery(5.seconds).as(ConsensusInput.InspectionTrigger)

    def ownRoundTrigger: Stream[F, OwnerConsensusInput] =
      Stream
        .awakeEvery(5.seconds)
        .as(ConsensusInput.OwnRoundTrigger)

    def ownerBlockConsensusInputs =
      inspectionTrigger.merge(ownRoundTrigger)

    def isPeerInputValid(input: Signed[ConsensusInput.PeerConsensusInput]): F[Boolean] =
      input.hasValidSignature.map {
        _ && input.isSignedExclusivelyBy(PeerId._Id.get(input.value.senderId))
      }

    def peerBlockConsensusInputs =
      Stream
        .fromQueueUnterminated(queues.dataApplicationPeerConsensusInput)
        .evalFilter(isPeerInputValid(_))
        .map(_.value)

    def blockConsensusInputs =
      ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

    def runConsensus: Pipe[F, ConsensusInput, ConsensusOutput.FinalBlock] =
      _.evalMapAccumulate(ConsensusState.Empty) {
        Engine
          .fsm(dataApplicationService, clusterStorage, consensusClient, queues.dataUpdates, selfId, selfKeyPair)
          .run
      }.flatMap {
        case (_, fb @ ConsensusOutput.FinalBlock(hashedBlock)) =>
          Stream
            .eval(logger.debug(s"Data application block created! Hash=${hashedBlock.hash}, ProofsHash=${hashedBlock.proofsHash}"))
            .as(fb)
        case (_, ConsensusOutput.CleanedConsensuses(ids)) =>
          Stream.eval(logger.debug(s"Cleaned consensuses ids=${ids}")) >> Stream.empty
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
              currencyClusterClient
                .sendDataApplicationBlock(fb.hashedBlock.signed)(dataApplicationService.dataEncoder)(l0Peer)
                .ifM(Applicative[F].unit, logger.warn("Sending block to L0 failed"))
            }
          }
      }

    // TODO
    def l0PeerDiscovery: Stream[F, Unit] = Stream
      .awakeEvery(10.seconds)
      .as(())
    // .evalMap { _ =>
    // lastSnapshotStorage.get.flatMap {
    // _.fold(Applicative[F].unit) { latestSnapshot =>
    // l0PeerDiscoveryProgram.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
    // }
    // }
    // }

    def sample = Stream
      .awakeEvery(5.seconds)
      .evalMap(_ => dataApplicationService.sample)
      .evalMap(queues.dataUpdates.offer)

    blockConsensusInputs
      .through(runConsensus)
      .through(sendBlockToL0)
      .merge(l0PeerDiscovery)
      .merge(sample)
      .void
  }

}
