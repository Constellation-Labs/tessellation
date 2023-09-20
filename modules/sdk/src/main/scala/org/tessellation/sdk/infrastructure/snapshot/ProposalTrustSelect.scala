package org.tessellation.sdk.infrastructure.snapshot

import cats.MonadThrow
import cats.syntax.functor._
import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.defaultPeerTrustScore
import org.tessellation.sdk.config.types.ProposalSelectConfig
import org.tessellation.sdk.domain.snapshot.ProposalSelect
import org.tessellation.sdk.domain.trust.storage.TrustMap
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import org.tessellation.security.hash.Hash

import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive

object ProposalTrustSelect {

  def make[F[_]: MonadThrow](
    getTrusts: F[(Option[Map[PeerId, Double]], TrustMap)],
    config: ProposalSelectConfig
  ): ProposalSelect[F] = (declarations: Map[PeerId, PeerDeclarations]) =>
    for {
      (d, r) <- getTrusts
      deterministic = d
        .map(_.filter { case (peerId, s) => s > 0 && declarations.contains(peerId) })
        .getOrElse(Map.empty)

      relative = r.trust.view
        .mapValues(_.predictedTrust)
        .collect { case (peerId, Some(s)) if s > 0 && declarations.contains(peerId) => peerId -> s }
        .toMap

      scoredHashes = declarations
        .foldLeft(Map.empty[Hash, Double]) {
          case (acc, (peerId, pd)) =>
            pd.proposal.map { p =>
              val d = deterministic.getOrElse(peerId, defaultPeerTrustScore.value)
              val r = relative.getOrElse(peerId, defaultPeerTrustScore.value)

              val score = if (r > d * config.trustMultiplier) r else d

              p.hash -> score
            }
              .fold(acc) {
                case (h, s) =>
                  acc.updatedWith(h) {
                    case None => s.some
                    case v    => v.map(_ + s)
                  }
              }
        }
        .toList
        .sortBy { case (_, s) => s }(Ordering[Double].reverse)
        .map {
          case (h, s) => h -> Refined.unsafeApply[Double, Positive](s)
        }
    } yield scoredHashes

}
