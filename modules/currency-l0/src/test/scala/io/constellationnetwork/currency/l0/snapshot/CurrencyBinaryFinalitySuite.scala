package io.constellationnetwork.currency.l0.snapshot

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.state.QuorumDenominatorShrink
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import weaver.SimpleIOSuite

object CurrencyBinaryFinalitySuite extends SimpleIOSuite {

  private def peer(byte: String): PeerId = PeerId(Hex(byte * 128))

  private def proof(peerId: PeerId, byte: String): SignatureProof =
    SignatureProof(Id(peerId.value), Signature(Hex(byte * 128)))

  private val committee = List(peer("1"), peer("2"), peer("3"), peer("4"), peer("5"))
  private val decision = QuorumDenominatorShrink.Decision(
    active = false,
    steps = 0,
    baseQuorum = 4,
    requiredQuorum = 4,
    anchor = SortedSet.empty
  )

  pureTest("three received declarations with only one valid proof cannot finalize a four-proof quorum") {
    val validAfterVerification = List(proof(committee.head, "a"))

    expect(
      !CurrencySnapshotConsensusStateAdvancer.hasValidSignatureQuorum(
        validAfterVerification,
        committee.toSet,
        decision
      )
    )
  }

  pureTest("four distinct valid committee proofs satisfy a four-proof quorum") {
    val valid = committee.take(4).zip(List("a", "b", "c", "d")).map { case (id, byte) => proof(id, byte) }

    expect(CurrencySnapshotConsensusStateAdvancer.hasValidSignatureQuorum(valid, committee.toSet, decision))
  }

  pureTest("foreign proofs cannot inflate the valid quorum") {
    val foreign = peer("9")
    val proofs = committee.take(3).zip(List("a", "b", "c")).map { case (id, byte) => proof(id, byte) } :+ proof(foreign, "d")

    expect(!CurrencySnapshotConsensusStateAdvancer.hasValidSignatureQuorum(proofs, committee.toSet, decision))
  }

  pureTest("multiple proofs from one peer count as one signer and are rejected") {
    val duplicated = List(
      proof(committee.head, "a"),
      proof(committee.head, "b"),
      proof(committee(1), "c"),
      proof(committee(2), "d")
    )

    expect(!CurrencySnapshotConsensusStateAdvancer.hasValidSignatureQuorum(duplicated, committee.toSet, decision))
  }
}
