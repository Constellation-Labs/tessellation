package org.tessellation.sdk.infrastructure.consensus

import cats.syntax.eq._

import scala.annotation.tailrec

import org.tessellation.schema.peer.PeerId

trait FacilitatorCalculator {
  def calculate(peersDeclarations: Map[PeerId, PeerDeclarations], local: Set[PeerId], removed: Set[PeerId]): Set[PeerId]
}

object FacilitatorCalculator {

  def make(seedlist: Option[Set[PeerId]]): FacilitatorCalculator =
    (peersDeclarations: Map[PeerId, PeerDeclarations], local: Set[PeerId], removed: Set[PeerId]) => {
      def peerFilter(p: PeerId): Boolean =
        seedlist.forall(_.contains(p)) && !removed.contains(p)

      val localFiltered = local.filter(peerFilter)
      val proposedFiltered = peersDeclarations.view
        .mapValues(_.facility.map(_.facilitators.filter(peerFilter)))
        .collect { case (id, Some(facilitators)) => id -> facilitators }
        .toMap

      @tailrec
      def loop(current: Set[PeerId]): Set[PeerId] = {
        val next = proposedFiltered.view.filterKeys(current.contains).values.flatten.toSet.union(current)

        if (next === current)
          current
        else
          loop(next)
      }

      loop(localFiltered)
    }
}
