package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._

import derevo.cats.{eqv, show}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.scalacheck.all._
import org.scalacheck.{Arbitrary, Gen}

object PeerDeclarationsArbitraries {
  // Generate Option[VCC] as always None in arbitrary derivation.
  // VCC requires a NonEmptySet[Signed[ViewChangeVote]] which needs full crypto generators;
  // in production-code test fixtures we rarely need a random VCC.
  implicit val vccOptionArb: Arbitrary[Option[ViewChangeCertificate]] =
    Arbitrary(Gen.const(None))

  // Same rationale for EvictionCertificate list: the cert wraps a NonEmptySet[Signed[EvictionVote]]
  // that needs full crypto generators. Property-based test fixtures almost never need random
  // eviction certificates; tests that need a real cert build one explicitly.
  implicit val evictionCertsListArb: Arbitrary[List[EvictionCertificate]] =
    Arbitrary(Gen.const(List.empty))
}

import PeerDeclarationsArbitraries._

@derive(arbitrary, eqv, show)
case class PeerDeclarations(
  facility: Option[Facility],
  proposal: Option[Proposal],
  signature: Option[MajoritySignature],
  binarySignature: Option[BinarySignature]
)

object PeerDeclarations {
  val empty: PeerDeclarations = PeerDeclarations(Option.empty, Option.empty, Option.empty, Option.empty)
}
