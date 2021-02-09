package org.tessellation.schema

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all._
import cats.{Applicative, Traverse}
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{AlgebraM, CoalgebraM}
import io.chrisdavenport.fuuid.FUUID
import monocle.Monocle.some
import monocle.macros.syntax.lens._
import org.tessellation.schema.L1Consensus.{BroadcastProposalResponse, Peer}

import scala.util.Random

// run: Ω => Ω

sealed trait L1ConsensusF[A] extends Hom[Ω, A]

case class L1Transaction(a: Int) extends Ω

case class L1Block(a: Int) extends Ω

case class L1Edge[A](txs: Set[L1Transaction]) extends L1ConsensusF[A]

case class BroadcastProposal[A]() extends L1ConsensusF[A]

case class ConsensusEnd[A](responses: List[BroadcastProposalResponse]) extends L1ConsensusF[A]

case class ReceiveProposal[A](txs: Set[L1Transaction]) extends L1ConsensusF[A]

case class ProposalResponse[A](txs: Set[L1Transaction]) extends L1ConsensusF[A] // output as facilitator
// ?? - output as owner

object L1ConsensusF {

  implicit val traverse: Traverse[L1ConsensusF] = new DefaultTraverse[L1ConsensusF] {
    override def traverse[G[_] : Applicative, A, B](fa: L1ConsensusF[A])(f: A => G[B]): G[L1ConsensusF[B]] =
      fa.asInstanceOf[L1ConsensusF[B]].pure[G]
  }
}



object L1Consensus {
  type Peer = String
  type StateM[A] = StateT[IO, L1ConsensusMetadata, A]
  val coalgebra: CoalgebraM[StateM, L1ConsensusF, Ω] = CoalgebraM {
    case L1Edge(txs) => generateRoundId() >> storeTransactions(txs) >> selectFacilitators(2) >> StateT { metadata =>
      IO {
        (metadata, BroadcastProposal())
      }
    }
    case BroadcastProposal() => broadcastProposal() >>= { responses =>
      StateT { metadata =>
        IO {
          (metadata, ConsensusEnd(responses))
        }
      }
    }

    case ReceiveProposal(txs) => StateT { metadata =>
      // send to others, fold, and then:
      val responses: Set[L1Transaction] = Set.empty
      IO {
        (metadata, ProposalResponse(txs + responses))
      }
    }


  }
  val algebra: AlgebraM[StateM, L1ConsensusF, Ω] = AlgebraM {
    //      case CreateBlock(data) => StateT { metadata =>
    //        IO {
    //          (metadata, L1Block(data.getOrElse(-1))) // TODO: Option ?
    //        }
    //      }

    case cmd: Ω => StateT { metadata =>
      IO {
        (metadata, cmd)
      }
    }
  }

  def broadcastProposal(): StateM[List[BroadcastProposalResponse]] = StateT { metadata =>
    def apiCall(request: BroadcastProposalRequest): IO[BroadcastProposalResponse] = IO {
      // scheme.hylo(L1.coalg,..).apply((initialState, ReceiveProposal(request.proposal)) //> (metadata, ProposalResponse(txs))
      BroadcastProposalResponse(request.roundId, request.proposal, Set.empty)
    }

    val r = for {
      request <- metadata.roundId.map(BroadcastProposalRequest(_, metadata.txs))
      facilitators <- metadata.facilitators.map(_.toList)
      responses <- facilitators.traverse(_ => apiCall(request)).some
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
      (metadata.lens(_.txs).set(txs), ())
    }
  }

  def selectFacilitators(n: Int = 2): StateM[Unit] = StateT { metadata =>
    IO {
      Random.shuffle(metadata.peers).take(n)
    }
      .map(facilitators => (metadata.lens(_.facilitators).set(facilitators.some), ()))
  }

  case class BroadcastProposalRequest(roundId: FUUID, proposal: Set[L1Transaction])

  case class BroadcastProposalResponse(roundId: FUUID, senderProposals: Set[L1Transaction], receiverProposals: Set[Transaction])

  case class L1ConsensusMetadata(
                                  txs: Set[L1Transaction],
                                  facilitators: Option[Set[Peer]],
                                  peers: Set[Peer], // Pass via semi-DI (initial state / input to hylo)
                                  roundId: Option[FUUID]
                                )

  object L1ConsensusMetadata {
    val empty = L1ConsensusMetadata(txs = Set.empty, facilitators = None, roundId = None, peers = Set.empty)
  }

  //  type Peer = String
  //  type Proposal = Int
  //
  //  case class L1ConsensusMetadata(
  //    txs: Set[L1Transaction],
  //    facilitators: Option[Set[Peer]]
  //  )
  //
  //  object L1ConsensusMetadata {
  //    val empty = L1ConsensusMetadata(txs = Set.empty, facilitators = None)
  //  }
  //
  //  def apiCall(): IO[Int] = {
  //    IO { Random.nextInt(10) }
  //  }
  //
  //  def getPeers(): IO[Set[Peer]] = IO.pure {
  //    Set.tabulate(10)(n => s"node$n")
  //  }
  //
  //  def gatherProposals(facilitators: Set[Peer]): IO[Set[(Peer, Proposal)]] =
  //    facilitators.toList.traverse(peer => IO { Random.nextInt(10) }.map(i => (peer, i))).map(_.toSet)
  //
  //  def selectFacilitators(peers: Set[Peer]): Set[Peer] = Random.shuffle(peers.toSeq).take(2).toSet
  //
  //  type StateM[A] = StateT[IO, L1ConsensusMetadata, A]
  //
  //  val coalgebra: CoalgebraM[StateM, L1ConsensusF, Ω] = CoalgebraM {
  //    case L1Edge(txs) => StateT { metadata =>
  //      IO { (metadata.lens(_.txs).set(txs), SelectFacilitators()) }
  //    }
  //
  //    case SelectFacilitators() => StateT { metadata =>
  //      getPeers().map(selectFacilitators).map { facilitators =>
  //        (metadata.lens(_.facilitators).set(facilitators.some), GatherProposals())
  //      }
  //    }
  //
  //    case GatherProposals() => StateT { metadata =>
  //      // TODO: handle error - currently forced option: .get.get
  //      gatherProposals(metadata.lens(_.facilitators).get.get).map { proposals =>
  //        (metadata, ProposalResponses(proposals))
  //      }
  //    }
  //
  //    case ProposalResponses(responses) => StateT { metadata =>
  //      val proposals = responses.map { case (_, proposal) => proposal }
  //      val cmd: L1ConsensusF[Ω] = if (proposals.isEmpty) CreateBlock(none[Int]) else CreateBlock(proposals.sum.some)
  //      IO { (metadata, cmd) }
  //    }
  //  }
  //
  //  val algebra: AlgebraM[StateM, L1ConsensusF, Ω] = AlgebraM {
  //    case CreateBlock(data) => StateT { metadata =>
  //      IO {
  //        (metadata, L1Block(data.getOrElse(-1))) // TODO: Option ?
  //      }
  //    }
  //
  //    case cmd: Ω => StateT { metadata =>
  //      IO { (metadata, cmd) }
  //    }
  //  }

}
