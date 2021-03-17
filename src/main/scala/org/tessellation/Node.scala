package org.tessellation

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Ref
import cats.syntax.all._
import org.tessellation.consensus.{L1Cell, L1Transaction, L1TransactionPool, ReceiveProposal, StartOwnRound}
import org.tessellation.consensus.L1ConsensusStep.{L1ConsensusContext}
import org.tessellation.consensus.L1TransactionPool.L1TransactionPoolEnqueue
import org.tessellation.schema.{CellError, Ω}

import scala.concurrent.duration.DurationInt
import scala.util.Random

case class Node(id: String, txPool: L1TransactionPoolEnqueue) {
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
      context = L1ConsensusContext(peer = this, peers = peers, txPool = txPool)
      ohm <- cell.run(context, ReceiveProposal(_))
    } yield ohm

  def startL1Consensus(cell: L1Cell): IO[Either[CellError, Ω]] =
    for {
      _ <- rounds.modify(n => (n + 1, ()))
      peers <- peers.get
      context = L1ConsensusContext(peer = this, peers = peers, txPool = txPool)
      _ <- IO.sleep(1.second)(IO.timer(scala.concurrent.ExecutionContext.global))
      ohm <- cell.run(context, StartOwnRound(_))
      _ <- rounds.modify(n => (n - 1, ()))
    } yield ohm
}

object Node {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def run(id: String): IO[Node] =
    for {
      txPool <- generateRandomTxPool(id)
      node <- IO.pure {
        Node(id, txPool)
      }
    } yield node

  private def generateRandomTxPool(id: String): IO[L1TransactionPoolEnqueue] =
    List(1, 2, 3)
      .traverse(
        _ =>
          IO.delay {
            Random.nextInt(Integer.MAX_VALUE)
          }.map(a => L1Transaction(a, "a", "b", ""))
      )
      .map(_.toSet) >>= L1TransactionPool.init
}
