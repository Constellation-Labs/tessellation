package org.tesselation.infrastructure.trust

case class TrustEdge(src: Int, dst: Int, trust: Double, isLabel: Boolean = false) {
  def other(id: Int): Int = Seq(src, dst).filterNot(_ == id).head
}

// Simple way to simulate modularity of connections / generate a topology different from random
case class TrustNode(id: Int, xCoordinate: Double, yCoordinate: Double, edges: Seq[TrustEdge] = Seq()) {

  def distance(other: TrustNode): Double =
    Math.sqrt {
      Math.pow(xCoordinate - other.xCoordinate, 2) +
        Math.pow(yCoordinate - other.yCoordinate, 2)
    } / Math.sqrt(2.0)

  def negativeEdges: Seq[TrustEdge] = edges.filter(_.trust < 0)

  def normalizedPositiveEdges(visited: Set[Int]): Map[Int, Double] = {
    val positiveSubset = positiveEdges.filterNot { e =>
      visited.contains(e.dst)
    }
    if (positiveSubset.isEmpty) Map.empty[Int, Double]
    else {
      val total = positiveSubset.map {
        _.trust
      }.sum
      val normalized = positiveSubset.map { edge =>
        edge.dst -> (edge.trust / total)
      }.toMap
      // println("Normalized sum: " + normalized.values.sum)
      normalized
    }
  }

  def positiveEdges: Seq[TrustEdge] = edges.filter(_.trust > 0)

}
