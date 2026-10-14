package io.constellationnetwork.dag.l0.infrastructure.rewards

private[rewards] object RewardApportionment {

  // Largest remainder allocation; equal remainders are resolved by the canonical key order.
  def allocate[A: Ordering](budget: BigInt, weights: List[(A, BigInt)]): List[(A, BigInt)] = {
    val positiveWeights = weights.filter(_._2 > 0).sortBy(_._1)
    val totalWeight = positiveWeights.map(_._2).sum

    if (budget <= 0 || totalWeight == 0) List.empty
    else {
      val portions = positiveWeights.map {
        case (key, weight) =>
          val (quotient, remainder) = (budget * weight) /% totalWeight
          (key, quotient, remainder)
      }
      val residual = (budget - portions.map(_._2).sum).toInt
      val extra = portions.sortBy { case (key, _, remainder) => (-remainder, key) }.take(residual).map(_._1).toSet
      portions.map { case (key, quotient, _) => key -> (quotient + (if (extra.contains(key)) 1 else 0)) }
    }
  }
}
