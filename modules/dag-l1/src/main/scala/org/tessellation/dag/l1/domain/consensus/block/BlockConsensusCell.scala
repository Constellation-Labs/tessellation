package org.tessellation.dag.l1.domain.consensus.block

import cats.effect.std.Random
import cats.effect.{Async, Clock}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.{Applicative, Id, Order}

import scala.concurrent.duration.FiniteDuration

import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusCell._
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusInput._
import org.tessellation.dag.l1.domain.consensus.block.BlockConsensusOutput.{CleanedConsensuses, FinalBlock, NoData}
import org.tessellation.dag.l1.domain.consensus.block.CancellationReason._
import org.tessellation.dag.l1.domain.consensus.block.Validator.isReadyForBlockConsensus
import org.tessellation.dag.l1.domain.consensus.block.http.p2p.clients.BlockConsensusClient
import org.tessellation.dag.l1.domain.transaction.{TransactionStorage, transactionLoggerName}
import org.tessellation.ext.collection.MapRefUtils.MapRefOps
import org.tessellation.kernel._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.Block
import org.tessellation.schema.Block.BlockConstructor
import org.tessellation.schema.peer.{Peer, PeerId}
import org.tessellation.schema.round.RoundId
import org.tessellation.schema.transaction.Transaction
import org.tessellation.sdk.domain.block.processing.BlockValidationParams
import org.tessellation.sdk.effects.GenUUID
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed._
import org.tessellation.security.{Hashed, SecurityProvider}

import eu.timepit.refined.auto.{autoRefineV, autoUnwrap}
import io.circe.Encoder
import org.typelevel.log4cats.slf4j.Slf4jLogger

class BlockConsensusCell[
  F[_]: Async: SecurityProvider: KryoSerializer: Random,
  T <: Transaction: Encoder: Order: Ordering,
  B <: Block[T]
](
  data: BlockConsensusInput[T],
  ctx: BlockConsensusContext[F, T, B]
)(implicit blockConstructor: BlockConstructor[T, B])
    extends Cell[F, Id, BlockConsensusInput[T], Either[CellError, BlockConsensusOutput[B]], BlockConsensusInput[T]](
      data,
      {
        case OwnRoundTrigger                                => startOwnRound(ctx)
        case InspectionTrigger                              => inspectConsensuses(ctx)
        case proposal: Proposal[T]                          => processProposal(proposal, ctx)
        case blockSignatureProposal: BlockSignatureProposal => persistBlockSignatureProposal(blockSignatureProposal, ctx)
        case cancellation: CancelledBlockCreationRound      => processCancellation(cancellation, ctx)
      },
      identity
    )

object BlockConsensusCell {

  private def logger[F[_]: Async] = Slf4jLogger.getLogger

  private def getTransactionLogger[F[_]: Async] = Slf4jLogger.getLoggerFromName(transactionLoggerName)

  private def getTime[F[_]: Clock](): F[FiniteDuration] = Clock[F].monotonic

  private def deriveConsensusPeerIds[T <: Transaction](proposal: Proposal[T], selfId: PeerId): Set[PeerId] =
    proposal.facilitators + proposal.senderId + proposal.owner - selfId

  private def returnTransactions[F[_]: Async: KryoSerializer, T <: Transaction](
    ownProposal: Proposal[T],
    transactionStorage: TransactionStorage[F, T]
  ): F[Unit] =
    ownProposal.transactions.toList
      .traverse(_.toHashed[F])
      .map(_.toSet)
      .flatTap { txs =>
        getTransactionLogger[F]
          .info(s"Returned transactions for round: ${ownProposal.roundId} are: ${txs.size}, ${txs.map(_.hash).show}")
      }
      .flatMap {
        transactionStorage.put
      }

  private def cleanUpRoundData[F[_]: Async, T <: Transaction, B <: Block[T]](
    ownProposal: Proposal[T],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Unit] = {
    def clean: Option[RoundData[T, B]] => (Option[RoundData[T, B]], Unit) = {
      case Some(roundData: RoundData[T, B]) if roundData.roundId == ownProposal.roundId => (none[RoundData[T, B]], ())
      case current                                                                      => (current, ())
    }

    (ownProposal.owner == ctx.selfId)
      .pure[F]
      .ifM(
        ctx.consensusStorage.ownConsensus.modify(clean),
        ctx.consensusStorage.peerConsensuses(ownProposal.roundId).modify(clean)
      )
  }

  private def cancelRound[F[_]: Async: KryoSerializer, T <: Transaction, B <: Block[T]](
    ownProposal: Proposal[T],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Unit] =
    for {
      _ <- returnTransactions(ownProposal, ctx.transactionStorage)
      _ <- cleanUpRoundData(ownProposal, ctx)
    } yield ()

  private def broadcast[F[_]: Async, T <: Transaction: Encoder](
    data: Signed[PeerBlockConsensusInput[T]],
    peers: Set[Peer],
    blockConsensusClient: BlockConsensusClient[F, T]
  ): F[Unit] =
    peers.toList
      .traverse(blockConsensusClient.sendConsensusData(data)(_))
      .void

  private def tryPersistRoundData[T <: Transaction, B <: Block[T]](
    roundData: RoundData[T, B]
  ): Option[RoundData[T, B]] => (Option[RoundData[T, B]], Option[RoundData[T, B]]) = {
    case existing @ Some(_) => (existing, None)
    case None               => (roundData.some, roundData.some)
  }

  private def sendOwnProposal[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder, B <: Block[T]](
    ownProposal: Proposal[T],
    peers: Set[Peer],
    context: BlockConsensusContext[F, T, B]
  ): F[Unit] =
    for {
      signedProposal <- Signed.forAsyncKryo[F, PeerBlockConsensusInput[T]](ownProposal, context.keyPair)
      _ <- broadcast(signedProposal, peers, context.blockConsensusClient)
    } yield ()

  def persistInitialOwnRoundData[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder, B <: Block[T]](
    roundData: RoundData[T, B],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    ctx.consensusStorage.ownConsensus
      .modify(tryPersistRoundData(roundData))
      .flatMap {
        case Some(RoundData(_, _, peers, _, ownProposal, _, _, _, _, _, _)) =>
          sendOwnProposal(ownProposal, peers, ctx)
            .map(_ => NoData.asRight[CellError].widen[BlockConsensusOutput[B]])
        case None =>
          returnTransactions(roundData.ownProposal, ctx.transactionStorage)
            .map(_ => CellError("Another own round already in progress! Transactions returned.").asLeft[BlockConsensusOutput[B]])
      }

  def persistInitialPeerRoundData[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder, B <: Block[T]](
    roundData: RoundData[T, B],
    peerProposal: Proposal[T],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    ctx.consensusStorage
      .peerConsensuses(roundData.roundId)
      .modify(tryPersistRoundData(roundData))
      .flatMap {
        case Some(RoundData(_, _, peers, _, ownProposal, _, _, _, _, _, _)) =>
          sendOwnProposal(ownProposal, peers, ctx)
        case None =>
          for {
            _ <- logger
              .debug(
                s"Round with roundId=${roundData.roundId} already exists! Returning transactions and processing proposal!"
              )
            _ <- returnTransactions(roundData.ownProposal, ctx.transactionStorage)
          } yield ()
      } >> persistProposal(peerProposal, ctx)

  private def canPersistProposal(roundData: RoundData[_, _], proposal: Proposal[_]): Boolean = {
    val sameRoundId = roundData.roundId == proposal.roundId
    lazy val peerExists = roundData.peers.exists(_.id == proposal.senderId)
    lazy val noProposalYet = !roundData.peerProposals.contains(proposal.senderId)

    sameRoundId && peerExists && noProposalYet
  }

  private def tryPersistProposal[T <: Transaction, B <: Block[T]](
    proposal: Proposal[T]
  ): Option[RoundData[T, B]] => (Option[RoundData[T, B]], Option[RoundData[T, B]]) = {
    case Some(roundData) if canPersistProposal(roundData, proposal) =>
      val updated = roundData.addPeerProposal(proposal)
      (updated.some, updated.some)
    case other => (other, None)
  }

  private def canPersistOwnBlock[T <: Transaction, B <: Block[T]](roundData: RoundData[T, B], proposal: Proposal[T]): Boolean =
    roundData.ownBlock.isEmpty && roundData.roundId == proposal.roundId

  private def tryPersistOwnBlock[T <: Transaction, B <: Block[T]](
    signedBlock: Signed[B],
    proposal: Proposal[T]
  ): Option[RoundData[T, B]] => (Option[RoundData[T, B]], Option[RoundData[T, B]]) = {
    case Some(roundData) if canPersistOwnBlock(roundData, proposal) =>
      val updated = roundData.setOwnBlock(signedBlock)
      (updated.some, updated.some)
    case other => (other, None)
  }

  private def sendBlockProposal[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder, B <: Block[T]](
    signedBlock: Signed[B],
    roundData: RoundData[T, B],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    for {
      _ <- Applicative[F].unit
      blockSignatureProposal = BlockSignatureProposal(
        roundData.roundId,
        ctx.selfId,
        roundData.owner,
        signedBlock.proofs.head.signature
      )
      signedBlockSignatureProposal <- Signed
        .forAsyncKryo[F, PeerBlockConsensusInput[T]](blockSignatureProposal, ctx.keyPair)
      _ <- broadcast(signedBlockSignatureProposal, roundData.peers, ctx.blockConsensusClient)
    } yield NoData.asRight[CellError].widen[BlockConsensusOutput[B]]

  private def processValidBlock[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder, B <: Block[T]](
    proposal: Proposal[T],
    signedBlock: Signed[B],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    (proposal.owner == ctx.selfId)
      .pure[F]
      .ifM(
        ctx.consensusStorage.ownConsensus.modify(tryPersistOwnBlock(signedBlock, proposal)),
        ctx.consensusStorage
          .peerConsensuses(proposal.roundId)
          .modify(tryPersistOwnBlock(signedBlock, proposal))
      )
      .flatMap {
        case Some(roundData) =>
          sendBlockProposal(signedBlock, roundData, ctx)
        case None =>
          CellError("Tried to persist own signed block but the update failed!").asLeft[BlockConsensusOutput[B]].pure[F]
      }

  private def gotAllProposals[T <: Transaction, B <: Block[T]](roundData: RoundData[T, B]): Boolean =
    roundData.peers.map(_.id) == roundData.peerProposals.keySet

  private val validationParams: BlockValidationParams = BlockValidationParams.default.copy(minSignatureCount = 1)

  def persistProposal[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder, B <: Block[T]](
    proposal: Proposal[T],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] = (proposal.owner == ctx.selfId)
    .pure[F]
    .ifM(
      ctx.consensusStorage.ownConsensus.modify(tryPersistProposal(proposal)),
      ctx.consensusStorage.peerConsensuses(proposal.roundId).modify(tryPersistProposal(proposal))
    )
    .flatMap {
      case Some(roundData) if gotAllProposals(roundData) =>
        roundData.formBlock(ctx.transactionValidator).flatMap {
          case Some(block) =>
            Signed.forAsyncKryo(block, ctx.keyPair).flatMap { signedBlock =>
              ctx.blockValidator
                .validate(signedBlock, validationParams)
                .flatTap { validationResult =>
                  Applicative[F].whenA(validationResult.isInvalid) {
                    logger.debug(s"Created block is invalid: $validationResult")
                  }
                }
                .map(_.isValid)
                .ifM(
                  processValidBlock(proposal, signedBlock, ctx), {
                    val cancellation = CancelledBlockCreationRound(
                      roundData.roundId,
                      senderId = ctx.selfId,
                      owner = roundData.owner,
                      CreatedInvalidBlock
                    )
                    processCancellation(cancellation, ctx)
                  }
                )
            }
          case None =>
            val cancellation = CancelledBlockCreationRound(
              roundData.roundId,
              senderId = ctx.selfId,
              owner = roundData.owner,
              CreatedBlockWithNoTransactions
            )
            processCancellation(cancellation, ctx)
        }
      case _ => NoData.asRight[CellError].widen[BlockConsensusOutput[B]].pure[F]

    }

  private def canPersistBlockSignatureProposal[T <: Transaction, B <: Block[T]](
    roundData: RoundData[T, B],
    blockSignatureProposal: BlockSignatureProposal
  ): Boolean = {
    val sameRoundId = roundData.roundId == blockSignatureProposal.roundId
    lazy val peerExists = roundData.peers.exists(_.id == blockSignatureProposal.senderId)
    lazy val noBlockYet = !roundData.peerBlockSignatures.contains(blockSignatureProposal.senderId)

    sameRoundId && peerExists && noBlockYet
  }

  private def gotAllSignatures[T <: Transaction, B <: Block[T]](roundData: RoundData[T, B]): Boolean =
    roundData.peers.map(_.id) == roundData.peerBlockSignatures.keySet

  private def tryPersistBlockSignatureProposal[T <: Transaction, B <: Block[T]](
    blockSignatureProposal: BlockSignatureProposal
  ): Option[RoundData[T, B]] => (Option[RoundData[T, B]], Option[RoundData[T, B]]) = {
    case Some(roundData) if canPersistBlockSignatureProposal(roundData, blockSignatureProposal) =>
      val updated = roundData.addPeerBlockSignature(blockSignatureProposal)
      (updated.some, updated.some)
    case other => (other, None)
  }

  def persistBlockSignatureProposal[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction, B <: Block[T]](
    blockSignatureProposal: BlockSignatureProposal,
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    (blockSignatureProposal.owner == ctx.selfId)
      .pure[F]
      .ifM(
        ctx.consensusStorage.ownConsensus.modify(tryPersistBlockSignatureProposal(blockSignatureProposal)),
        ctx.consensusStorage
          .peerConsensuses(blockSignatureProposal.roundId)
          .modify(tryPersistBlockSignatureProposal(blockSignatureProposal))
      )
      .flatMap {
        case Some(roundData @ RoundData(_, _, _, _, _, Some(ownBlock), _, _, _, _, _)) if gotAllSignatures(roundData) =>
          for {
            _ <- Applicative[F].unit
            finalBlock = roundData.peerBlockSignatures.values.foldLeft(ownBlock) {
              case (agg, proof) => agg.addProof(proof)
            }
            result <- finalBlock.toHashedWithSignatureCheck.flatMap {
              case Left(_) =>
                cancelRound(roundData.ownProposal, ctx)
                  .map(_ => CellError("Round cancelled after final block turned out to be invalid!").asLeft[BlockConsensusOutput[B]])

              case Right(hashedBlock) =>
                cleanUpRoundData(roundData.ownProposal, ctx)
                  .map(_ => FinalBlock[B](hashedBlock).asRight[CellError].widen[BlockConsensusOutput[B]])
            }
          } yield result
        case _ =>
          NoData.asRight[CellError].widen[BlockConsensusOutput[B]].pure[F]
      }

  def informAboutInabilityToParticipate[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder, B <: Block[T]](
    proposal: Proposal[T],
    reason: CancellationReason,
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    for {
      peersToInform <- ctx.clusterStorage.getResponsivePeers
        .map(_.filter(peer => deriveConsensusPeerIds(proposal, ctx.selfId).contains(peer.id)))
      cancellation = CancelledBlockCreationRound(
        proposal.roundId,
        senderId = ctx.selfId,
        owner = proposal.owner,
        reason
      )
      signedCancellationMessage <- Signed
        .forAsyncKryo[F, PeerBlockConsensusInput[T]](cancellation, ctx.keyPair)
      _ <- broadcast(signedCancellationMessage, peersToInform, ctx.blockConsensusClient)
    } yield NoData.asRight[CellError].widen[BlockConsensusOutput[B]]

  private def canPersistCancellation[T <: Transaction, B <: Block[T]](
    roundData: RoundData[T, B],
    cancellation: CancelledBlockCreationRound,
    selfId: PeerId
  ): Boolean = {
    val sameRoundId = roundData.roundId == cancellation.roundId
    lazy val peerExists = roundData.peers.exists(_.id == cancellation.senderId)
    lazy val myCancellation = cancellation.senderId == selfId

    sameRoundId && (peerExists || myCancellation)
  }

  private def persistCancellationMessage[T <: Transaction, B <: Block[T]](
    cancellation: CancelledBlockCreationRound,
    selfId: PeerId
  ): Option[RoundData[T, B]] => (Option[RoundData[T, B]], Option[(RoundData[T, B], Option[CancelledBlockCreationRound])]) = {
    case Some(roundData) if canPersistCancellation(roundData, cancellation, selfId) =>
      (roundData, cancellation.senderId) match {
        case (roundData @ RoundData(_, _, _, _, _, _, Some(_), _, _, _, _), `selfId`) =>
          (roundData.some, None)

        case (roundData @ RoundData(_, _, _, _, _, _, None, _, _, _, _), `selfId`) =>
          val updated = roundData.setOwnCancellation(cancellation.reason)
          (updated.some, (updated, cancellation.some).some)

        case (roundData @ RoundData(_, _, _, _, _, _, Some(_), _, _, _, _), _) =>
          val updated = roundData.addPeerCancellation(cancellation)
          (updated.some, (updated, None).some)

        case (roundData @ RoundData(_, _, _, _, _, _, None, _, _, _, _), _) =>
          val myCancellation = CancelledBlockCreationRound(roundData.roundId, selfId, roundData.owner, PeerCancelled)
          val updated = roundData.setOwnCancellation(myCancellation.reason).addPeerCancellation(cancellation)
          (updated.some, (updated, myCancellation.some).some)
      }
    case other => (other, None)
  }

  private def canFinalizeRoundCancellation[T <: Transaction, B <: Block[T]](roundData: RoundData[T, B]): Boolean = {
    val ownCancellationPresent = roundData.ownCancellation.nonEmpty
    lazy val peerCancellationsPresent = roundData.peers.map(_.id) == roundData.peerCancellations.keySet

    ownCancellationPresent && peerCancellationsPresent
  }

  def processCancellation[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder, B <: Block[T]](
    cancellation: CancelledBlockCreationRound,
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    for {
      maybeUpdated <- (cancellation.owner == ctx.selfId)
        .pure[F]
        .ifM(
          ctx.consensusStorage.ownConsensus.modify(persistCancellationMessage(cancellation, ctx.selfId)),
          ctx.consensusStorage
            .peerConsensuses(cancellation.roundId)
            .modify(persistCancellationMessage(cancellation, ctx.selfId))
        )
      _ <- maybeUpdated match {
        case Some((roundData, Some(myCancellation))) =>
          for {
            signedCancellationMessage <- Signed
              .forAsyncKryo[F, PeerBlockConsensusInput[T]](myCancellation, ctx.keyPair)
            _ <- broadcast(signedCancellationMessage, roundData.peers, ctx.blockConsensusClient)
          } yield ()
        case _ => Applicative[F].unit
      }
      result <- maybeUpdated match {
        case Some((roundData, _)) if canFinalizeRoundCancellation(roundData) =>
          cancelRound(roundData.ownProposal, ctx)
            .map(_ => CellError("Round cancelled after all peers agreed!").asLeft[BlockConsensusOutput[B]])
        case Some((_, Some(myCancellation))) =>
          CellError(
            s"Round is being cancelled! Own round cancellation request got processed. Reason: ${myCancellation.reason}"
          ).asLeft[BlockConsensusOutput[B]].pure[F]
        case Some(_) =>
          CellError(s"Round is being cancelled! Round cancellation request got processed.").asLeft[BlockConsensusOutput[B]].pure[F]
        case None => NoData.asRight[CellError].widen[BlockConsensusOutput[B]].pure[F]
      }
    } yield result

  def cancelTimedOutRounds[F[_]: Async: KryoSerializer, T <: Transaction, B <: Block[T]](
    toCancel: Set[Proposal[T]],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    toCancel.toList
      .traverse(cancelRound(_, ctx))
      .map(_ => CleanedConsensuses(toCancel.map(_.roundId)).asRight[CellError])

  private def pullNewConsensusPeers[F[_]: Async: Random, T <: Transaction, B <: Block[T]](
    ctx: BlockConsensusContext[F, T, B]
  ): F[Option[Set[Peer]]] =
    ctx.clusterStorage.getResponsivePeers
      .map(_.filter(p => isReadyForBlockConsensus(p.state)))
      .flatMap(peers => Random[F].shuffleList(peers.toList))
      .map(_.take(ctx.consensusConfig.peersCount).toSet match {
        case peers if peers.size == ctx.consensusConfig.peersCount.value => peers.some
        case _                                                           => None
      })

  private def pullTransactions[F[_]: Async, T <: Transaction, B <: Block[T]](
    ctx: BlockConsensusContext[F, T, B]
  ): F[Set[Hashed[T]]] =
    ctx.transactionStorage
      .pull(ctx.consensusConfig.pullTxsCount)
      .map(_.map(_.toList.toSet).getOrElse(Set.empty))

  def startOwnRound[F[_]: Async: Random: SecurityProvider: KryoSerializer, T <: Transaction: Encoder: Order: Ordering, B <: Block[T]](
    ctx: BlockConsensusContext[F, T, B]
  )(implicit blockConstructor: BlockConstructor[T, B]): F[Either[CellError, BlockConsensusOutput[B]]] =
    for {
      roundId <- GenUUID.forSync[F].make.map(RoundId(_))
      maybePeers <- pullNewConsensusPeers(ctx)
      maybeTips <- ctx.blockStorage.getTips(ctx.consensusConfig.tipsCount)
      result <- (maybePeers, maybeTips) match {
        case (Some(peers), Some(tips)) =>
          for {
            transactions <- pullTransactions(ctx)
            startedAt <- getTime()
            proposal = Proposal(
              roundId,
              senderId = ctx.selfId,
              owner = ctx.selfId,
              peers.map(_.id),
              transactions.map(_.signed),
              tips
            )
            roundData = RoundData[T, B](roundId, startedAt, peers, ctx.selfId, proposal, tips = tips)
            result <- persistInitialOwnRoundData(roundData, ctx)
          } yield result

        case (Some(_), None) =>
          CellError("Missing tips!").asLeft[BlockConsensusOutput[B]].pure[F]
        case (None, Some(_)) =>
          CellError("Missing peers!").asLeft[BlockConsensusOutput[B]].pure[F]
        case (None, None) =>
          CellError("Missing both peers and tips!").asLeft[BlockConsensusOutput[B]].pure[F]
      }
    } yield result

  def inspectConsensuses[F[_]: Async: KryoSerializer, T <: Transaction, B <: Block[T]](
    ctx: BlockConsensusContext[F, T, B]
  ): F[Either[CellError, BlockConsensusOutput[B]]] =
    for {
      ownConsensus <- ctx.consensusStorage.ownConsensus.get.map(_.toSet)
      peerConsensuses <- ctx.consensusStorage.peerConsensuses.toMap.map(_.values.toSet)
      current <- getTime()
      toCancel = (peerConsensuses ++ ownConsensus)
        .filter(_.startedAt + ctx.consensusConfig.timeout < current)
        .map(_.ownProposal)
      result <- toCancel.nonEmpty
        .pure[F]
        .ifM(
          cancelTimedOutRounds(toCancel, ctx),
          NoData.asRight[CellError].widen[BlockConsensusOutput[B]].pure[F]
        )
    } yield result

  private def fetchConsensusPeers[F[_]: Async, T <: Transaction, B <: Block[T]](
    proposal: Proposal[T],
    ctx: BlockConsensusContext[F, T, B]
  ): F[Option[Set[Peer]]] =
    for {
      knownPeers <- ctx.clusterStorage.getResponsivePeers
      peerIds = deriveConsensusPeerIds(proposal, ctx.selfId)
      peers = peerIds
        .map(id => id -> knownPeers.find(_.id == id))
        .collect { case (id, Some(peer)) => id -> peer }
        .toMap
      result <-
        if (peers.keySet == peerIds && peerIds.size == ctx.consensusConfig.peersCount.value)
          peers.values.toSet.some.pure[F]
        else
          none[Set[Peer]].pure[F]
    } yield result

  def processProposal[F[_]: Async: SecurityProvider: KryoSerializer, T <: Transaction: Encoder: Order: Ordering, B <: Block[T]](
    proposal: Proposal[T],
    ctx: BlockConsensusContext[F, T, B]
  )(implicit blockConstructor: BlockConstructor[T, B]): F[Either[CellError, BlockConsensusOutput[B]]] =
    for {
      maybeRoundData <- ctx.consensusStorage.ownConsensus.get.flatMap {
        case Some(ownRoundData) if ownRoundData.roundId == proposal.roundId => Option(ownRoundData).pure[F]
        case _                                                              => ctx.consensusStorage.peerConsensuses(proposal.roundId).get
      }
      maybePeers <- fetchConsensusPeers(proposal, ctx)
      result <- (maybeRoundData, maybePeers) match {
        case (Some(_), _) => persistProposal(proposal, ctx)
        case (None, _) if proposal.owner == ctx.selfId =>
          informAboutInabilityToParticipate(proposal, ReceivedProposalForNonExistentOwnRound, ctx)
        case (None, Some(peers)) =>
          for {
            transactions <- pullTransactions(ctx)
            startedAt <- getTime()
            ownProposal = Proposal(
              proposal.roundId,
              senderId = ctx.selfId,
              owner = proposal.owner,
              peers.map(_.id),
              transactions.map(_.signed),
              proposal.tips
            )
            roundData = RoundData(
              proposal.roundId,
              startedAt,
              peers,
              owner = proposal.owner,
              ownProposal,
              tips = proposal.tips
            )
            result <- persistInitialPeerRoundData(roundData, proposal, ctx)
          } yield result
        case (None, None) => informAboutInabilityToParticipate(proposal, MissingRoundPeers, ctx)
      }
    } yield result
}
