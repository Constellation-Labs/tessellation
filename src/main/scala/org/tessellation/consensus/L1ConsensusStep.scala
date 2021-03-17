package org.tessellation.consensus

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.chrisdavenport.fuuid.FUUID
import org.tessellation.consensus.L1TransactionPool.L1TransactionPoolEnqueue
import org.tessellation.schema.{CellError, Ω}
import org.tessellation.{Log, Node}
import monocle.macros.syntax.lens._
import org.tessellation.consensus.transaction.RandomTransactionGenerator

import scala.util.Random

object L1ConsensusStep {
  type Peer = String
  type StateM[A] = StateT[IO, L1ConsensusMetadata, A]
  implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)

  // TODO: Use Reader monad -> Reader[L1ConsensusContext, Ω]
  val coalgebra: CoalgebraM[StateM, L1ConsensusF, Ω] = CoalgebraM {

    case StartOwnRound(edge) =>
      generateRoundId() >> storeOwnTransactions(edge.txs) >> selectFacilitators(2) >> StateT[
        IO,
        L1ConsensusMetadata,
        L1ConsensusF[Ω]
      ] { metadata =>
        IO {
          Log.logNode(metadata.context.peer)(
            s"[${metadata.context.peer.id}][${metadata.roundId}][StartOwnRound] Locked transactions ${edge.txs.toList.sortBy(_.a)} and running consensus"
          )
          (metadata, BroadcastProposal())
        }
      }

    case BroadcastProposal() =>
      broadcastProposal() >>= { responses =>
        StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
          IO {
            (metadata, responses.fold(error => L1Error(error.reason), ConsensusEnd(_)))
          }
        }
      }

    case ReceiveProposal(roundId, proposalNode, proposalEdge, ownEdge) =>
      storeRoundId(roundId) >> storeOwnTransactions(proposalEdge.txs) >>
        StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
          IO {
            val roundId = metadata.roundId.get

            val state = metadata.lens(_.txs).modify { t =>
              t.updatedWith(roundId) { prev =>
                prev
                  .map(_.updated(metadata.context.peer, ownEdge.txs).updated(proposalNode, proposalEdge.txs))
                  .orElse(Map(metadata.context.peer -> ownEdge.txs, proposalNode -> proposalEdge.txs).some)
              }
            }

            Log.logNode(metadata.context.peer)(
              s"[${metadata.context.peer.id}][${metadata.roundId}][ReceiveProposal] Received proposal ${proposalEdge.txs} from (${proposalNode.id}). Broadcasting facilitator proposal ${ownEdge.txs}."
            )

            Log.logNode(metadata.context.peer)(
              s"[${metadata.context.peer.id}][${metadata.roundId}][ReceiveProposal] Stored for round: ${state.txs.get(roundId).map(_.values.flatten.toSet)}"
            )

            (state, BroadcastReceivedProposal())
          }
        }

    case BroadcastReceivedProposal() =>
      broadcastProposal() >>= { responses =>
        StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
          IO {
            responses match {
              case Right(r) => {
                val roundTxs = metadata.roundId
                  .flatMap(metadata.txs.get)

                val txs = roundTxs.map(_.values.flatten.toSet).getOrElse(Set.empty[L1Transaction])
                val resTxs = r.flatMap(_.receiverProposals).toSet
                // TODO: We can't send ProposalResponse because C can get proposal from B before proposal A reaches C! (Race condition)
                (metadata, ProposalResponse(txs ++ resTxs))
              }
              case Left(error) => (metadata, L1Error(error.reason))
            }
          }
        }
      }
  }

  val algebra: AlgebraM[StateM, L1ConsensusF, Either[CellError, Ω]] = AlgebraM {
    case ConsensusEnd(responses) =>
      StateT { metadata =>
        IO {
          val txs = responses.map(_.receiverProposals).foldRight(Set.empty[L1Transaction])(_ ++ _)
          (metadata, L1Block(txs).asRight[CellError])
        }
      }

    case L1Error(reason) =>
      StateT { metadata =>
        IO {
          (metadata, CellError(reason).asLeft[Ω])
        }
      }

    case cmd: Ω =>
      StateT { metadata =>
        IO {
          (metadata, cmd.asRight[CellError])
        }
      }
  }

  def broadcastProposal(): StateM[Either[CellError, List[BroadcastProposalResponse]]] = StateT { metadata =>
    def simulateHttpConsensusRequest(request: BroadcastProposalRequest, caller: Node, context: L1ConsensusContext): IO[BroadcastProposalResponse] = {
      val facilitatorTxs = context.peer.txGenerator.generateRandomTransaction().unsafeRunSync()
      val facilitatorCell = L1Cell(L1Edge(Set(facilitatorTxs)))
      val facilitatorConsensus = context.peer.participateInL1Consensus(request.roundId, caller, L1Edge(request.proposal), facilitatorCell)

      facilitatorConsensus.flatMap {
        case Right(ProposalResponse(txs)) => {
          Log.logNode(metadata.context.peer)(
            s"[${metadata.context.peer.id}][ProposalResponse] ${txs.toList.sortBy(_.a)}"
          )
          IO {
            BroadcastProposalResponse(request.roundId, request.proposal, txs)
          }
        }
        case Left(CellError(reason)) => {
          Log.red("unexpected")
          IO.raiseError(CellError(reason))
        }
      }
    }

    val responsesFromFacilitators = for {
      facilitators <- metadata.facilitators.map(_.filterNot(_ == metadata.context.peer))
      _ <- Option {
        Log.logNode(metadata.context.peer)(
          s"[${metadata.context.peer.id}][${metadata.roundId}][BroadcastProposal] Broadcasting proposal to facilitators: ${facilitators.map(_.id)}"
        )
        ()
      }
      txs <- metadata.roundId.flatMap(metadata.txs.get).flatMap(_.get(metadata.context.peer))
      request <- metadata.roundId.map(BroadcastProposalRequest(_, txs, facilitators))
      responses <- facilitators.toList.parTraverse { facilitator =>
        (for {
          proposalResponse <- simulateHttpConsensusRequest(
            request,
            metadata.context.peer,
            metadata.context
              .lens(_.peer)
              .set(facilitator)
          )
        } yield proposalResponse).attempt.map(_.leftMap(e => CellError(e.getMessage)))
      }.some
    } yield responses

    // TODO: handle Either instead of Option above
    responsesFromFacilitators.sequence
      .map(_.map(_.sequence).getOrElse(List.empty.asRight[CellError]))
      .map((metadata, _))

  }

  def generateRoundId(): StateM[Unit] = StateT { metadata =>
    FUUID
      .randomFUUID[IO]
      .map(_.some)
      .map(o => (metadata.lens(_.roundId).set(o), ()))
  }

  def storeOwnTransactions(txs: Set[L1Transaction]): StateM[Unit] = StateT { metadata =>
    IO {
      (metadata.lens(_.txs).modify { t =>
        t.updatedWith(metadata.roundId.get) { prev =>
          prev
            .map(_.updated(metadata.context.peer, txs))
            .orElse(Map(metadata.context.peer -> txs).some)
        }
      }, ())
    }
  }

  def storeRoundId(roundId: FUUID): StateM[Unit] = StateT { metadata =>
    IO {
      (metadata.lens(_.roundId).set(roundId.some), ())
    }
  }

  def selectFacilitators(n: Int = 2): StateM[Unit] = StateT { metadata =>
    IO {
      Random.shuffle(metadata.context.peers).take(n)
    }.map(facilitators => (metadata.lens(_.facilitators).set(facilitators.some), ()))
  }

  case class BroadcastProposalRequest(roundId: FUUID, proposal: Set[L1Transaction], facilitators: Set[Node])

  case class BroadcastProposalResponse(
    roundId: FUUID,
    senderProposals: Set[L1Transaction],
    receiverProposals: Set[L1Transaction]
  )

  case class L1ConsensusContext(
    peer: Node,
    peers: Set[Node],
    txGenerator: RandomTransactionGenerator
  )

  case class L1ConsensusMetadata(
    context: L1ConsensusContext,
    txs: Map[FUUID, Map[Node, Set[L1Transaction]]], // TODO: @mwadon - why FUUID?
    facilitators: Option[Set[Node]],
    roundId: Option[FUUID]
  )

  object L1ConsensusMetadata {

    def empty(context: L1ConsensusContext): L1ConsensusMetadata =
      L1ConsensusMetadata(context = context, txs = Map.empty, facilitators = None, roundId = None)
  }
}
