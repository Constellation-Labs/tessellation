package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.selfhealth.SelfHealthHint
import io.constellationnetwork.schema.peer.PeerId

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

  // Same rationale for TimeoutCertificate: random PeerDeclarations do not need
  // cryptographically signed timeout certs unless a test constructs one explicitly.
  implicit val timeoutCertificateOptionArb: Arbitrary[Option[TimeoutCertificate]] =
    Arbitrary(Gen.const(None))

  // Same rationale for EvictionCertificate list: the cert wraps a NonEmptySet[Signed[EvictionVote]]
  // that needs full crypto generators. Property-based test fixtures almost never need random
  // eviction certificates; tests that need a real cert build one explicitly.
  implicit val evictionCertsListArb: Arbitrary[List[EvictionCertificate]] =
    Arbitrary(Gen.const(List.empty))

  // Same rationale for AdmissionCertificate list (B2).
  implicit val admissionCertsListArb: Arbitrary[List[AdmissionCertificate]] =
    Arbitrary(Gen.const(List.empty))

  // v15 self-health throttle. Picks uniformly from the three states.
  implicit val selfHealthHintArb: Arbitrary[SelfHealthHint] =
    Arbitrary(Gen.oneOf(SelfHealthHint.values))

  // `observedSelfHealth` map and `selfHealthHint` option fields on Facility/Proposal default to
  // empty / None in property-based tests because the map's keyset is otherwise unrelated to the
  // facilitator set and would dominate generation cost without exercising consensus paths.
  implicit val observedSelfHealthArb: Arbitrary[Map[PeerId, SelfHealthHint]] =
    Arbitrary(Gen.const(Map.empty))

  implicit val selfHealthHintOptArb: Arbitrary[Option[SelfHealthHint]] =
    Arbitrary(Gen.const(None))
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
