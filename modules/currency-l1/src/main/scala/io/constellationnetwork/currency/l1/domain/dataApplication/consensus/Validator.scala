package io.constellationnetwork.currency.l1.domain.dataApplication.consensus

import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{Applicative, Monad}

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL1Service, ConsensusInput}
import io.constellationnetwork.node.shared.domain.cluster.storage.ClusterStorage
import io.constellationnetwork.node.shared.domain.node.NodeStorage
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.node.NodeState.Ready
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import eu.timepit.refined.types.numeric.PosInt
import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

object Validator {

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  private def isReadyForConsensus(state: NodeState): Boolean = state == Ready

  def isLastGlobalSnapshotPresent[F[_]: Async](
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
  ): F[Boolean] =
    lastGlobalSnapshot.getOrdinal.map(_.isDefined)

  private def enoughPeersForConsensus[F[_]: Monad](
    clusterStorage: ClusterStorage[F],
    peersCount: PosInt
  ): F[Boolean] =
    clusterStorage.getResponsivePeers
      .map(_.filter(p => isReadyForConsensus(p.state)))
      .map(_.size >= peersCount.value)

  def isPeerInputValid[F[_]: Async: SecurityProvider: Hasher](
    input: Signed[ConsensusInput.PeerConsensusInput],
    dataApplicationService: BaseDataApplicationL1Service[F]
  ): F[Boolean] =
    for {
      validSignature <- input.value match {
        case proposal: ConsensusInput.Proposal =>
          implicit val e: Encoder[ConsensusInput.Proposal] = ConsensusInput.Proposal.encoder(dataApplicationService.dataEncoder)
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

  def canStartOwnDataConsensus[F[_]: Async](
    nodeStorage: NodeStorage[F],
    clusterStorage: ClusterStorage[F],
    lastGlobalSnapshot: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    peersCount: PosInt
  ): F[Boolean] =
    for {
      stateReadyForConsensus <- nodeStorage.getNodeState.map(isReadyForConsensus)
      enoughPeers <- enoughPeersForConsensus(clusterStorage, peersCount)
      lastGlobalSnapshotPresent <- isLastGlobalSnapshotPresent(lastGlobalSnapshot)
      res = stateReadyForConsensus && enoughPeers && lastGlobalSnapshotPresent
      _ <-
        Applicative[F].whenA(!res) {
          val reason = Seq(
            if (!stateReadyForConsensus) "State not ready for consensus" else "",
            if (!enoughPeers) "Not enough peers" else "",
            if (!lastGlobalSnapshotPresent) "No global snapshot" else ""
          ).filter(_.nonEmpty).mkString(", ")
          logger.debug(s"Cannot start data own consensus: ${reason}")
        }
    } yield res
}
