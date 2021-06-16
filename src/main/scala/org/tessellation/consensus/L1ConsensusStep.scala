package org.tessellation.consensus

import cats.data.{EitherT, StateT}
import cats.effect.IO
import cats.syntax.all._
import higherkindness.droste.{AlgebraM, CoalgebraM}
import io.chrisdavenport.fuuid.FUUID
import io.chrisdavenport.log4cats.SelfAwareStructuredLogger
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import monocle.macros.syntax.lens._
import org.http4s.client.Client
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.schema.{CellError, Ω}
import org.tessellation.{Log, Node, Peer}
import org.http4s.dsl.io._
import org.http4s.client.dsl.io._
import org.tessellation.consensus.L1ConsensusStep.StateM
import org.tessellation.http.HttpClient

import scala.util.Random

object L1ConsensusStep {
  type StateM[A] = StateT[IO, L1ConsensusMetadata, A]
  implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)
  type RoundId = String
  val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]

  val algebra: AlgebraM[StateM, L1ConsensusF, Either[CellError, Ω]] = AlgebraM {
    case ConsensusEnd(responses) =>
      StateT { metadata =>
        logger.debug("[ConsensusEnd] Producing block") >> IO {
          val txs = responses.map(_.receiverProposals).foldRight(Set.empty[L1Transaction])(_ ++ _)
          (metadata, L1Block(txs).asRight[CellError])
        }
      }

    case L1Error(reason) =>
      StateT { metadata =>
        logger.error(reason)("L1Error in Algebra") >> IO {
          (metadata, CellError(reason.getMessage).asLeft[Ω])
        }
      }

    case cmd: Ω =>
      StateT { metadata =>
        logger.debug(s"Ω: ${cmd}") >> IO {
          (metadata, cmd.asRight[CellError])
        }
      }
  }

  // TODO: Use Reader monad -> Reader[L1ConsensusContext, Ω]
  val coalgebra: CoalgebraM[StateM, L1ConsensusF, Ω] = CoalgebraM {
    case StartOwnRound(edge) =>
      for {
        _ <- generateRoundId()
        _ <- storeOwnTransactions(edge.txs)
        _ <- selectFacilitators(2)
        next <- StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
          for {
            _ <- logger.debug("[StartOwnRound]")
          } yield (metadata, BroadcastProposal())
        }
      } yield next

    case BroadcastProposal() =>
      for {
        responses <- broadcastProposal()
        next <- StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
          for {
            _ <- logger.debug(s"[BroadcastProposal]")
            nextStep: L1ConsensusF[Ω] = responses.fold(L1Error(_), ConsensusEnd(_))
          } yield (metadata, nextStep)
        }
      } yield next

    case ReceiveProposal(roundId, senderId, proposal, ownEdge) =>
      for {
        _ <- storeRoundId(roundId)
        _ <- storeSenderId(senderId)
        _ <- storeReceivedTransactions(senderId, proposal.txs) // Storing received transactions
        _ <- storeOwnTransactions(ownEdge.txs)
        next <- StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
          for {
            _ <- logger.debug("[ReceiveProposal]")
          } yield (metadata, BroadcastReceivedProposal())
        }
      } yield next

    case BroadcastReceivedProposal() =>
      for {
        responses <- broadcastProposal()
        next <- StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
          for {
            _ <- logger.debug("[BroadcastReceivedProposal]")
            nextStep: L1ConsensusF[Ω] = responses.fold(
              L1Error(_),
              r => {
                val existingTxs = metadata.roundId
                  .flatMap(metadata.txs.get)
                  .map(_.values.flatten.toSet)
                  .getOrElse(Set.empty[L1Transaction])
                val receivedTxs = r.flatMap(_.receiverProposals).toSet
                ProposalResponse(existingTxs ++ receivedTxs)
              }
            )
          } yield (metadata, nextStep)
        }
      } yield next
  }

  def broadcastProposal(): StateM[Either[Throwable, List[BroadcastProposalResponse]]] = StateT { metadata =>
    val request = for {
      roundId <- metadata.roundId
      facilitators <- metadata.facilitators
      senderId <- metadata.senderId.orElse(metadata.consensusOwnerId)
      consensusOwnerId <- metadata.consensusOwnerId
      selfId = metadata.context.selfId
      txs <- metadata.txs.get(roundId).map(_.values.flatten.toSet)
      dst <- metadata.facilitators.map {
        _.filterNot(_.id == selfId)
          .filterNot(_.id == consensusOwnerId)
          .filterNot(_.id == senderId)
      }
      request <- Some(
        logger.debug(
          s"Broadcasting proposal for round $roundId started by consensusOwner $consensusOwnerId to facilitators $dst"
        ) >> dst.toList
          .parTraverse(
            metadata.context.httpClient.sendConsensusProposal(roundId, consensusOwnerId, txs, facilitators)
          )
      )
    } yield request

    request
      .getOrElse(
        IO.raiseError(
          CellError(
            s"Missing data to broadcast proposal: roundId ${metadata.roundId}, senderId ${metadata.senderId}, consensusOwnerId ${metadata.consensusOwnerId}"
          )
        )
      )
      .attempt
      .map(result => (metadata, result))

  }

  def generateRoundId(): StateM[Unit] = StateT { metadata =>
    FUUID
      .randomFUUID[IO]
      .map(_.toString)
      .map(_.some)
      .map(o => (metadata.lens(_.roundId).set(o), ()))
  }

  def storeOwnTransactions(txs: Set[L1Transaction]): StateM[Unit] = StateT { metadata =>
    IO {
      (metadata.lens(_.txs).modify { t =>
        t.updatedWith(metadata.roundId.get) { prev =>
          prev
            .map(_.updated(metadata.context.selfId, txs))
            .orElse(Map(metadata.context.selfId -> txs).some)
        }
      }, ())
    }
  }

  def storeSenderId(senderId: String): StateM[Unit] = StateT { metadata =>
    IO {
      (metadata.lens(_.senderId).set(senderId.some), ())
    }
  }

  def storeReceivedTransactions(senderId: String, txs: Set[L1Transaction]): StateM[Unit] = StateT { metadata =>
    IO {
      (metadata.lens(_.txs).modify { t =>
        t.updatedWith(metadata.roundId.get) { prev =>
          prev
            .map(_.updated(senderId, txs))
            .orElse(Map(senderId -> txs).some)
        }
      }, ())
    }
  }

  def selectFacilitators(n: Int = 2): StateM[Unit] = StateT { metadata =>
    IO {
      Random.shuffle(metadata.context.peers).take(n)
    }.map(facilitators => (metadata.lens(_.facilitators).set(facilitators.some), ()))
  }

  def storeRoundId(roundId: RoundId): StateM[Unit] = StateT { metadata =>
    IO {
      (metadata.lens(_.roundId).set(roundId.some), ())
    }
  }

  case class BroadcastProposalPayload(
    senderId: String,
    consensusOwnerId: String,
    roundId: RoundId,
    proposal: Set[L1Transaction],
    facilitators: Set[Peer]
  )

  case class BroadcastProposalResponse(
    roundId: RoundId,
    senderProposals: Set[L1Transaction],
    receiverProposals: Set[L1Transaction]
  )

  case class L1ConsensusContext(
    selfId: String,
    peers: Set[Peer],
    txGenerator: RandomTransactionGenerator,
    httpClient: HttpClient
  )

  case class L1ConsensusMetadata(
    context: L1ConsensusContext,
    txs: Map[RoundId, Map[String, Set[L1Transaction]]], // TODO: @mwadon - why FUUID?
    facilitators: Option[Set[Peer]],
    roundId: Option[RoundId],
    consensusOwnerId: Option[String],
    senderId: Option[String]
  )

  object BroadcastProposalResponse {
    implicit val decoder: Decoder[BroadcastProposalResponse] = deriveDecoder
    implicit val encoder: Encoder[BroadcastProposalResponse] = deriveEncoder
  }

  object BroadcastProposalPayload {
    implicit val decoder: Decoder[BroadcastProposalPayload] = deriveDecoder
    implicit val encoder: Encoder[BroadcastProposalPayload] = deriveEncoder
  }

  object L1ConsensusMetadata {

    def empty(context: L1ConsensusContext): L1ConsensusMetadata =
      L1ConsensusMetadata(
        context = context,
        txs = Map.empty,
        facilitators = None,
        roundId = None,
        consensusOwnerId = None,
        senderId = None
      )
  }
}
