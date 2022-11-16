package org.tessellation.sdk.infrastructure.consensus

import cats.syntax.eq._

import scala.annotation.tailrec

import org.tessellation.schema.peer.PeerId

trait FacilitatorCalculator {
  def calculate(peersDeclarations: Map[PeerId, PeerDeclarations], local: List[PeerId], removed: Set[PeerId] = Set.empty): List[PeerId]
}

object FacilitatorCalculator {

  def make(seedlist: Option[Set[PeerId]]): FacilitatorCalculator = new FacilitatorCalculator {
    def calculate(peersDeclarations: Map[PeerId, PeerDeclarations], local: List[PeerId], removed: Set[PeerId]): List[PeerId] = {
      def peerFilter(p: PeerId): Boolean =
        seedlist.forall(_.contains(p)) && !removed.contains(p)

      val localFiltered = local.filter(peerFilter).toSet
      val proposedFiltered = peersDeclarations.view
        .mapValues(_.facility.map(_.facilitators.filter(peerFilter)).getOrElse(Set.empty[PeerId]))
        .toMap

      @tailrec
      def loop(current: Set[PeerId]): Set[PeerId] = {
        val next = proposedFiltered.view.filterKeys(current.contains).values.flatten.toSet.union(current)

        if (next === current)
          current
        else
          loop(next)
      }

      loop(localFiltered).toList.sorted
    }
  }
}
