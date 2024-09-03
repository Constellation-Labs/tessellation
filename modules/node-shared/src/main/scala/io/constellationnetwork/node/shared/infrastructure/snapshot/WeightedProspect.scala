package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.MonadThrow
import cats.data.Chain
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.node.shared.infrastructure.snapshot.WeightedSampling.{
  ProbabilityRefinement,
  Weight,
  WeightRefinement,
  sample => weightedSample
}
import io.constellationnetwork.schema.trust.TrustValueRefined

import eu.timepit.refined.auto._
import eu.timepit.refined.refineV
import eu.timepit.refined.types.numeric.PosInt

object WeightedProspect {

  def sample[F[_]: MonadThrow: Random, A](candidates: Map[A, TrustValueRefined], sampleSize: PosInt): F[List[A]] = {
    val filtered = candidates.view
      .mapValues(score => refineV[WeightRefinement](score.value))
      .collect {
        case (key, Right(refinedScore)) =>
          key -> refinedScore
      }
      .toMap

    if (filtered.size <= sampleSize)
      filtered.keySet.toList.pure
    else {
      type Result = List[A]
      type Agg = (Chain[A], Map[A, Weight])

      Random[F].nextDouble.map(refineV[ProbabilityRefinement].unsafeFrom(_)).flatMap { chosenP =>
        (Chain.empty[A], filtered).tailRecM {
          case (chosen, _) if chosen.size == sampleSize.value => chosen.toList.asRight[Agg].pure
          case (chosen, d) =>
            weightedSample(chosenP)(d.toList).map(c => (chosen :+ c, d - c).asLeft[Result])
        }
      }

    }
  }

}
