package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.data.NonEmptySet
import cats.syntax.all._

import io.constellationnetwork.currency.schema.currency.SnapshotFee
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.{BinaryProposal, BinarySignature}
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import io.circe.syntax._
import weaver.FunSuite

object BinarySignatureAttemptDomainSuite extends FunSuite {

  private val signature = Signature(Hex("00"))
  private val facilitatorsHash = Hash("facilitators")
  private val parentHash = Hash("parent")
  private val binaryHash = Hash("binary")
  private val proposalHash = Hash("proposal")
  private val domain = BinarySignatureAttemptDomain(facilitatorsHash, parentHash, binaryHash, 3L, proposalHash)

  private def declaration(
    binary: Hash = binaryHash,
    view: Long = 3L,
    proposal: Hash = proposalHash
  ): BinarySignature =
    BinarySignature(signature, facilitatorsHash, parentHash, binary, view, proposal)

  test("an exact binary signing attempt matches") {
    expect(domain.contains(declaration()))
  }

  test("a signature for a competing binary is rejected") {
    expect(!domain.contains(declaration(binary = Hash("other-binary"))))
  }

  test("a stale view signature is rejected") {
    expect(!domain.contains(declaration(view = 2L)))
  }

  test("a signature for another Currency artifact proposal is rejected") {
    expect(!domain.contains(declaration(proposal = Hash("other-proposal"))))
  }

  test("a certified binary proposal survives the declaration JSON wire round-trip") {
    val proof = SignatureProof(Id(Hex("01" * 64)), signature)
    val binaryProposal = BinaryProposal(
      NonEmptySet.one(proof),
      StateChannelSnapshotBinary(parentHash, "certified-binary".getBytes("UTF-8"), SnapshotFee.MinValue)
    )
    val expected = declaration().copy(proposal = binaryProposal.some)
    val decoded = expected.asJson.as[BinarySignature]

    expect(decoded.exists(_ === expected))
  }
}
