package org.tessellation.sdk.domain.trust.storage

import cats.syntax.eq._
import cats.syntax.functorFilter._

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.{PublicTrust, TrustInfo}

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

@derive(eqv, encoder, decoder, show)
case class TrustMap(trust: Map[PeerId, TrustInfo], peerLabels: PublicTrustMap) {

  def isEmpty: Boolean = this === TrustMap.empty

  def toPublicTrust: PublicTrust = PublicTrust(trust.mapFilter(_.publicTrust))

  def hasTrustValues: Boolean = trust =!= Map.empty

}

object TrustMap {

  val empty: TrustMap = TrustMap(Map.empty, PublicTrustMap.empty)

}
