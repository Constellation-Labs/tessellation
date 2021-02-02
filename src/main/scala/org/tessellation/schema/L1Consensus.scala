package org.tessellation.schema

import cats.data.StateT
import cats.effect.IO
import cats.syntax.all._
import cats.{Applicative, Traverse}
import higherkindness.droste.util.DefaultTraverse
import higherkindness.droste.{AlgebraM, CoalgebraM}
import monocle.Monocle.some
import monocle.macros.syntax.lens._
import org.tessellation.schema.L1Consensus.{Peer, Proposal}

import scala.util.Random

// run: Ω => Ω

sealed trait L1ConsensusF[A]

case class L1Transaction(a: Int) extends Ω
case class L1Block(a: Int) extends Ω

// select facilitators
// gather proposals
// validate responses

case class L1Edge[A](txs: Set[L1Transaction]) extends L1ConsensusF[A] with Ω
case class SelectFacilitators[A]() extends L1ConsensusF[A] with Ω
case class GatherProposals[A]() extends L1ConsensusF[A] with Ω
case class ProposalResponses[A](responses: Set[(Peer, Proposal)]) extends L1ConsensusF[A] with Ω

object L1ConsensusF {

  implicit val traverse: Traverse[L1ConsensusF] = new DefaultTraverse[L1ConsensusF] {
    override def traverse[G[_]: Applicative, A, B](fa: L1ConsensusF[A])(f: A => G[B]): G[L1ConsensusF[B]] =
      fa match {
        case L1Edge(txs) => (L1Edge(txs): L1ConsensusF[B]).pure[G]
        case SelectFacilitators() => (SelectFacilitators(): L1ConsensusF[B]).pure[G]
        case GatherProposals() => (GatherProposals(): L1ConsensusF[B]).pure[G]
        case ProposalResponses(responses) => (ProposalResponses(responses): L1ConsensusF[B]).pure[G]
      }
  }

}

object L1Consensus {
  type Peer = String
  type Proposal = Int

  case class L1ConsensusMetadata(
    txs: Set[L1Transaction],
    facilitators: Option[Set[Peer]]
  )

  object L1ConsensusMetadata {
    val empty = L1ConsensusMetadata(txs = Set.empty, facilitators = None)
  }

  def apiCall(): IO[Int] = {
    IO { Random.nextInt(10) }
  }

  def getPeers(): IO[Set[Peer]] = IO.pure {
    Set.tabulate(10)(n => s"node$n")
  }

  def gatherProposals(facilitators: Set[Peer]): IO[Set[(Peer, Proposal)]] =
    facilitators.toList.traverse(peer => IO { Random.nextInt(10) }.map(i => (peer, i))).map(_.toSet)

  def selectFacilitators(peers: Set[Peer]): Set[Peer] = Random.shuffle(peers.toSeq).take(2).toSet

  type StateM[A] = StateT[IO, L1ConsensusMetadata, A]

  val coalgebra: CoalgebraM[StateM, L1ConsensusF, Ω] = CoalgebraM {
    case L1Edge(txs) => StateT { metadata =>
      apiCall().map { int =>
        (metadata.lens(_.txs).set(txs ++ Set(L1Transaction(int))), SelectFacilitators())
      }
    }

    case SelectFacilitators() => StateT { metadata =>
      getPeers().map(selectFacilitators).map { facilitators =>
        (metadata.lens(_.facilitators).set(facilitators.some), GatherProposals())
      }
    }

    case GatherProposals() => StateT { metadata =>
      // TODO: handle error - currently forced option: .get.get
      gatherProposals(metadata.lens(_.facilitators).get.get).map { proposals =>
        (metadata, ProposalResponses(proposals))
      }
    }
  }


  val algebra: AlgebraM[StateM, L1ConsensusF, Ω] = AlgebraM {
    case SelectFacilitators() => StateT { metadata =>
      IO { (metadata, SelectFacilitators()) }
    }

    case GatherProposals() => StateT { metadata =>
      IO { (metadata, GatherProposals() )}
    }

    case ProposalResponses(responses) => StateT { metadata =>
      IO {
        (metadata, L1Block(responses.map(_._2).sum))
      }
    }
  }

}
