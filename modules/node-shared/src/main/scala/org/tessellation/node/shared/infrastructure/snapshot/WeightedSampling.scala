package org.tessellation.node.shared.infrastructure.snapshot

import cats.MonadThrow
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Interval

object WeightedSampling {

  type ProbabilityRefinement = Interval.ClosedOpen[0.0, 1.0]
  type Probability = Double Refined ProbabilityRefinement

  type WeightRefinement = Interval.OpenClosed[0.0, 1.0]
  type Weight = Double Refined WeightRefinement

  def sample[F[_]: MonadThrow, A](probability: Probability)(distribution: List[(A, Weight)]): F[A] = {
    type Agg = (Double, List[(A, Double)])

    val sum = distribution.foldLeft(0.0) { case (sum, (_, weight)) => sum + weight }

    for {
      _ <- new IllegalStateException("The distribution sum should be positive.").raiseError.whenA(sum <= 0.0)

      normalized = distribution.map { case (c, p) => c -> (p / sum) }

      sample <- (0.0, normalized).tailRecM {
        case (_, Nil) =>
          new IllegalArgumentException("The probability calculation is incorrect.")
            .raiseError[F, Either[Agg, A]]
        case (accP, (candidate, p) :: tail) if probability <= (accP + p) || tail.isEmpty =>
          candidate.asRight[Agg].pure
        case (accP, (_, p) :: tail) =>
          (accP + p, tail).asLeft[A].pure
      }
    } yield sample
  }
}
