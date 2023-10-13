package org.tessellation.sdk.infrastructure.fork

import cats.Monad
import cats.syntax.contravariantSemigroupal._

import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.trust.TrustScores
import org.tessellation.sdk.domain.fork.{ForkDetect, ForkInfo, ForkInfoMap}
import org.tessellation.security.hash.Hash

object ForkDetect {

  def make[F[_]: Monad](
    getTrustScores: F[TrustScores],
    getForkInfo: F[ForkInfoMap]
  ): ForkDetect[F] = new ForkDetect[F] {

    def getMajorityFork: F[ForkInfo] =
      (getTrustScores, getForkInfo).mapN {
        case (trust, info) =>
          val scores = trust.scores.filter { case (_, s) => s > 0.0 }
          val weightedForks = info.forks.foldLeft(List.empty[(SnapshotOrdinal, (Hash, Double))]) {
            case (acc, (peerId, entries)) =>
              if (scores.contains(peerId))
                acc.prependedAll(entries.getEntries.map(e => e.ordinal -> (e.hash, scores(peerId))))
              else
                acc
          }

          val groupedOrdinals = weightedForks.groupMap { case (ordinal, _) => ordinal } { case (_, t) => t }
          val scoredOrdinals = groupedOrdinals.view
            .mapValues(_.foldLeft(0.0) { case (acc, (_, s)) => acc + s })
            .toMap

          val majorityOrdinal = scoredOrdinals.maxBy { case (_, s) => s }._1
          val majorityHash =
            groupedOrdinals(majorityOrdinal).groupMapReduce { case (h, _) => h } { case (_, s) => s }(_ + _).maxBy {
              case (_, s) => s
            }._1

          ForkInfo(majorityOrdinal, majorityHash)
      }
  }

}
