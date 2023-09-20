package org.tessellation.sdk.infrastructure.snapshot

import cats.Applicative
import cats.effect.IO
import cats.syntax.applicative._
import cats.syntax.option._

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust.TrustInfo
import org.tessellation.sdk.config.types.ProposalSelectConfig
import org.tessellation.sdk.domain.snapshot.ProposalSelect
import org.tessellation.sdk.domain.trust.storage.{PublicTrustMap, TrustMap}
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import org.tessellation.sdk.infrastructure.consensus.declaration.Proposal
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosDouble
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object ProposalTrustSelectSuite extends SimpleIOSuite with Checkers {
  val defaultDeterministicTrust = Map(
    PeerId(Hex("a")) -> -1.0,
    PeerId(Hex("b")) -> 0.5,
    PeerId(Hex("c")) -> 1.0,
    PeerId(Hex("d")) -> -0.5,
    PeerId(Hex("e")) -> 0.75,
    PeerId(Hex("f")) -> 0.2
  )

  def mkProposalSelect(
    deterministicTrust: IO[Option[Map[PeerId, Double]]] = defaultDeterministicTrust.some.pure,
    relativeTrust: IO[TrustMap] = TrustMap.empty.pure
  ): ProposalSelect[F] = {
    val getTrusts = Applicative[IO].product(deterministicTrust, relativeTrust)
    val config = ProposalSelectConfig(trustMultiplier = 5.0)

    ProposalTrustSelect.make[IO](getTrusts, config)
  }

  test("no proposals returns an empty list") {
    val declarations: Map[PeerId, PeerDeclarations] = Map.empty
    val select = mkProposalSelect()

    select
      .score(declarations)
      .map(r => expect.eql(true, r.isEmpty))
  }

  test("scored declarations does not include proposals from peers with negative trust") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("a")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashA"), Hash("facilitatorHashA")).some,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      )
    )

    val relativeTrust = TrustMap(
      trust = Map(
        PeerId(Hex("a")) -> TrustInfo(predictedTrust = Some(-1.0))
      ),
      peerLabels = PublicTrustMap.empty
    ).pure

    val select = mkProposalSelect(relativeTrust = relativeTrust)

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashC"), 1.0),
      (Hash("hashB"), 0.5)
    )

    select.score(declarations).map { actual =>
      expect.eql(true, expected.diff(actual).isEmpty)
    }
  }

  test("scored declarations includes peer IDs not in the trust maps") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("a")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashA"), Hash("facilitatorHashA")).some,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("g")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashG"), Hash("facilitatorHashG")).some,
        none
      )
    )

    val select = mkProposalSelect()

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashC"), 1.0),
      (Hash("hashB"), 0.5),
      (Hash("hashG"), 1e-4)
    )

    select.score(declarations).map { actual =>
      expect.eql(true, expected.diff(actual).isEmpty)
    }
  }

  test("scored declarations adds scores for the same hash") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("e")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      ),
      PeerId(Hex("f")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      )
    )

    val select = mkProposalSelect()

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashC"), 1.95),
      (Hash("hashB"), 0.5)
    )

    select.score(declarations).map { actual =>
      expect.eql(true, expected.diff(actual).isEmpty)
    }
  }

  test("scored declarations uses the relative score when it is sufficiently large relative to the deterministic score") {
    val declarations: Map[PeerId, PeerDeclarations] = Map(
      PeerId(Hex("a")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashA"), Hash("facilitatorHashA")).some,
        none
      ),
      PeerId(Hex("b")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashB"), Hash("facilitatorHashB")).some,
        none
      ),
      PeerId(Hex("c")) -> PeerDeclarations(
        none,
        Proposal(Hash("hashC"), Hash("facilitatorHashC")).some,
        none
      )
    )

    val deterministicTrust = Map(
      PeerId(Hex("a")) -> -1.0,
      PeerId(Hex("b")) -> 0.5,
      PeerId(Hex("c")) -> 0.75
    ).some.pure

    val relativeTrust = TrustMap(
      trust = Map(
        PeerId(Hex("a")) -> TrustInfo(predictedTrust = Some(1.0))
      ),
      peerLabels = PublicTrustMap.empty
    ).pure

    val select = mkProposalSelect(deterministicTrust = deterministicTrust, relativeTrust = relativeTrust)

    val expected: List[(Hash, PosDouble)] = List(
      (Hash("hashA"), 1.0),
      (Hash("hashC"), 0.75),
      (Hash("hashB"), 0.5)
    )

    select.score(declarations).map { actual =>
      expect.eql(true, expected.diff(actual).isEmpty)
    }
  }
}
