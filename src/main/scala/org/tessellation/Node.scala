package org.tessellation

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import org.tessellation.consensus.L1ConsensusStep.L1ConsensusContext
import org.tessellation.consensus.{L1Cell, ReceiveProposal, StartOwnRound}
import org.tessellation.schema.{CellError, Ω}

import scala.concurrent.duration.DurationInt

case class Node(id: String) {
  private val peers = Ref.unsafe[IO, Set[Node]](Set.empty[Node])
  private val rounds = Ref.unsafe[IO, Int](0)

  def countRoundsInProgress: IO[Int] = rounds.get

  def joinTo(nodes: Set[Node]): IO[Unit] =
    nodes.toList.traverse(joinTo).void

  def joinTo(node: Node): IO[Unit] =
    node.updatePeers(this) >> updatePeers(node)

  def updatePeers(node: Node): IO[Unit] =
    peers.modify(p => (p + node, ()))

  def participateInL1Consensus(cell: L1Cell): IO[Either[CellError, Ω]] =
    for {
      peers <- peers.get
      context = L1ConsensusContext(peer = this, peers = peers)
      ohm <- cell.run(context, ReceiveProposal(_))
    } yield ohm

  def startL1Consensus(cell: L1Cell): IO[Either[CellError, Ω]] =
    for {
      _ <- rounds.modify(n => (n + 1, ()))
      peers <- peers.get
      context = L1ConsensusContext(peer = this, peers = peers)
      _ <- IO.sleep(1.second)(IO.timer(scala.concurrent.ExecutionContext.global))
      ohm <- cell.run(context, StartOwnRound(_))
      _ <- rounds.modify(n => (n - 1, ()))
    } yield ohm
}

object Node {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def run(id: String): IO[Node] =
    for {
      node <- IO.pure {
        Node(id)
      }
    } yield node
}
