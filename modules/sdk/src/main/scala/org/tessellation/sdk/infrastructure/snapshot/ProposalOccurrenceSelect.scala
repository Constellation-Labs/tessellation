package org.tessellation.sdk.infrastructure.snapshot

import cats.Order
import cats.effect.kernel.Async
import cats.syntax.foldable._

import org.tessellation.schema.peer
import org.tessellation.sdk.domain.snapshot.ProposalSelect
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations

import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

object ProposalOccurrenceSelect {

  def make[F[_]: Async](): ProposalSelect[F] = (declarations: Map[peer.PeerId, PeerDeclarations]) =>
    Async[F].delay(
      declarations.flatMap { case (_, declarations) => declarations.proposal.map(_.hash) }.toList
        .foldMap(h => Map(h -> 1))
        .toList
        .sortBy { case (_, s) => s }(Order[Int].toOrdering.reverse)
        .map {
          case (h, s) => h -> Refined.unsafeApply[Double, Positive](s.toDouble)
        }
    )

}
