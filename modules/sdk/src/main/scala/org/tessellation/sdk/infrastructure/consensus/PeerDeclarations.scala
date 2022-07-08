package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.sdk.infrastructure.consensus.declaration._

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class PeerDeclarations(
  facility: Option[Facility],
  proposal: Option[Proposal],
  signature: Option[MajoritySignature]
)

object PeerDeclarations {
  val empty: PeerDeclarations = PeerDeclarations(Option.empty, Option.empty, Option.empty)
}
