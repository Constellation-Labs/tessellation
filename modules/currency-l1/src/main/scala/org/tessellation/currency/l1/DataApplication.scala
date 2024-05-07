package org.tessellation.currency.l1

import java.security.KeyPair

import cats.Applicative
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.currency.dataApplication.ConsensusInput.OwnerConsensusInput
import org.tessellation.currency.dataApplication._
import org.tessellation.currency.l1.domain.dataApplication.consensus.{ConsensusClient, ConsensusState, Engine}
import org.tessellation.currency.l1.modules.{Queues, Services}
import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.http.p2p.L0BlockOutputClient
import org.tessellation.node.shared.domain.cluster.storage.{ClusterStorage, L0ClusterStorage}
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import fs2.{Pipe, Stream}
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

object DataApplication {
  def run[F[_]: Async: Random: Hasher: SecurityProvider: L1NodeContext](
    clusterStorage: ClusterStorage[F],
    l0ClusterStorage: L0ClusterStorage[F],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
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
      Stream.awakeEvery(5.seconds).as(ConsensusInput.InspectionTrigger)

    def ownRoundTrigger: Stream[F, OwnerConsensusInput] =
      Stream
        .awakeEvery(5.seconds)
        .as(ConsensusInput.OwnRoundTrigger)

    def ownerBlockConsensusInputs =
      inspectionTrigger.merge(ownRoundTrigger)

    def isPeerInputValid(input: Signed[ConsensusInput.PeerConsensusInput]): F[Boolean] =
      for {
        validSignature <- input.value match {
          case proposal: ConsensusInput.Proposal =>
            implicit val e = ConsensusInput.Proposal.encoder(dataApplicationService.dataEncoder)
            Signed(proposal, input.proofs).hasValidSignature

          case signatureProposal: ConsensusInput.SignatureProposal =>
            implicit val e: Encoder[ConsensusInput.SignatureProposal] = deriveEncoder
            Signed(signatureProposal, input.proofs).hasValidSignature

          case cancellation: ConsensusInput.CancelledCreationRound =>
            implicit val e: Encoder[ConsensusInput.CancelledCreationRound] = deriveEncoder
            Signed(cancellation, input.proofs).hasValidSignature
        }

        signedExclusively = input.isSignedExclusivelyBy(PeerId._Id.get(input.value.senderId))
      } yield validSignature && signedExclusively

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
          .fsm(
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
