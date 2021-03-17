package org.tessellation

import cats.effect.concurrent.Ref
import cats.effect.{ContextShift, IO}
import cats.syntax.all._
import io.chrisdavenport.fuuid.FUUID
import org.tessellation.consensus.L1ConsensusStep.L1ConsensusContext
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.consensus.{L1Cell, L1Edge, L1Transaction, ReceiveProposal, StartOwnRound}
import org.tessellation.schema.{CellError, Ω}

import scala.concurrent.duration.DurationInt

case class Node(id: String, txGenerator: RandomTransactionGenerator) {
  private val peers = Ref.unsafe[IO, Set[Node]](Set.empty[Node])

  def joinTo(nodes: Set[Node]): IO[Unit] =
    nodes.toList.traverse(joinTo).void

  def joinTo(node: Node): IO[Unit] =
    node.updatePeers(this) >> updatePeers(node)

  def updatePeers(node: Node): IO[Unit] =
    peers.modify(p => (p + node, ()))

  def participateInL1Consensus(
     roundId: FUUID,
     proposalNode: Node,
     receiverProposal: L1Edge,
     cachedCell: L1Cell
  ): IO[Either[CellError, Ω]] =
    for {
      peers <- peers.get
      context = L1ConsensusContext(peer = this, peers = peers, txGenerator = txGenerator)
      ohm <- cachedCell.run(context, ownProposal => ReceiveProposal(roundId, proposalNode, receiverProposal, ownProposal))
    } yield ohm

  def startL1Consensus(cell: L1Cell): IO[Either[CellError, Ω]] =
    for {
      peers <- peers.get
      context = L1ConsensusContext(peer = this, peers = peers, txGenerator = txGenerator)
      _ <- IO.sleep(1.second)(IO.timer(scala.concurrent.ExecutionContext.global))
      ohm <- cell.run(context, StartOwnRound(_))
    } yield ohm
}

object Node {
  implicit val contextShift: ContextShift[IO] = IO.contextShift(scala.concurrent.ExecutionContext.global)

  def run(id: String, txSrc: String): IO[Node] =
    for {
      node <- IO.pure {
        Node(id, RandomTransactionGenerator(id, Some(txSrc)))
      }
    } yield node
}
