package org.tessellation.consensus

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.chrisdavenport.fuuid.FUUID
import org.tessellation.consensus.L1TransactionPool.L1TransactionPoolEnqueue
import org.tessellation.schema.Ω
import org.tessellation.{Log, Node}
import monocle.macros.syntax.lens._

import scala.util.Random

object L1ConsensusStep {
  type Peer = String
  type StateM[A] = StateT[IO, L1ConsensusMetadata, A]
  implicit val contextShift = IO.contextShift(scala.concurrent.ExecutionContext.global)

  // TODO: Use Reader monad -> Reader[L1ConsensusContext, Ω]
  val coalgebra: CoalgebraM[StateM, L1ConsensusF, Ω] = CoalgebraM {

    // TODO: Use StartOwnRound
    case StartOwnRound(edge) =>
      generateRoundId() >> storeTransactions(edge.txs) >> selectFacilitators(2) >> StateT[
        IO,
        L1ConsensusMetadata,
        L1ConsensusF[Ω]
      ] { metadata =>
        IO {
          Log.logNode(metadata.context.peer)(
            s"[${metadata.context.peer.id}][L1Edge] Stored transactions: ${edge.txs.toList.sortBy(_.a)}"
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

    case ReceiveProposal(edge) =>
      storeTransactions(edge.txs) >>
        StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
          IO {
            val txs = edge.txs
            val roundId = metadata.roundId.get
            val state = metadata.lens(_.txs).modify { t =>
              t.updatedWith(roundId) {
                case Some(mapping) => (mapping + (metadata.context.peer -> txs)).some
                case None          => Map[Node, Set[L1Transaction]](metadata.context.peer -> txs).some
              }
            }

            Log.logNode(metadata.context.peer)(
              s"[${metadata.context.peer.id}][ReceiveProposal] Stored transaction (pull): ${state.txs
                .get(roundId)
                .flatMap(_.get(metadata.context.peer).map(_.toList.sortBy(_.a)))}"
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

  val algebra: AlgebraM[StateM, L1ConsensusF, Either[L1ConsensusError, Ω]] = AlgebraM {
    case ConsensusEnd(responses) =>
      StateT { metadata =>
        IO {
          val txs = responses.map(_.receiverProposals).foldRight(Set.empty[L1Transaction])(_ ++ _)
          (metadata, L1Block(txs).asRight[L1ConsensusError])
        }
      }

    case L1Error(reason) =>
      StateT { metadata =>
        IO {
          (metadata, L1ConsensusError(reason).asLeft[Ω])
        }
      }

    case cmd: Ω =>
      StateT { metadata =>
        IO {
          (metadata, cmd.asRight[L1ConsensusError])
        }
      }
  }
  val hyloM: Ω => StateM[Either[L1ConsensusError, Ω]] = scheme.hyloM(L1ConsensusStep.algebra, L1ConsensusStep.coalgebra)

  def pullTxs(n: Int): StateM[Set[L1Transaction]] = StateT { metadata =>
    metadata.context.txPool
      .pull(metadata.roundId.get, n)
      .map(txs => (metadata, txs))
  }

  def broadcastProposal(): StateM[Either[L1ConsensusError, List[BroadcastProposalResponse]]] = StateT { metadata =>
    // TODO: apiCall peer should be taken from node
    def apiCall(
      request: BroadcastProposalRequest,
      from: Node,
      context: L1ConsensusContext
    ): IO[BroadcastProposalResponse] = {
      val initialState = L1ConsensusMetadata(
        context = context,
        txs = Map(metadata.roundId.get -> Map(from -> request.proposal)),
        facilitators = request.facilitators.some,
        roundId = request.roundId.some
      )
      Log.logNode(context.peer)(
        s"\n[${context.peer.id}] InitialState: facilitators=${initialState.facilitators}, txs=${initialState.txs}"
      )
      val input = ReceiveProposal(L1Edge(Set.empty)) // TODO: cell cache

      scheme.hyloM(L1Consensus.algebra, L1Consensus.coalgebra).apply((initialState, input)).flatMap {
        case Right(ProposalResponse(txs)) => {
          Log.logNode(metadata.context.peer)(
            s"[${metadata.context.peer.id}][ProposalResponse] ${txs.toList.sortBy(_.a)}"
          )
          IO { BroadcastProposalResponse(request.roundId, request.proposal, txs) }
        }
        case Left(L1ConsensusError(reason)) => {
          // TODO: in case of other flow in algebras, handle error
          Log.red("unexpected")
          IO.raiseError(L1ConsensusError(reason))
        }
      }
    }

    val r = for {
      facilitators <- metadata.facilitators.map(_.filterNot(_ == metadata.context.peer))
      _ <- Option {
        Log.logNode(metadata.context.peer)(
          s"[${metadata.context.peer.id}] [BroadcastProposal] Facilitators: ${facilitators}"
        )
        ()
      }
      txs <- metadata.roundId.flatMap(metadata.txs.get).flatMap(_.get(metadata.context.peer))
      request <- metadata.roundId.map(BroadcastProposalRequest(_, txs, facilitators))
      responses <- facilitators.toList.parTraverse { facilitator =>
        (for {
          proposalResponse <- apiCall(
            request,
            metadata.context.peer,
            metadata.context
              .lens(_.peer)
              .set(facilitator)
              .lens(_.txPool)
              .set(metadata.context.peers.find(_ == facilitator).get.txPool) // TODO: peers.find(..).get
          )
        } yield proposalResponse).attempt.map(_.leftMap(e => L1ConsensusError(e.getMessage)))
      }.some
    } yield responses

    // TODO: handle Either instead of Option above
    r.sequence
      .map(_.map(_.sequence).getOrElse(List.empty.asRight[L1ConsensusError]))
      .map((metadata, _))

  }

  def generateRoundId(): StateM[Unit] = StateT { metadata =>
    FUUID
      .randomFUUID[IO]
      .map(_.some)
      .map(o => (metadata.lens(_.roundId).set(o), ()))
  }

  def storeTransactions(txs: Set[L1Transaction]): StateM[Unit] = StateT { metadata =>
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

  def selectFacilitators(n: Int = 2): StateM[Unit] = StateT { metadata =>
    IO {
      Random.shuffle(metadata.context.peers).take(n)
    }.map(facilitators => (metadata.lens(_.facilitators).set(facilitators.some), ()))
  }

  case class L1ConsensusError(reason: String) extends Throwable(reason)

  case class BroadcastProposalRequest(roundId: FUUID, proposal: Set[L1Transaction], facilitators: Set[Node])

  case class BroadcastProposalResponse(
    roundId: FUUID,
    senderProposals: Set[L1Transaction],
    receiverProposals: Set[L1Transaction]
  )

  case class L1ConsensusContext(
    peer: Node,
    peers: Set[Node],
    txPool: L1TransactionPoolEnqueue
  )

  case class L1ConsensusMetadata(
    context: L1ConsensusContext,
    txs: Map[FUUID, Map[Node, Set[L1Transaction]]], // TODO: @mwadon - why FUUID?
    facilitators: Option[Set[Node]],
    roundId: Option[FUUID]
  )

  object L1ConsensusMetadata {

    def empty(context: L1ConsensusContext) =
      L1ConsensusMetadata(context = context, txs = Map.empty, facilitators = None, roundId = None)
  }
}
