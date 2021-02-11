package org.tessellation.schema

import cats.data.StateT
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, IO}
import cats.syntax.all._
import cats.{Applicative, Traverse}
import fs2.concurrent.Queue
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import io.chrisdavenport.fuuid.FUUID
import monocle.macros.syntax.lens._
import monocle.syntax.apply._
import org.tessellation.schema.L1Consensus.BroadcastProposalResponse
import org.tessellation.schema.L1TransactionPool.L1TransactionPoolEnqueue

import scala.util.Random

// run: Ω => Ω

sealed trait L1ConsensusF[A] extends Hom[Ω, A]

case class L1Transaction(a: Int) extends Ω

case class L1Block(a: Int) extends Ω

case class L1Edge[A](txs: Set[L1Transaction]) extends L1ConsensusF[A]

case class BroadcastProposal[A]() extends L1ConsensusF[A]

case class BroadcastReceivedProposal[A]() extends L1ConsensusF[A]

case class ConsensusEnd[A](responses: List[BroadcastProposalResponse]) extends L1ConsensusF[A]

case class ReceiveProposal[A]() extends L1ConsensusF[A]

case class ProposalResponse[A](txs: Set[L1Transaction]) extends L1ConsensusF[A] // output as facilitator
// ?? - output as owner

object L1ConsensusF {
  implicit val traverse: Traverse[L1ConsensusF] = new DefaultTraverse[L1ConsensusF] {
    override def traverse[G[_] : Applicative, A, B](fa: L1ConsensusF[A])(f: A => G[B]): G[L1ConsensusF[B]] =
      fa.asInstanceOf[L1ConsensusF[B]].pure[G]
  }
}

object L1TransactionPool {
  def apply[F[_]](ref: Ref[F, Set[L1Transaction]])(implicit F: Concurrent[F]): F[L1TransactionPoolEnqueue[F]] = {
    F.delay {
      new L1TransactionPoolEnqueue[F] {
        def enqueue(tx: L1Transaction): F[Unit] =
          ref.modify(txs => (txs + tx, ()))

        def dequeue(n: Int): F[Set[L1Transaction]] =
          ref.modify { txs =>
            val taken: Set[L1Transaction] = txs.take(n)

            (txs -- taken, taken)
          }
      }
    }
  }

  trait L1TransactionPoolEnqueue[F[_]] {
    def enqueue(tx: L1Transaction): F[Unit]

    def dequeue(n: Int): F[Set[L1Transaction]]
  }

}

object L1Consensus {
  type Peer = String
  type StateM[A] = StateT[IO, L1ConsensusMetadata, A]

  // TODO: Use Reader monad -> Reader[L1ConsensusContext, Ω]
  val coalgebra: CoalgebraM[StateM, L1ConsensusF, Ω] = CoalgebraM {
    case L1Edge(txs) => generateRoundId() >> storeTransactions(txs) >> selectFacilitators(2) >> StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
      IO {
        println(s"[${metadata.context.peer}][L1Edge] Stored transactions: ${txs.toList.sortBy(_.a)}")
        (metadata, BroadcastProposal())
      }
    }

    case BroadcastProposal() => broadcastProposal() >>= { responses =>
      StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
        IO {
          (metadata, ConsensusEnd(responses))
        }
      }
    }

      // A ---> B
      // A ---> C

      // B (A) --- b ---> C

    case ReceiveProposal() => pullTxs(2) >>= { txs =>
      StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
        IO {
          val roundId = metadata.roundId.get
          val state = metadata.lens(_.txs).modify { t =>
            t.updatedWith(roundId) {
              case Some(mapping) => (mapping + (metadata.context.peer -> txs)).some
              case None => Map[Peer, Set[L1Transaction]](metadata.context.peer -> txs).some
            }
          }

          println(s"[${metadata.context.peer}][ReceiveProposal] Stored transaction (pull): ${state.txs.get(roundId).flatMap(_.get(metadata.context.peer).map(_.toList.sortBy(_.a)))}")

          (state, BroadcastReceivedProposal())
        }
      }
    }

    case BroadcastReceivedProposal() => broadcastProposal() >>= { responses =>
      StateT[IO, L1ConsensusMetadata, L1ConsensusF[Ω]] { metadata =>
        IO {
          val roundTxs = metadata.roundId
            .flatMap(metadata.txs.get)

          val txs = roundTxs.map(_.values.flatten.toSet).getOrElse(Set.empty[L1Transaction])
          val resTxs = responses.flatMap(_.receiverProposals).toSet
          // TODO: We can't send ProposalResponse because C can get proposal from B before proposal A reaches C! (Race condition)
          (metadata, ProposalResponse(txs ++ resTxs))
        }
      }
    }
  }

  val algebra: AlgebraM[StateM, L1ConsensusF, Ω] = AlgebraM {
    case cmd: Ω => StateT { metadata =>
      IO {
        (metadata, cmd)
      }
    }
  }

  val hyloM = scheme.hyloM(L1Consensus.algebra, L1Consensus.coalgebra)

  def pullTxs(n: Int): StateM[Set[L1Transaction]] = StateT { metadata =>
    metadata.roundId
      .flatMap(metadata.txs.get) // TODO: We should persist the state and get from state as metadata is not shared across hylos
      .flatMap(_.get(metadata.context.peer))
      .map(IO.pure)
      .getOrElse(metadata.context.txPool.dequeue(n))
      .map(txs => (metadata, txs))
  }

  def broadcastProposal(): StateM[List[BroadcastProposalResponse]] = StateT { metadata =>
    // TODO: apiCall peer should be taken from node
    def apiCall(request: BroadcastProposalRequest, from: Peer, context: L1ConsensusContext): IO[BroadcastProposalResponse] = {
      val initialState = L1ConsensusMetadata(
        context = context,
        txs = Map(metadata.roundId.get -> Map(from -> request.proposal)),
        facilitators = request.facilitators.some,
        roundId = request.roundId.some
      )
      println(s"\n[${context.peer}] InitialState: facilitators=${initialState.facilitators}, txs=${initialState.txs}")
      val input = ReceiveProposal()

      scheme.hyloM(StackL1Consensus.algebra, StackL1Consensus.coalgebra).apply((initialState, input)).map {
        case result@ProposalResponse(txs) => {
          println(s"[${metadata.context.peer}][ProposalResponse] ${txs.toList.sortBy(_.a)}")
          BroadcastProposalResponse(request.roundId, request.proposal, txs)
        }
        case _ => {
          println("unexpected")
          ???
        } // TODO: in case of other flow in algebras, handle error
      }
    }

    val r = for {
      facilitators <- metadata.facilitators.map(_.filterNot(_ == metadata.context.peer))
      _ <- Option({
        println(s"[${metadata.context.peer}][BroadcastProposal] Facilitators: ${facilitators}")
        ()
      })
      txs <- metadata.roundId.flatMap(metadata.txs.get).flatMap(_.get(metadata.context.peer))
      request <- metadata.roundId.map(BroadcastProposalRequest(_, txs, facilitators))
      responses <- facilitators.toList.traverse(facilitator => apiCall(request, metadata.context.peer, metadata.context.lens(_.peer).set(facilitator))).some
    } yield responses

    r.sequence.map(_.getOrElse(List.empty)).map((metadata, _))

  }

  def generateRoundId(): StateM[Unit] = StateT { metadata =>
    FUUID.randomFUUID[IO]
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
    }
      .map(facilitators => (metadata.lens(_.facilitators).set(facilitators.some), ()))
  }

  case class BroadcastProposalRequest(roundId: FUUID, proposal: Set[L1Transaction], facilitators: Set[Peer])

  case class BroadcastProposalResponse(roundId: FUUID, senderProposals: Set[L1Transaction], receiverProposals: Set[L1Transaction])

  case class L1ConsensusContext(
    peer: Peer,
    peers: Set[Peer],
    txPool: L1TransactionPoolEnqueue[IO]
  )

  case class L1ConsensusMetadata(
                                  context: L1ConsensusContext,
                                  txs: Map[FUUID, Map[Peer, Set[L1Transaction]]],
                                  facilitators: Option[Set[Peer]],
                                  roundId: Option[FUUID]
                                )

  object L1ConsensusMetadata {
    def empty(context: L1ConsensusContext) =
      L1ConsensusMetadata(context = context, txs = Map.empty, facilitators = None, roundId = None)
  }
}
