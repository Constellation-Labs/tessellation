package io.constellationnetwork.node.shared.infrastructure.fork

import cats.Monad
import cats.syntax.contravariantSemigroupal._

import io.constellationnetwork.node.shared.domain.fork.{ForkDetect, ForkInfo, ForkInfoMap}
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.trust.TrustScores
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

object ForkDetect {

  def exitOnCheck(flag: String, facilitators: Set[PeerId]): Unit =
    if (sys.env.get(flag).contains("true")) {
      sys.env.get("CL_FOLLOWER_ID") match {
        case Some(id) =>
          val peerId = PeerId(Hex(id))
          val hasFollowerPeer = facilitators.contains(peerId)
          if (!hasFollowerPeer) {
            println(s"Exit in advancer to missing follower peer on $flag")
            System.exit(1)
          }
        case _ =>
      }
    }

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
