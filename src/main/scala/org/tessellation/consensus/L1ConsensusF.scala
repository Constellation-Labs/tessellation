package org.tessellation.consensus

import cats.{Applicative, Traverse}
import cats.syntax.all._
import higherkindness.droste.util.DefaultTraverse
import L1ConsensusStep.BroadcastProposalResponse
import org.tessellation.schema.{Hom, Ω}

import java.util.Calendar

case class L1Transaction(
  a: Int,
  src: String,
  dst: String,
  parentHash: String = "",
  ordinal: Int
) extends Ω {
  val hash = s"$a$src$dst${Calendar.getInstance.getTimeInMillis}"

  override def toString: String =
    s"Tx$ordinal($src -> $dst)"

  //    s"L1Transaction($ordinal: $src -> $dst, hash $hash, parentHash $parentHash)"
}

object L1Transaction {}

case class L1Edge[A](txs: Set[L1Transaction]) extends Ω

case class L1Block(txs: Set[L1Transaction]) extends Ω

sealed trait L1ConsensusF[A] extends Hom[Ω, A]

/**
  * Input as owner
  */
case class StartOwnRound[A](edge: L1Edge[L1Transaction]) extends L1ConsensusF[A]

/**
  * Input as facilitator
  */
case class ReceiveProposal[A](edge: L1Edge[L1Transaction]) extends L1ConsensusF[A]

case class BroadcastProposal[A]() extends L1ConsensusF[A]

case class BroadcastReceivedProposal[A]() extends L1ConsensusF[A]

/**
  * Output - error
  */
case class L1Error[A](reason: String) extends L1ConsensusF[A]

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
