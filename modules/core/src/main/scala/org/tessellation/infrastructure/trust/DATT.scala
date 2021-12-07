package org.tessellation.infrastructure.trust

/**
  * Directed Acyclic Transitive Trust Scores
  */
object DATT {

  def calculate[K](
    trust: Map[K, Map[K, Double]],
    selfPeerId: K,
    expansionThreshold: Double = 1e-7
  ): Map[K, Double] = {

    val selfTrust = trust(selfPeerId)
    val scores = scala.collection.mutable.HashMap.from[K, Double](selfTrust)
    // Scores always populated/available for current neighbor expansion
    var currentNeighborExpansion = scala.collection.mutable.HashSet.from(scores.keys)

    while (currentNeighborExpansion.nonEmpty) {
      val nextNeighborExpansion = scala.collection.mutable.HashSet.empty[K]
      val dotScores = scala.collection.mutable.HashMap.empty[K, scala.collection.mutable.HashMap[K, Double]]
      for (neighbor <- currentNeighborExpansion) {
        val neighborScore = scores(neighbor)
        if (neighborScore > expansionThreshold) {
          val outerLabels = trust(neighbor)
          outerLabels.foreach {
            case (outerNeighbor, v) =>
              if (!scores.contains(outerNeighbor)) {
                val outerTransitiveScore = neighborScore * v
                val dotUpdate = dotScores.getOrElse(outerNeighbor, scala.collection.mutable.HashMap.empty)
                dotUpdate.update(neighbor, outerTransitiveScore)
                dotScores(outerNeighbor) = dotUpdate
                nextNeighborExpansion.add(outerNeighbor)
              }
          }
        }
      }
      dotScores.foreach {
        case (k, v) =>
          val totalWeight = v.map { case (weightingPeer, _) => scores(weightingPeer) }.sum
          val weightedMean = v.map {
            case (weightingPeer, transitiveScore) =>
              val weight = scores(weightingPeer)
              val weightNormalized = weight / totalWeight
              transitiveScore * weightNormalized
          }.sum
          scores(k) = weightedMean
      }
      currentNeighborExpansion = nextNeighborExpansion
    }
    scores.toMap
  }

  def convert(nodes: List[TrustNode]): Map[Int, Map[Int, Double]] =
    nodes.map(tn => tn.id -> tn.edges.map(e => e.dst -> e.trust).toMap).toMap

}
