package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.semigroupk._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, RumorHandlerWithQueue}
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.security.HasherSelector

import io.circe.Decoder

class ConsensusRumorHandlers[F[
  _
]: Async: HasherSelector, Event: TypeTag: Decoder, Key: TypeTag: Decoder, Artifact: TypeTag: Decoder, Context, Status, Outcome, Kind: Decoder: TypeTag](
  queue: Queue[F, ConsensusCommand[Key, Artifact, Context, Outcome]]
) {

  /** 1. Events */
  val eventHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusEvent[Event]](queue.offer)

  /** 2. Facility */
  val facilityHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration[Key, Facility]](queue.offer)

  /** 3. Proposal */
  val proposalHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration[Key, Proposal]](queue.offer)

  /** 4. MajoritySignature */
  val signatureHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration[Key, MajoritySignature]](queue.offer)

  /** 5. BinarySignature */
  val binarySignatureHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration[Key, BinarySignature]](queue.offer)

  /** 6. PeerDeclarationAck */
  val ackHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclarationAck[Key, Kind]](queue.offer)

  /** 7. WithdrawPeerDeclaration */
  val withdrawHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusWithdrawPeerDeclaration[Key, Kind]](queue.offer)

  /** 8. Artifact (Common rumor) */
  val artifactHandler: RumorHandler[F] =
    RumorHandlerWithQueue.common[F, ConsensusArtifact[Key, Artifact]](queue.offer)

  /** 9. ViewChangeVote (signed, routed via ConsensusPeerVote — not ConsensusPeerDeclaration — so the per-vote Signed proof survives to the
    * VCC assembly stage).
    */
  val viewChangeVoteHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerVote[Key]](queue.offer)

  /** 10. TimeoutVote (signed, routed separately from ViewChangeVote while TC is collect-only). */
  val timeoutVoteHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerTimeoutVote[Key]](queue.offer)

  /** 11. EvictionVote (signed, routed via ConsensusPeerEvictionVote for the same per-vote Signed preservation rationale as ViewChangeVote).
    *
    * Without this registration, inbound ConsensusPeerEvictionVote rumors gossiped by other peers are silently dropped at the rumor-router
    * layer — Kryo decodes them, but nothing dispatches them to the ConsensusCommand queue. That kept EvictionCertificate assembly stuck at
    * votes=1 (the local self-vote only) during the broken-leader stall on testnet, even though every honest peer was emitting votes. See
    * `.workspace/codex-response-broken-leader-trap-apr23.md` for the full diagnosis.
    */
  val evictionVoteHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerEvictionVote[Key]](queue.offer)

  /** 12. AdmissionVote (B2, symmetric counterpart of EvictionVote). Same wiring requirements — without registration, inbound
    * ConsensusPeerAdmissionVote rumors are silently dropped.
    */
  val admissionVoteHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerAdmissionVote[Key]](queue.offer)

  /** 13. AssembledVcc (re-distribution of a locally-built ViewChangeCertificate). Lets peers that did NOT see local quorum for a
    * `(fromView, toView)` transition still store the VCC locally, so they can build a valid proposal when they next lead at `view > 0`.
    * Symmetric wiring to the other vote handlers -- without registration, inbound ConsensusAssembledVcc rumors are silently dropped at the
    * rumor-router layer.
    */
  val assembledVccHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusAssembledVcc[Key]](queue.offer)
}
