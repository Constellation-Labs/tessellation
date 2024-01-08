package org.tessellation.currency.l1.domain.dataApplication.consensus

import java.security.KeyPair

import cats.Applicative
import cats.data.NonEmptyList
import cats.effect.kernel.{Async, Clock}
import cats.effect.std.{Queue, Random}
import cats.syntax.all._

import scala.concurrent.duration._

import org.tessellation.currency.dataApplication.ConsensusInput._
import org.tessellation.currency.dataApplication.ConsensusOutput.Noop
import org.tessellation.currency.dataApplication._
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.effects.GenUUID
import org.tessellation.fsm.FSM
import org.tessellation.node.shared.domain.cluster.storage.ClusterStorage
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.round.RoundId
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{Hasher, SecurityProvider}

import io.circe.Encoder
import monocle.syntax.all._
import org.typelevel.log4cats.slf4j.Slf4jLogger

import dataApplication.DataApplicationBlock

case class RoundData(
  roundId: RoundId,
  startedAt: FiniteDuration,
  peers: Set[Peer],
  owner: PeerId,
  ownProposal: Proposal,
  ownBlock: Option[Signed[DataApplicationBlock]] = None,
  ownCancellation: Option[DataCancellationReason] = None,
  peerProposals: Map[PeerId, Proposal] = Map.empty[PeerId, Proposal],
  peerBlockSignatures: Map[PeerId, SignatureProof] = Map.empty,
  peerCancellations: Map[PeerId, DataCancellationReason] = Map.empty
) {
  def addPeerProposal(proposal: Proposal): RoundData =
    this.focus(_.peerProposals).modify(_ + (proposal.senderId -> proposal))

  def setOwnBlock(block: Signed[DataApplicationBlock]): RoundData = this.focus(_.ownBlock).replace(block.some)

  def addPeerSignature(signatureProposal: SignatureProposal): RoundData = {
    val proof = SignatureProof(PeerId._Id.get(signatureProposal.senderId), signatureProposal.signature)
    this.focus(_.peerBlockSignatures).modify(_ + (signatureProposal.senderId -> proof))
  }

  def setOwnCancellation(reason: DataCancellationReason): RoundData =
    this.focus(_.ownCancellation).replace(reason.some)

  def addPeerCancellation(cancellation: CancelledCreationRound): RoundData =
    this.focus(_.peerCancellations).modify(_ + (cancellation.senderId -> cancellation.reason))

  def formBlock[F[_]: Async](
    validateUpdate: Signed[DataUpdate] => F[Either[String, Signed[DataUpdate]]],
    constructBlock: RoundId => List[Signed[DataUpdate]] => F[Option[DataApplicationBlock]]
  ): F[Option[DataApplicationBlock]] =
    NonEmptyList
      .fromList((ownProposal.dataUpdates ++ peerProposals.values.flatMap(_.dataUpdates)).toList)
      .traverse {
        _.toList
          .traverse(validateUpdate)
          .flatMap { validatedUpdates =>
            val (_, valid) = validatedUpdates.partitionMap(identity)

            valid.pure[F]
          }
          .flatMap(constructBlock(roundId))
      }
      .map(_.flatten)
}

object Engine {
  type In = ConsensusInput
  type Out = ConsensusOutput
  type State = ConsensusState

  def fsm[F[_]: Async: Random: SecurityProvider: Hasher: L1NodeContext](
    dataApplication: BaseDataApplicationL1Service[F],
    clusterStorage: ClusterStorage[F],
    consensusClient: ConsensusClient[F],
    dataUpdates: Queue[F, Signed[DataUpdate]],
    selfId: PeerId,
    selfKeyPair: KeyPair,
    lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
  ): FSM[F, State, In, Out] = {

    implicit val dataEncoder = dataApplication.dataEncoder

    def peersCount = 2
    def timeout = 10.seconds
    def maxDataUpdatesToDequeue = 50

    def logger = Slf4jLogger.getLogger[F]
    def getTime: F[FiniteDuration] = Clock[F].monotonic
    def mkRoundId = GenUUID.forSync[F].make.map(RoundId(_))

    def isReadyForConsensus(state: NodeState): Boolean = state === NodeState.Ready

    def pullNewConsensusPeers: F[Option[Set[Peer]]] =
      clusterStorage.getResponsivePeers
        .map(_.filter(p => isReadyForConsensus(p.state)))
        .flatMap(peers => Random[F].shuffleList(peers.toList))
        .map(_.take(peersCount).toSet match {
          case peers if peers.size === peersCount => peers.some
          case _                                  => None
        })

    def pullDataUpdates: F[Set[Signed[DataUpdate]]] =
      dataUpdates.tryTakeN(maxDataUpdatesToDequeue.some).map(_.toSet).flatTap { updates =>
        logger.debug(s"Fetched ${updates.size} updates")
      }

    def returnDataUpdates(round: RoundData): F[Unit] =
      round.ownProposal.dataUpdates.toList.traverse(dataUpdates.offer).void

    def removeRound(state: State, round: RoundData): State =
      if (round.owner === selfId)
        state.focus(_.ownConsensus).replace(None)
      else
        state.focus(_.peerConsensuses).modify(_.removed(round.roundId))

    def cancelRound(state: State, round: RoundData): F[(State, Unit)] =
      returnDataUpdates(round).tupleLeft(removeRound(state, round))

    def cancelTimeoutedRounds(state: State, rounds: Set[RoundData]): F[(State, Out)] = {
      val newState = rounds.toList.foldLeft(state)(removeRound(_, _))

      rounds.toList
        .traverse(returnDataUpdates)
        .as[Out](ConsensusOutput.CleanedConsensuses(rounds.map(_.roundId)))
        .tupleLeft(newState)
    }

    def inspectConsensuses(state: State): F[(State, Out)] = {
      val own = state.ownConsensus.toSet
      val peer = state.peerConsensuses.values.toSet

      getTime.flatMap { current =>
        val toCancel = (peer ++ own)
          .filter(_.startedAt + timeout < current)

        if (toCancel.nonEmpty) cancelTimeoutedRounds(state, toCancel) else (state, Noop.asInstanceOf[Out]).pure[F]
      }
    }

    def deriveConsensusPeerIds(proposal: Proposal): Set[PeerId] =
      proposal.facilitators + proposal.senderId + proposal.owner - selfId

    def fetchConsensusPeers(proposal: Proposal): F[Option[Set[Peer]]] =
      clusterStorage.getResponsivePeers.map { knownPeers =>
        val peerIds = deriveConsensusPeerIds(proposal)
        val peers = peerIds.map(id => id -> knownPeers.find(_.id === id)).collect { case (id, Some(peer)) => id -> peer }.toMap

        if (peers.keySet === peerIds && peerIds.size == peersCount) peers.values.toSet.some else none[Set[Peer]]
      }

    def canPersistProposal(proposal: Proposal)(roundData: RoundData): Boolean = {
      val sameRoundId = roundData.roundId === proposal.roundId
      lazy val peerExists = roundData.peers.exists(_.id === proposal.senderId)
      lazy val noProposalYet = !roundData.peerProposals.contains(proposal.senderId)

      sameRoundId && peerExists && noProposalYet
    }

    def canPersistSignatureProposal(proposal: SignatureProposal)(roundData: RoundData): Boolean = {
      val sameRoundId = roundData.roundId === proposal.roundId
      lazy val peerExists = roundData.peers.exists(_.id === proposal.senderId)
      lazy val noProposalYet = !roundData.peerBlockSignatures.contains(proposal.senderId)

      sameRoundId && peerExists && noProposalYet
    }

    def canPersistCancellation(cancellation: CancelledCreationRound)(roundData: RoundData): Boolean = {
      val sameRoundId = roundData.roundId === cancellation.roundId
      lazy val peerExists = roundData.peers.exists(_.id === cancellation.senderId)
      lazy val noCancellationYet = !roundData.peerCancellations.contains(cancellation.senderId)
      lazy val ownCancellation = cancellation.senderId === selfId && roundData.ownCancellation.isEmpty

      sameRoundId && ((peerExists && noCancellationYet) || ownCancellation)
    }

    def canFinalizeRoundCancellation(roundData: RoundData): Boolean = {
      val ownCancellation = roundData.ownCancellation.isDefined
      lazy val peerCancellations = roundData.peers.map(_.id) === roundData.peerCancellations.keySet

      ownCancellation && peerCancellations
    }

    def gotAllProposals(roundData: RoundData): Boolean =
      roundData.peers.map(_.id) === roundData.peerProposals.keySet

    def gotAllSignatures(roundData: RoundData): Boolean =
      roundData.peers.map(_.id) === roundData.peerBlockSignatures.keySet

    def tryPersistProposal(state: State, proposal: Proposal): Option[(State, RoundData)] = {
      def getRoundData(state: State, proposal: Proposal) =
        if (proposal.owner === selfId) state.ownConsensus else state.peerConsensuses.get(proposal.roundId)

      getRoundData(state, proposal)
        .filter(canPersistProposal(proposal))
        .map { roundData =>
          if (proposal.owner === selfId) state.focus(_.ownConsensus).modify(_.map(_.addPeerProposal(proposal)))
          else state.focus(_.peerConsensuses).modify(_.updated(roundData.roundId, roundData.addPeerProposal(proposal)))
        }
        .flatMap { newState =>
          getRoundData(newState, proposal).tupleLeft(newState)
        }
    }

    def tryPersistSignatureProposal(state: State, proposal: SignatureProposal): Option[(State, RoundData)] = {
      def getRoundData(state: State, proposal: SignatureProposal) =
        if (proposal.owner === selfId) state.ownConsensus else state.peerConsensuses.get(proposal.roundId)

      getRoundData(state, proposal)
        .filter(canPersistSignatureProposal(proposal))
        .map { roundData =>
          if (proposal.owner === selfId) state.focus(_.ownConsensus).modify(_.map(_.addPeerSignature(proposal)))
          else state.focus(_.peerConsensuses).modify(_.updated(roundData.roundId, roundData.addPeerSignature(proposal)))
        }
        .flatMap { newState =>
          getRoundData(newState, proposal).tupleLeft(newState)
        }
    }

    def tryPersistCancellation(
      state: State,
      cancellation: CancelledCreationRound
    ): Option[(State, Set[Peer], Option[CancelledCreationRound])] = {
      def getRoundData(state: State, cancellation: CancelledCreationRound) =
        if (cancellation.owner === selfId) state.ownConsensus else state.peerConsensuses.get(cancellation.roundId)

      getRoundData(state, cancellation)
        .filter(canPersistCancellation(cancellation))
        .map { roundData =>
          def ownCancellation = CancelledCreationRound(roundData.roundId, selfId, roundData.owner, DataCancellationReason.PeerCancelled)

          def modified: RoundData = (roundData, cancellation.senderId) match {
            case (rd, `selfId`) if rd.ownCancellation.isDefined => rd
            case (rd, `selfId`) if rd.ownCancellation.isEmpty   => rd.setOwnCancellation(cancellation.reason)
            case (rd, _) if rd.ownCancellation.isDefined        => rd.addPeerCancellation(cancellation)
            case (rd, _) if rd.ownCancellation.isEmpty => rd.setOwnCancellation(ownCancellation.reason).addPeerCancellation(cancellation)
            case (_, _)                                => roundData
          }

          val newState =
            if (cancellation.owner === selfId)
              state.focus(_.ownConsensus).modify(_.map(_ => modified))
            else
              state.focus(_.peerConsensuses).modify(_.updated(roundData.roundId, modified))

          (newState, roundData.peers, Option.when(cancellation.senderId =!= selfId && roundData.ownCancellation.isEmpty)(ownCancellation))
        }
    }

    def broadcast[A <: PeerConsensusInput](data: Signed[A], peers: Set[Peer])(implicit e: Encoder[A]): F[Unit] =
      peers.toList
        .traverse(consensusClient.sendConsensusData(data)(e)(_))
        .void

    def sendBlockProposal(signedBlock: Signed[DataApplicationBlock], roundData: RoundData): F[Unit] = {
      val proposal = SignatureProposal(
        roundData.roundId,
        selfId,
        roundData.owner,
        signedBlock.proofs.head.signature
      )
      val signedProposal = Signed.forAsyncKryo(proposal, selfKeyPair)

      signedProposal.flatMap {
        broadcast(_, roundData.peers)
      }
    }

    def processBlock(state: State, proposal: Proposal, signedBlock: Signed[DataApplicationBlock]): F[(State, Unit)] = {
      val maybeRoundData = if (proposal.owner === selfId) state.ownConsensus else state.peerConsensuses.get(proposal.roundId)

      val newState = maybeRoundData.fproductLeft { roundData =>
        if (proposal.owner === selfId)
          state.focus(_.ownConsensus).modify(_.map(_.setOwnBlock(signedBlock)))
        else
          state.focus(_.peerConsensuses).modify(_.updated(roundData.roundId, roundData.setOwnBlock(signedBlock)))
      }

      newState.traverse {
        case (s, rd) =>
          sendBlockProposal(signedBlock, rd).tupleLeft(s)
      }.map(_.getOrElse((state, ())))
    }

    def processCancellation(state: State, cancellation: CancelledCreationRound): F[(State, Unit)] =
      tryPersistCancellation(state, cancellation).traverse {
        case (newState, peers, Some(ownCancellation)) =>
          Signed.forAsyncKryo[F, CancelledCreationRound](ownCancellation, selfKeyPair).flatMap(broadcast(_, peers)).tupleLeft(newState)
        case (newState, _, _) =>
          ().pure[F].tupleLeft(newState)
      }.flatMap {
        _.fold {
          none[(State, Unit)].pure[F]
        } {
          case (newState, _) =>
            (if (cancellation.owner === selfId) newState.ownConsensus else newState.peerConsensuses.get(cancellation.roundId)).traverse {
              roundData =>
                if (canFinalizeRoundCancellation(roundData)) cancelRound(newState, roundData)
                else ().pure[F].tupleLeft(newState)
            }
        }
      }
        .map(_.getOrElse((state, ())))

    def persistProposal(state: State, proposal: Proposal): F[(State, Unit)] =
      tryPersistProposal(state, proposal).map {
        case (newState, roundData) if gotAllProposals(roundData) =>
          def combine(roundId: RoundId)(updates: List[Signed[DataUpdate]]): F[Option[DataApplicationBlock]] =
            NonEmptyList.fromList(updates).traverse { u =>
              u.traverse(_.toHashed(dataApplication.serializeUpdate).map(_.hash)).map { hashes =>
                DataApplicationBlock(roundId, u, hashes)
              }
            }

          roundData
            .formBlock(
              a => dataApplication.validateUpdate(a).map(_.toEither.leftMap(_.toString).map(_ => a)),
              combine
            )
            .flatMap {
              case Some(block) =>
                Signed.forAsyncKryo(block, selfKeyPair).flatMap { signedBlock =>
                  signedBlock.updates
                    .traverse(dataApplication.validateUpdate(_))
                    .map(_.forall(_.isValid))
                    .ifM(
                      processBlock(newState, proposal, signedBlock), {
                        val cancellation =
                          CancelledCreationRound(
                            roundData.roundId,
                            senderId = selfId,
                            owner = roundData.owner,
                            DataCancellationReason.CreatedInvalidBlock
                          )
                        processCancellation(newState, cancellation)
                      }
                    )
                }
              case None =>
                val cancellation =
                  CancelledCreationRound(
                    roundData.roundId,
                    senderId = selfId,
                    owner = roundData.owner,
                    DataCancellationReason.CreatedEmptyBlock
                  )
                processCancellation(newState, cancellation)
            }

        case (newState, _) => ().pure[F].tupleLeft(newState)
      }.getOrElse(logger.warn(s"Couldn't persist proposal").tupleLeft(state))

    def informAboutInabilityToParticipate(proposal: Proposal, reason: DataCancellationReason): F[Unit] = {
      def cancellation = CancelledCreationRound(
        proposal.roundId,
        senderId = selfId,
        owner = proposal.owner,
        reason
      )

      def cancellationMsg =
        Signed.forAsyncKryo[F, CancelledCreationRound](cancellation, selfKeyPair)

      def peersToInform = clusterStorage.getResponsivePeers
        .map(_.filter(peer => deriveConsensusPeerIds(proposal).contains(peer.id)))

      (cancellationMsg, peersToInform).flatMapN(broadcast(_, _))
    }

    def sendOwnProposal(ownProposal: Proposal, peers: Set[Peer]): F[Unit] = {
      implicit val e = Proposal.encoder(dataApplication.dataEncoder)

      Signed
        .forAsyncKryo[F, Proposal](ownProposal, selfKeyPair)
        .flatMap(broadcast(_, peers))
        .handleErrorWith(e => logger.error(e)(s"Error sending own proposal") >> e.raiseError[F, Unit])
    }

    def processProposal(state: State, proposal: Proposal): F[(State, Unit)] =
      fetchConsensusPeers(proposal).flatMap { maybePeers =>
        val maybeRoundData = state.ownConsensus.filter(_.roundId === proposal.roundId).orElse(state.peerConsensuses.get(proposal.roundId))

        (maybeRoundData, maybePeers) match {
          case (Some(_), _) => persistProposal(state, proposal)
          case (None, _) if proposal.owner === selfId =>
            informAboutInabilityToParticipate(proposal, DataCancellationReason.ReceivedProposalForNonExistentOwnRound).tupleLeft(state)
          case (None, Some(peers)) =>
            getTime.flatMap { startedAt =>
              pullDataUpdates.flatMap { data =>
                data.toList.traverse(_.toHashed(dataApplication.serializeUpdate)).map(_.map(_.hash)).map(_.toSet).flatMap { dataHashes =>
                  val ownProposal = Proposal(proposal.roundId, senderId = selfId, owner = proposal.owner, peers.map(_.id), data, dataHashes)
                  val roundData =
                    RoundData(proposal.roundId, startedAt, peers, owner = proposal.owner, ownProposal)

                  sendOwnProposal(ownProposal, peers)
                    .tupleLeft(
                      state.focus(_.peerConsensuses).modify(_.updated(roundData.roundId, roundData))
                    )
                    .map(_._1)
                    .flatMap(persistProposal(_, proposal))
                }
              }

            }
          case (None, None) => informAboutInabilityToParticipate(proposal, DataCancellationReason.MissingRoundPeers).tupleLeft(state)
        }
      }

    def processSignatureProposal(state: State, proposal: SignatureProposal): F[(State, Out)] =
      tryPersistSignatureProposal(state, proposal).traverse {
        case (newState, roundData) =>
          roundData match {
            case roundData @ RoundData(_, _, _, _, _, Some(ownBlock), _, _, _, _) if gotAllSignatures(roundData) =>
              val block = roundData.peerBlockSignatures.values.foldLeft(ownBlock) {
                case (agg, proof) => agg.addProof(proof)
              }

              block.toHashedWithSignatureCheck.flatMap {
                case Left(_) =>
                  cancelRound(newState, roundData).map { case (s, _) => (s, Noop.asInstanceOf[Out]) }
                case Right(hashedBlock) =>
                  Applicative[F]
                    .pure[ConsensusOutput](ConsensusOutput.FinalBlock(hashedBlock))
                    .tupleLeft(removeRound(newState, roundData))
              }
            case _ =>
              Applicative[F].pure[ConsensusOutput](Noop).tupleLeft(newState)
          }
      }.map(_.getOrElse((state, Noop)))

    def startOwnRound(state: State): F[(State, Unit)] =
      state.ownConsensus.fold {
        pullNewConsensusPeers.flatMap {
          case Some(peers) =>
            (mkRoundId, pullDataUpdates, getTime).mapN {
              case (roundId, dataUpdates, startedAt) =>
                dataUpdates.toList.traverse(_.toHashed(dataApplication.serializeUpdate)).map(_.map(_.hash)).map(_.toSet).flatMap {
                  dataHashes =>
                    val proposal = Proposal(roundId, senderId = selfId, owner = selfId, peers.map(_.id), dataUpdates, dataHashes)
                    val roundData = RoundData(roundId, startedAt, peers, selfId, proposal)

                    val newState = state.focus(_.ownConsensus).replace(roundData.some)

                    sendOwnProposal(proposal, peers).tupleLeft(newState)
                }
            }.flatten
          case _ => logger.warn(s"Missing peers").tupleLeft(state)
        }
      } { _ =>
        (state, ()).pure[F]
      }

    implicit class AsNoopOps(f: F[(State, Unit)]) {
      def asNoop: F[(State, Out)] = f.map { case (state, _) => (state, Noop) }
    }

    FSM {
      case (st, ConsensusInput.OwnRoundTrigger)                      => startOwnRound(st).asNoop
      case (st, ConsensusInput.InspectionTrigger)                    => inspectConsensuses(st)
      case (st, proposal: ConsensusInput.Proposal)                   => processProposal(st, proposal).asNoop
      case (st, signatureProposal: ConsensusInput.SignatureProposal) => processSignatureProposal(st, signatureProposal)
      case (st, _)                                                   => (st, ()).pure[F].asNoop
    }
  }
}
