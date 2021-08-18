package org.tessellation.consensus

import cats.syntax.all._
import cats.{Applicative, Traverse}
import higherkindness.droste.util.DefaultTraverse
import org.tessellation.consensus.L1ConsensusStep.{BroadcastProposalResponse, RoundId}
import org.tessellation.schema.{Hom, Ω}

case class L1Transaction(
  a: Int,
  src: String,
  dst: String,
  parentHash: String = "",
  ordinal: Int = 0
) extends Ω {
  val hash = s"$a$src$dst$ordinal$parentHash"

  override def toString: String =
    s"L1Transaction($hash)"
}

object L1Transaction {}

case class L1Edge(txs: Set[L1Transaction]) extends Ω

case class L1Block(txs: Set[L1Transaction]) extends Ω {

  def height: Int =
    txs.maxByOption(_.a).map(_.a).getOrElse(0) // TODO: height should be based on block parents (parents + 1)
}

sealed trait L1ConsensusF[A] extends Hom[Ω, A]

/**
  * Input as owner
  */
case class StartOwnRound[A](edge: L1Edge) extends L1ConsensusF[A]

/**
  * Input as facilitator
  */
case class ReceiveProposal[A](
  roundId: RoundId,
  senderId: String,
  proposal: L1Edge,
  ownEdge: L1Edge
) extends L1ConsensusF[A]

case class BroadcastProposal[A]() extends L1ConsensusF[A]

case class BroadcastReceivedProposal[A]() extends L1ConsensusF[A]

/**
  * Output - error
  */
case class L1Error[A](reason: Throwable) extends L1ConsensusF[A]

/**
  * Output from coalgebra to algebra to create a block
  */
case class ConsensusEnd[A](responses: List[BroadcastProposalResponse]) extends L1ConsensusF[A]

/**
  * Output as facilitator
  */
case class ProposalResponse[A](txs: Set[L1Transaction]) extends L1ConsensusF[A]

object L1ConsensusF {
  implicit val traverse: Traverse[L1ConsensusF] = new DefaultTraverse[L1ConsensusF] {
    override def traverse[G[_]: Applicative, A, B](fa: L1ConsensusF[A])(f: A => G[B]): G[L1ConsensusF[B]] =
      fa.asInstanceOf[L1ConsensusF[B]].pure[G]
  }
}
