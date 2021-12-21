package org.tessellation.trust

import cats.effect.std.Random
import cats.syntax.all._
import cats.{Applicative, Monad}

import org.tessellation.infrastructure.trust.{TrustEdge, TrustNode}

class DataGenerator[F[_]: Monad: Random] {

  def randomEdgeLogic(distance: Double): F[Boolean] =
    for {
      value1 <- Random[F].nextDouble
      value2 <- Random[F].nextDouble
    } yield {
      value1 > distance && value2 < 0.2
    }

  def randomEdge(logic: Double => F[Boolean] = randomEdgeLogic)(n: TrustNode, n2: TrustNode) =
    for {
      result <- logic(
        Math.sqrt(Math.pow(n.xCoordinate - n2.xCoordinate, 2) + Math.pow(n.yCoordinate - n2.yCoordinate, 2))
      )
      trustZeroToOne <- Random[F].nextDouble
    } yield {
      if (result) {
        Some(TrustEdge(n.id, n2.id, 2 * (trustZeroToOne - 0.5), isLabel = true))
      } else None
    }

  def randomPositiveEdge(
    logic: Double => F[Boolean] = randomEdgeLogic
  )(n: TrustNode, n2: TrustNode): F[Option[TrustEdge]] =
    for {
      result <- logic(
        Math.sqrt(Math.pow(n.xCoordinate - n2.xCoordinate, 2) + Math.pow(n.yCoordinate - n2.yCoordinate, 2))
      )
      trustZeroToOne <- Random[F].nextDouble
    } yield {
      if (result) {
        Some(TrustEdge(n.id, n2.id, trustZeroToOne, isLabel = true))
      } else None
    }

  def seedCliqueLogic(maxSeedNodeIdx: Int = 1)(id: Double): F[Boolean] =
    Applicative[F].pure(id <= maxSeedNodeIdx)

  def cliqueEdge(logic: Double => F[Boolean] = seedCliqueLogic())(n: TrustNode, n2: TrustNode): F[Option[TrustEdge]] =
    for {
      result <- logic(n.distance(n2))
      trustZeroToOne <- Random[F].nextDouble
    } yield
      if (result) Some(TrustEdge(n.id, n2.id, 1.0, isLabel = true))
      else {
        Some(TrustEdge(n.id, n2.id, 2 * (trustZeroToOne - 0.4)))
      }

  def randomTrustNode(id: Int): F[TrustNode] =
    for {
      x <- Random[F].nextDouble
      y <- Random[F].nextDouble
    } yield TrustNode(id, x, y)

  def generateData(
    numNodes: Int = 30,
    edgeLogic: (TrustNode, TrustNode) => F[Option[TrustEdge]] = randomEdge(),
    minimumEdgeCount: Int = 3
  ): F[List[TrustNode]] = {
    val nodes =
      (0 until numNodes).toList.map(id => TrustNode(id, scala.util.Random.nextDouble(), scala.util.Random.nextDouble()))
    val nodesWithEdges = nodes.traverse { n =>
      val edges = nodes.filterNot(_.id == n.id).traverse { n2 =>
        edgeLogic(n, n2)
      }
      edges.map(e => n.copy(edges = e.flatten))
    }
    nodesWithEdges
  }

  def bipartiteEdge(logic: Double => F[Boolean] = seedCliqueLogic())(n: TrustNode, n2: TrustNode) =
    for {
      result <- logic(n.id.toDouble)
    } yield
      if (result) Some(TrustEdge(n.id, n2.id, 1.0, isLabel = true))
      else Some(TrustEdge(n.id, n2.id, -1.0))
}
