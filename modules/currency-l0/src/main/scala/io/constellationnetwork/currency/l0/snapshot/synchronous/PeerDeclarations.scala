package io.constellationnetwork.currency.l0.snapshot.synchronous

import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration._

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class PeerDeclarations(
  facility: Option[Facility],
  proposal: Option[Proposal],
  signature: Option[MajoritySignature],
  binarySignature: Option[BinarySignature]
)

object PeerDeclarations {
  val empty: PeerDeclarations = PeerDeclarations(Option.empty, Option.empty, Option.empty, Option.empty)
}
