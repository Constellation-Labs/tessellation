package io.constellationnetwork.currency.l0.snapshot.synchronous

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.signature.Signature

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Currency-local wire declarations for the release/mainnet synchronous protocol.
  *
  * Parent hashes are the only v4 strengthening: they prevent declarations from a superseded branch at the same ordinal from entering the
  * live attempt. These messages are not public snapshot schema and are fenced by the ordinary node version/config join gates.
  */
object declaration {

  @derive(eqv, show, encoder, decoder)
  final case class AttemptDomain(
    // Immutable round-start set. ACK removal and authenticated withdrawal may
    // narrow the active set without changing the declaration domain mid-round.
    facilitatorsHash: Hash,
    parentArtifactHash: Hash,
    parentBinaryHash: Hash
  )

  sealed trait PeerDeclaration {
    def domain: AttemptDomain

    final def facilitatorsHash: Hash = domain.facilitatorsHash
    final def parentArtifactHash: Hash = domain.parentArtifactHash
    final def parentBinaryHash: Hash = domain.parentBinaryHash
  }

  @derive(eqv, show, encoder, decoder)
  final case class Facility(
    eventHashes: SortedSet[Hash],
    candidates: Candidates,
    trigger: Option[ConsensusTrigger],
    lastGlobalSnapshotOrdinal: SnapshotOrdinal,
    domain: AttemptDomain
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  final case class Proposal(
    hash: Hash,
    domain: AttemptDomain
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  final case class MajoritySignature(
    signature: Signature,
    artifactHash: Hash,
    domain: AttemptDomain
  ) extends PeerDeclaration

  @derive(eqv, show, encoder, decoder)
  final case class BinarySignature(
    signature: Signature,
    binaryHash: Hash,
    domain: AttemptDomain
  ) extends PeerDeclaration
}
