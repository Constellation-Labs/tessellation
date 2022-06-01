package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.sdk.infrastructure.consensus.declaration._

case class PeerDeclarations(
  facility: Option[Facility],
  proposal: Option[Proposal],
  signature: Option[MajoritySignature]
)

object PeerDeclarations {
  val empty: PeerDeclarations = PeerDeclarations(Option.empty, Option.empty, Option.empty)
}
