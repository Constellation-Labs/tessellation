package io.constellationnetwork.node.shared.infrastructure.consensus

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.state._
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.Signature

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object declaration {

  @derive(eqv, show, encoder, decoder)
  sealed trait PeerDeclaration {
    def facilitatorsHash: Hash
    def lastSnapshotHash: Hash
  }

  @derive(eqv, show, encoder, decoder)
  case class Facility(
    eventHashes: SortedSet[Hash],
    candidates: Candidates,
    trigger: Option[ConsensusTrigger],
    facilitatorsHash: Hash,
    lastGlobalSnapshotOrdinal: SnapshotOrdinal,
    lastSnapshotHash: Hash
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class Proposal(hash: Hash, facilitatorsHash: Hash, lastSnapshotHash: Hash) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class MajoritySignature(signature: Signature, facilitatorsHash: Hash, lastSnapshotHash: Hash) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  case class BinarySignature(signature: Signature, facilitatorsHash: Hash, lastSnapshotHash: Hash) extends PeerDeclaration
}
