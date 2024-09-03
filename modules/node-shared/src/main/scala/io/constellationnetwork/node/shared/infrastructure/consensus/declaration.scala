package io.constellationnetwork.node.shared.infrastructure.consensus

import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.Signature

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object declaration {

  sealed trait PeerDeclaration {
    def facilitatorsHash: Hash
  }

  @derive(eqv, show, encoder, decoder)
  case class Facility(
    upperBound: Bound,
    candidates: Candidates,
    trigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastGlobalSnapshotOrdinal: SnapshotOrdinal
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class Proposal(hash: Hash, facilitatorsHash: Hash) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class MajoritySignature(signature: Signature, facilitatorsHash: Hash) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class BinarySignature(signature: Signature, facilitatorsHash: Hash) extends PeerDeclaration
}
