package org.tessellation

import cats.effect.{ContextShift, IO}
import cats.effect.concurrent.Ref
import cats.syntax.all._
import higherkindness.droste.scheme
import org.tessellation.RunExample.input
import org.tessellation.schema.L1Consensus.{L1ConsensusContext, L1ConsensusMetadata}
import org.tessellation.schema.L1TransactionPool.L1TransactionPoolEnqueue
import org.tessellation.schema.{L1Edge, L1Transaction, L1TransactionPool, StackL1Consensus}

import scala.util.Random

case class Node(id: String, txPool: L1TransactionPoolEnqueue) {
  private val peers = Ref.unsafe[IO, Set[Node]](Set.empty[Node])

  def joinTo(nodes: Set[Node]): IO[Unit] =
    nodes.toList.traverse(joinTo).void

  def joinTo(node: Node): IO[Unit] =
    node.updatePeers(this) >> updatePeers(node)

  def updatePeers(node: Node): IO[Unit] =
    peers.modify(p => (p + node, ()))

  def startL1Consensus(edge: L1Edge[L1Transaction]): IO[Unit] =
    for {
      peers <- peers.get
      context = L1ConsensusContext(peer = this, peers = peers, txPool = txPool)
      initialState = L1ConsensusMetadata.empty(context)
      run <- scheme.hyloM(StackL1Consensus.algebra, StackL1Consensus.coalgebra).apply((initialState, input))
    } yield ()
}

object Node {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def run(id: String): IO[Node] =
    for {
      txPool <- generateRandomTxPool
      node <- IO.pure {
        Node(id, txPool)
      }
    } yield node

  private def generateRandomTxPool: IO[L1TransactionPoolEnqueue] =
    List(1, 2, 3)
      .traverse(
        _ =>
          IO.delay {
            Random.nextInt(Integer.MAX_VALUE)
          }.map(L1Transaction)
      )
      .map(_.toSet) >>= L1TransactionPool.init
}
