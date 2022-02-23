package org.tessellation.sdk.infrastructure.consensus

import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.signature.Signature

case class PeerDeclaration(
  upperBound: Option[Bound],
  proposal: Option[Hash],
  signature: Option[Signature]
)

object PeerDeclaration {
  val empty: PeerDeclaration = PeerDeclaration(Option.empty, Option.empty, Option.empty)
}
