package org.tessellation.dag.l1

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option.{none, _}
import cats.syntax.semigroup._
import cats.syntax.traverse._

import org.tessellation.dag.l1.storage.BlockStorage.PulledTips
import org.tessellation.dag.l1.storage.RoundData
import org.tessellation.effects.GenUUID
import org.tessellation.http.p2p.PeerResponse
import org.tessellation.kernel.Cell.NullTerminal
import org.tessellation.kernel._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.node.NodeState.Ready
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed._
import org.tessellation.security.{Hashed, SecurityProvider}

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.http4s.Method.POST
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

import DAGStateChannel.RoundId

// TODO: cleaning mechanism needed for cases where IO fails and can't recover.
//  A) Timed based eviction
//  B) Or maybe base it on majority snapshot and cleanup then? Will take longer,
//  but many cleanup processes will depend on majority maybe this could also.
//  Is worst case scenario all block creation rounds stuck and majority not progressing???
class L1DAGBlockCell[F[_]: Async: SecurityProvider: KryoSerializer: Random](
  data: L1DAGBlockData,
  context: DAGStateChannelContext[F]
) extends Cell[F, StackF, L1DAGBlockData, Either[CellError, Ω], L1DAGBlockCoalgebraCommand](
      data, {
        implicit val logger = Slf4jLogger.getLogger[F]

        val returnTransactions: Proposal => F[Unit] = ownProposal =>
          context.transactionStorage
            .putTransactions(ownProposal.transactions)

        val cleanUpRoundData: Proposal => F[Unit] = ownProposal => {
          def clean: Option[RoundData] => (Option[RoundData], Unit) = {
            case Some(roundData: RoundData) if roundData.roundId == ownProposal.roundId => (none[RoundData], ())
            case current                                                                => (current, ())
          }

          (ownProposal.owner == context.myId)
            .pure[F]
            .ifM(
              context.consensusStorage.ownConsensus.modify(clean),
              context.consensusStorage.peerConsensuses(ownProposal.roundId).modify(clean)
            )
        }

        // TODO: handle traverse errors? Should we cancel the round after failed broadcast?
        val broadcast: (Signed[L1PeerDAGBlockData], Set[Peer]) => F[Unit] = (toSend, peers) =>
          peers.toList
            .traverse(
              peer =>
                PeerResponse[F, Unit]("consensus/data", POST)(context.client) { (req, c) =>
                  c.successful(req.withEntity(toSend)).void
                }(peer)
            )
            .void

        val processCancellation: CancelledBlockCreationRound => F[Either[CellError, Ω]] = cancellation => {
          def persistCancellationMessage
            : Option[RoundData] => (Option[RoundData], Option[(RoundData, Option[CancelledBlockCreationRound])]) = {
            case Some(roundData)
                if roundData.roundId == cancellation.roundId && (roundData.peers
                  .map(_.id)
                  .contains(cancellation.senderId) || cancellation.senderId == context.myId) =>
              (roundData, cancellation.senderId) match {
                case (rd @ RoundData(_, _, _, _, _, Some(_), _, _, _, _), context.myId) => (rd.some, None)
                case (rd @ RoundData(_, _, _, _, _, None, _, _, _, _), context.myId) =>
                  val updated = rd.copy(ownCancellation = cancellation.reason.some)
                  (updated.some, (updated, cancellation.some).some)
                case (rd @ RoundData(_, _, _, _, _, Some(_), _, _, peerCancellations, _), _) =>
                  val updated =
                    rd.copy(peerCancellations = peerCancellations + (cancellation.senderId -> cancellation.reason))
                  (updated.some, (updated, None).some)
                case (rd @ RoundData(_, _, _, _, _, None, _, _, peerCancellations, _), _) =>
                  val myCancellation = CancelledBlockCreationRound(rd.roundId, context.myId, rd.owner, PeerCancelled)
                  val updated = rd.copy(
                    ownCancellation = myCancellation.reason.some,
                    peerCancellations = peerCancellations + (cancellation.senderId -> cancellation.reason)
                  )
                  (updated.some, (updated, myCancellation.some).some)
              }
            case other => (other, None)
          }

          for {
            maybeUpdate <- (cancellation.owner == context.myId)
              .pure[F]
              .ifM(
                context.consensusStorage.ownConsensus.modify(persistCancellationMessage),
                context.consensusStorage.peerConsensuses(cancellation.roundId).modify(persistCancellationMessage)
              )
            _ <- maybeUpdate match {
              case Some((roundData, Some(myCancellation))) =>
                for {
                  signedCancellationMessage <- Signed
                    .forAsyncKryo[F, L1PeerDAGBlockData](myCancellation, context.keyPair)
                  _ <- broadcast(signedCancellationMessage, roundData.peers)
                } yield ()
              case _ => Async[F].unit
            }
            result <- maybeUpdate match {
              case Some((roundData, _))
                  if roundData.ownCancellation.nonEmpty && roundData.peers
                    .map(_.id) == roundData.peerCancellations.keySet =>
                for {
                  _ <- returnTransactions(roundData.ownProposal)
                  _ <- cleanUpRoundData(roundData.ownProposal)
                } yield CellError("Round cancelled after all peers agreed to cancel the round!").asLeft[Ω]
              case Some((_, Some(myCancellation))) =>
                CellError(
                  s"Round is being cancelled! Own round cancellation request got processed. Reason is ${myCancellation.reason}"
                ).asLeft[Ω].pure[F]
              case Some(_) =>
                CellError(s"Round is being cancelled! Round cancellation request got processed.").asLeft[Ω].pure[F]
              case None => NullTerminal.asRight[CellError].widen[Ω].pure[F]
            }
          } yield result
        }

        scheme.hyloM(
          AlgebraM[F, StackF, Either[CellError, Ω]] {
            case More(a) => a.pure[F]
            case Done(Right(cmd: L1DAGBlockAlgebraCommand)) =>
              cmd match {
                //TODO: should we check if at this moment all the proposals are present (for the case of single facilitator consensus e.g.)
                case PersistInitialRoundData(roundData) =>
                  def persistRoundData: Option[RoundData] => (Option[RoundData], Option[RoundData]) = {
                    case existing @ Some(_) => (existing, none[RoundData])
                    case None               => (roundData.some, roundData.some)
                  }
                  def handleUpdateResult(errorMessage: String): Option[RoundData] => F[Either[CellError, Ω]] = {
                    case Some(roundData) =>
                      for {
                        signedProposal <- Signed
                          .forAsyncKryo[F, L1PeerDAGBlockData](roundData.ownProposal, context.keyPair)
                        // TODO: when broadcast failed the own consensus round got stuck and wasn't cleaned.
                        //  Should we automatically clean up after the failed broadcast (which may not work - I mean sending cancellation)
                        //  or should it be handled by consensus health check mechanism(that's not here yet)?
                        _ <- broadcast(signedProposal, roundData.peers)
                      } yield NullTerminal.asRight[CellError].widen[Ω]
                    case None =>
                      returnTransactions(roundData.ownProposal)
                        .map(_ => CellError(errorMessage).asLeft[Ω])
                  }
                  (roundData.owner == context.myId)
                    .pure[F]
                    .flatTap(
                      _ =>
                        logger.debug(
                          s"Starting ${if (roundData.owner == context.myId) "OWN" else "PEER"} round with roundId=${roundData.roundId}"
                        )
                    )
                    .ifM(
                      context.consensusStorage.ownConsensus
                        .modify(persistRoundData)
                        .flatMap(handleUpdateResult("Another own round already in progress!")),
                      context.consensusStorage
                        .peerConsensuses(roundData.roundId)
                        .modify(persistRoundData)
                        .flatMap(handleUpdateResult("Round data for given round id already persisted!"))
                    )
                case PersistProposal(proposal) =>
                  def persistProposal: Option[RoundData] => (Option[RoundData], Option[RoundData]) = {
                    case Some(roundData)
                        if roundData.roundId == proposal.roundId && roundData.peers
                          .exists(_.id == proposal.senderId) && !roundData.peerProposals
                          .contains(proposal.senderId) =>
                      val updated =
                        roundData.copy(peerProposals = roundData.peerProposals + (proposal.senderId -> proposal))
                      (updated.some, updated.some)
                    case other => (other, None)
                  }

                  for {
                    maybeRoundData <- (proposal.owner == context.myId)
                      .pure[F]
                      .ifM(
                        context.consensusStorage.ownConsensus.modify(persistProposal),
                        context.consensusStorage.peerConsensuses(proposal.roundId).modify(persistProposal)
                      )
                    result <- maybeRoundData match {
                      case Some(roundData) if roundData.peers.map(_.id) == roundData.peerProposals.keySet =>
                        def persistOwnBlock(
                          signedBlock: Signed[DAGBlock]
                        ): Option[RoundData] => (Option[RoundData], Option[RoundData]) = {
                          case Some(roundData) if roundData.ownBlock.isEmpty && roundData.roundId == proposal.roundId =>
                            val updated = roundData.copy(ownBlock = signedBlock.some)
                            (updated.some, updated.some)
                          case other => (other, None)
                        }
                        for {
                          _ <- ().pure[F]
                          // TODO: Tips correctness:
                          //  A) Check tips beforehand???
                          //  B) Have a tips proposal phase where we will determine parents accepted on every facilitator.
                          //  C) We can as well not check parents acceptance during block creation and assume the parents will eventually get accepted.
                          block = DAGBlock(
                            roundData.ownProposal.transactions ++ roundData.peerProposals.values
                              .flatMap(_.transactions)
                              .toSet,
                            roundData.tips.value
                          )
                          result <- context.blockValidator
                            .validateBlock(block)
                            // TODO: if would like to not check if parents are accepted during block creation round
                            //.areTransactionsValid(block.transactions.toSeq)
                            .flatTap(isValid => logger.debug(s"Created block isValid = $isValid"))
                            .ifM(
                              for {
                                _ <- Async[F].unit
                                signedBlock <- Signed.forAsyncKryo(block, context.keyPair)
                                updateResult <- (proposal.owner == context.myId)
                                  .pure[F]
                                  .ifM(
                                    context.consensusStorage.ownConsensus.modify(persistOwnBlock(signedBlock)),
                                    context.consensusStorage
                                      .peerConsensuses(proposal.roundId)
                                      .modify(persistOwnBlock(signedBlock))
                                  )
                                result <- updateResult match {
                                  case Some(roundData) =>
                                    for {
                                      _ <- Async[F].unit
                                      blockProposal = BlockProposal(
                                        roundData.roundId,
                                        context.myId,
                                        roundData.owner,
                                        signedBlock
                                      )
                                      signedBlockProposal <- Signed
                                        .forAsyncKryo[F, L1PeerDAGBlockData](blockProposal, context.keyPair)
                                      _ <- broadcast(signedBlockProposal, roundData.peers)
                                    } yield NullTerminal.asRight[CellError].widen[Ω]
                                  case None =>
                                    //TODO: ??? should I cleanup, is this case a failure??? I guess not, the block could be there already and it's not a failure.
                                    CellError("Tried to persist own signed block but the update failed!")
                                      .asLeft[Ω]
                                      .pure[F]
                                }

                              } yield result,
                              for {
                                _ <- Async[F].unit
                                cancellationMessage = CancelledBlockCreationRound(
                                  roundData.roundId,
                                  senderId = context.myId,
                                  owner = roundData.owner,
                                  CreatedBlockInvalid
                                )
                                result <- processCancellation(cancellationMessage)
                              } yield result
                            )
                        } yield result
                      case _ => NullTerminal.asRight[CellError].widen[Ω].pure[F]
                    }
                  } yield result
                case PersistBlockProposal(blockProposal) =>
                  def persistBlockProposal: Option[RoundData] => (Option[RoundData], Option[RoundData]) = {
                    case Some(roundData)
                        if roundData.roundId == blockProposal.roundId && roundData.peers
                          .exists(_.id == blockProposal.senderId) && !roundData.peerBlocks
                          .contains(blockProposal.senderId) =>
                      val updated =
                        roundData.copy(
                          peerBlocks = roundData.peerBlocks + (blockProposal.senderId -> blockProposal.signedBlock)
                        )
                      (updated.some, updated.some)
                    case other => (other, None)
                  }
                  for {
                    maybeRoundData <- (blockProposal.owner == context.myId)
                      .pure[F]
                      .ifM(
                        context.consensusStorage.ownConsensus.modify(persistBlockProposal),
                        context.consensusStorage.peerConsensuses(blockProposal.roundId).modify(persistBlockProposal)
                      )
                    result <- maybeRoundData match {
                      case Some(roundData @ RoundData(_, peers, _, _, Some(ownBlock), _, _, peerBlocks, _, _))
                          if peers.map(_.id) == peerBlocks.keySet =>
                        for {
                          _ <- ().pure[F]
                          finalBlock = peerBlocks.values.fold(ownBlock)(_ |+| _)
                          result <- finalBlock.hashWithSignatureCheck.flatMap {
                            case Left(_) =>
                              processCancellation(
                                CancelledBlockCreationRound(
                                  roundData.roundId,
                                  senderId = context.myId,
                                  owner = roundData.owner,
                                  MergedBlockHasIncorrectSignature
                                )
                              )

                            case Right(hashedBlock) =>
                              cleanUpRoundData(roundData.ownProposal)
                                .map(_ => FinalBlock(hashedBlock).asRight[CellError].widen[Ω])
                          }
                        } yield result
                      // Not all proposals are present so we can't create a Final block
                      case _ =>
                        //TODO: should I inform the peer that we already got proposal for this round? I guess not.
                        NullTerminal.asRight[CellError].widen[Ω].pure[F]
                    }
                  } yield result
                case InformAboutInabilityToParticipate(proposal, reason) =>
                  for {
                    peersToInform <- context.clusterStorage.getPeers
                      .map(
                        _.filter(
                          p =>
                            (proposal.facilitators + proposal.senderId + proposal.owner - context.myId).contains(p.id)
                        )
                      )
                    _ <- logger.debug(
                      s"Peers to inform about inability to participate ${peersToInform.map(_.id.value.value.take(5))}"
                    )
                    cancellationMessage = CancelledBlockCreationRound(
                      proposal.roundId,
                      senderId = context.myId,
                      owner = proposal.owner,
                      reason
                    )
                    signedCancellationMessage <- Signed
                      .forAsyncKryo[F, L1PeerDAGBlockData](cancellationMessage, context.keyPair)
                    _ <- broadcast(signedCancellationMessage, peersToInform)
                  } yield NullTerminal.asRight[CellError].widen[Ω]
                case PersistCancellationResult(cancellation) =>
                  processCancellation(cancellation)
                case RoundStartFailure(message) => CellError(message).asLeft[Ω].pure[F]
              }
            case Done(other) => other.pure[F]
          },
          CoalgebraM[F, StackF, L1DAGBlockCoalgebraCommand] {
            case StartOwnRound =>
              for {
                roundId <- GenUUID.forSync[F].make.map(RoundId(_))
                maybePeers <- context.clusterStorage.getPeers
                  .map(_.filter(_.state == Ready))
                  .flatMap(peers => Random[F].shuffleList(peers.toList))
                  .map(_.take(context.consensusConfig.peersCount) match {
                    case peers if peers.size == context.consensusConfig.peersCount => peers.toSet.some
                    case _                                                         => None
                  })
                maybeTips <- context.blockStorage.pullTips(context.consensusConfig.tipsCount)
                algebraCommand <- (maybePeers, maybeTips) match {
                  case (Some(peers), Some(tips)) =>
                    for {
                      // TODO: should we start round when we don't have any transactions?
                      //  I guess maybe we could have the transaction incoming on the endpoint have the Tick generated in order to possibly trigger the round immediately.
                      //  And a regular periodic starting round with no transactions (or reevaluating waiting ones) every period of time just like we have now.
                      transactions <- context.transactionStorage
                        .pullTransactions()
                        .map(_.map(_.toList.toSet).getOrElse(Set.empty))
                      roundData = RoundData(
                        roundId,
                        peers,
                        context.myId,
                        Proposal(roundId, context.myId, context.myId, peers.map(_.id), transactions, tips),
                        None,
                        None,
                        Map.empty,
                        Map.empty,
                        Map.empty,
                        tips
                      )
                    } yield PersistInitialRoundData(roundData)

                  case (Some(_), None) => RoundStartFailure("Missing tips!").pure[F]
                  case (None, Some(_)) => RoundStartFailure("Missing peers!").pure[F]
                  case (None, None)    => RoundStartFailure("Missing both peers and tips!").pure[F]
                }
              } yield Done(algebraCommand.asRight[CellError])

            case ProcessProposal(proposal) =>
              for {
                // TODO: I think we can use the owner id from the proposal and base the selection on this?
                // TODO: Maybe we shouldn't allow the case when we get a proposal for round where we are the owners but the round doesn't exist
                maybeRoundData <- context.consensusStorage.ownConsensus.get.flatMap {
                  case Some(ownRoundData) if ownRoundData.roundId == proposal.roundId => Option(ownRoundData).pure[F]
                  case _                                                              => context.consensusStorage.peerConsensuses(proposal.roundId).get
                }
                maybePeers <- context.clusterStorage.getPeers.flatMap {
                  knownPeers =>
                    for {
                      _ <- Async[F].unit
                      peerIds = proposal.facilitators + proposal.senderId + proposal.owner - context.myId
                      peers = peerIds
                        .map(id => id -> knownPeers.find(_.id == id))
                        .collect { case (id, Some(peer)) => id -> peer }
                        .toMap
                      result <- if (peers.keySet == peerIds && peerIds.size == context.consensusConfig.peersCount)
                        peers.values.toSet.some.pure[F]
                      else
                        logger.debug(
                          s"Peers missing: ${peerIds.diff(peers.keySet).map(_.value.value.take(5))} or peers count=${peerIds.size} different than required(${context.consensusConfig.peersCount})."
                        ) >>
                          none[Set[Peer]].pure[F]
                    } yield result
                }
                algebraCommand <- (maybeRoundData, maybePeers) match {
                  case (Some(_), _) => PersistProposal(proposal).pure[F]
                  case (None, Some(peers)) =>
                    for {
                      transactions <- context.transactionStorage
                        .pullTransactions()
                        .map(_.map(_.toList.toSet).getOrElse(Set.empty))
                      roundData = RoundData(
                        proposal.roundId,
                        peers,
                        proposal.owner,
                        Proposal(
                          proposal.roundId,
                          senderId = context.myId,
                          owner = proposal.owner,
                          peers.map(_.id),
                          transactions,
                          proposal.tips
                        ),
                        None,
                        None,
                        Map(proposal.senderId -> proposal),
                        Map.empty,
                        Map.empty,
                        proposal.tips
                      )
                    } yield PersistInitialRoundData(roundData)
                  case (None, None) => InformAboutInabilityToParticipate(proposal, MissingRoundPeers).pure[F]
                }
              } yield Done(algebraCommand.asRight[CellError])
            case ProcessBlockProposal(blockProposal) =>
              //TODO: Can I check anything here?
              Async[F].pure(Done(PersistBlockProposal(blockProposal).asRight[CellError]))
            case ProcessCancellation(c) =>
              Async[F].pure(Done(PersistCancellationResult(c).asRight[CellError]))
          }
        )
      }, {
        case OwnRoundTrigger                => StartOwnRound
        case p: Proposal                    => ProcessProposal(p)
        case bp: BlockProposal              => ProcessBlockProposal(bp)
        case c: CancelledBlockCreationRound => ProcessCancellation(c)
      }
    )

// DATA - global
sealed trait L1DAGBlockData extends Ω
sealed trait L1OwnerDAGBlockData extends L1DAGBlockData

@derive(encoder, decoder)
sealed trait L1PeerDAGBlockData extends L1DAGBlockData { val senderId: PeerId; val owner: PeerId }
case object OwnRoundTrigger extends L1OwnerDAGBlockData
case class Proposal(
  roundId: RoundId,
  senderId: PeerId,
  owner: PeerId,
  facilitators: Set[PeerId],
  transactions: Set[Signed[Transaction]],
  tips: PulledTips
) extends L1PeerDAGBlockData
case class BlockProposal(
  roundId: RoundId,
  senderId: PeerId,
  owner: PeerId,
  signedBlock: Signed[DAGBlock]
) extends L1PeerDAGBlockData
case class CancelledBlockCreationRound(roundId: RoundId, senderId: PeerId, owner: PeerId, reason: CancellationReason)
    extends L1PeerDAGBlockData

// OUTPUT
sealed trait L1DAGBlockOutput extends Ω
//case class RoundStarted(roundId: RoundId) extends L1DAGBlockOutput
case class FinalBlock(hashedBlock: Hashed[DAGBlock]) extends L1DAGBlockOutput

// COALGEBRA COMMAND - internal
sealed trait L1DAGBlockCoalgebraCommand
case object StartOwnRound extends L1DAGBlockCoalgebraCommand
case class ProcessProposal(proposal: Proposal) extends L1DAGBlockCoalgebraCommand
case class ProcessBlockProposal(blockProposal: BlockProposal) extends L1DAGBlockCoalgebraCommand
case class ProcessCancellation(cancellation: CancelledBlockCreationRound) extends L1DAGBlockCoalgebraCommand

// ALGEBRA COMMAND
sealed trait L1DAGBlockAlgebraCommand extends Ω
case class PersistInitialRoundData(roundData: RoundData) extends L1DAGBlockAlgebraCommand
case class PersistProposal(proposal: Proposal) extends L1DAGBlockAlgebraCommand
case class PersistBlockProposal(blockProposal: BlockProposal) extends L1DAGBlockAlgebraCommand
case class InformAboutInabilityToParticipate(proposal: Proposal, reason: CancellationReason)
    extends L1DAGBlockAlgebraCommand
case class PersistCancellationResult(cancellation: CancelledBlockCreationRound) extends L1DAGBlockAlgebraCommand
case class RoundStartFailure(message: String) extends L1DAGBlockAlgebraCommand

// CANCELLATION REASON
@derive(encoder, decoder)
sealed trait CancellationReason
case object MissingRoundPeers extends CancellationReason
case object CreatedBlockInvalid extends CancellationReason
case object MergedBlockHasIncorrectSignature extends CancellationReason
case object PeerCancelled extends CancellationReason
