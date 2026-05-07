package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.Applicative
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.schema.gossip.{CommonRumor, Ordinal, PeerRumor}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.HasherSelector

/** Processes incoming consensus rumors and stores them.
  *
  * ==Flow==
  *
  * {{{
  *   RumorReceived(rumor)
  *       │
  *       ▼
  *   RumorHandler.process(rumor)
  *       │
  *       ├── PeerRumor? → processPeerRumor()
  *       │                    │
  *       │                    ├── ConsensusPeerDeclaration → handleDeclaration()
  *       │                    │     ├── Facility → storage.addFacility()
  *       │                    │     ├── Proposal → storage.addProposal()
  *       │                    │     ├── MajoritySignature → storage.addSignature()
  *       │                    │     └── BinarySignature → storage.addBinarySignature()
  *       │                    │
  *       │                    ├── ConsensusPeerDeclarationAck → storage.addPeerDeclarationAck()
  *       │                    │
  *       │                    ├── ConsensusWithdrawPeerDeclaration → storage.addWithdrawPeerDeclaration()
  *       │                    │
  *       │                    └── ConsensusArtifact → storage.addArtifact()
  *       │
  *       └── CommonRumor? → processCommonRumor()
  *
  *   After storing: triggerUpdateIfChanged(key) → queue.offer(CheckUpdate(key))
  * }}}
  *
  * Note: Events are now propagated through EventMempool + EventGossipDaemon, not through consensus rumors.
  *
  * ==Key Method==
  *
  * '''process(rumor):''' Routes to appropriate handler based on rumor type
  */
class RumorHandler[F[_]: Async: HasherSelector, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
) {

  import ctx.{logger => log, queue, storage}
  import ConsensusHelpers.triggerUpdateIfChanged

  def process(rumor: Either[PeerRumor[_], CommonRumor[_]]): F[Unit] =
    rumor.fold(processPeerRumor, processCommonRumor)

  private def processPeerRumor(rumor: PeerRumor[_]): F[Unit] =
    rumor match {
      case PeerRumor(origin, ordinal, content) => dispatchContent(origin, ordinal, content)
      case other                               => log.warn(s"Unknown rumor wrapper: $other")
    }

  private def dispatchContent(origin: PeerId, _ordinal: Ordinal, content: Any): F[Unit] =
    content match {
      case d: ConsensusPeerDeclaration[_, _]         => handleDeclaration(origin, d)
      case v: ConsensusPeerVote[_]                   => handlePeerVote(origin, v)
      case e: ConsensusPeerEvictionVote[_]           => handleEvictionVote(origin, e)
      case ec: ConsensusPeerEvictionCertificate[_]   => handleEvictionCertificate(origin, ec)
      case av: ConsensusPeerAdmissionVote[_]         => handleAdmissionVote(origin, av)
      case a: ConsensusPeerDeclarationAck[_, _]      => handleDeclarationAck(origin, a)
      case w: ConsensusWithdrawPeerDeclaration[_, _] => handleWithdrawDeclaration(origin, w)
      case ConsensusArtifact(key, artifact)          =>
        // Artifact gossip is keyed; record the sender at this key before processing so Bug-B
        // aheadness detection reflects peers circulating artifacts for newer rounds.
        storage.observePeerAtKey(origin, key.asInstanceOf[Key]) >>
          handleArtifact(key.asInstanceOf[Key], artifact.asInstanceOf[Artifact])
      case other => log.warn(s"Unknown peer rumor content: $other")
    }

  private def processCommonRumor(rumor: CommonRumor[_]): F[Unit] =
    rumor.content match {
      case ConsensusArtifact(key, artifact) => handleArtifact(key.asInstanceOf[Key], artifact.asInstanceOf[Artifact])
      case other                            => log.warn(s"Unknown common rumor content: $other")
    }

  private def handleDeclaration(origin: PeerId, decl: ConsensusPeerDeclaration[_, _]): F[Unit] = {
    val key = decl.key.asInstanceOf[Key]
    // Record the sender's current tip BEFORE any admission filtering. This is the live "peer is
    // ahead of me" signal consumed by AbandonmentTracker + StallDetector. See Bug B in the
    // 2026-04-21 post-mortem: peerRegistrations alone is set-once and goes stale.
    val observeTip = storage.observePeerAtKey(origin, key)
    val kindLabel = decl.declaration match {
      case _: Facility          => "Facility"
      case _: Proposal          => "Proposal"
      case _: MajoritySignature => "Signature"
      case _: BinarySignature   => "BinarySignature"
      case _: ViewChangeVote    => "ViewChangeVote"
      case other                => other.getClass.getSimpleName
    }
    val logReceipt = ConsensusLog.info(
      log,
      Category.Facilitator,
      key.toString,
      "n/a",
      LogEvent.DeclarationReceived,
      "kind" -> kindLabel,
      "from" -> ConsensusLog.pid(origin)
    )
    val op = decl.declaration match {
      case f: Facility          => storage.addFacility(origin, key, f)
      case p: Proposal          => storage.addProposal(origin, key, p)
      case s: MajoritySignature => storage.addSignature(origin, key, s)
      case b: BinarySignature   => storage.addBinarySignature(origin, key, b)
      case other                =>
        // ViewChangeVote intentionally NOT handled here — it routes through ConsensusPeerVote
        // (signed wire wrapper) via handlePeerVote. Any other unknown declaration is an error.
        new IllegalArgumentException(s"Unexpected declaration: ${other.getClass.getName}").raiseError[F, Option[Any]]
    }
    observeTip >> logReceipt >> op.flatMap(triggerUpdateIfChanged(queue, key))
  }

  private def handlePeerVote(origin: PeerId, v: ConsensusPeerVote[_]): F[Unit] = {
    val key = v.key.asInstanceOf[Key]
    val observeTip = storage.observePeerAtKey(origin, key)
    val signedVote = v.vote
    val fromView = signedVote.value.fromView
    val toView = signedVote.value.toView
    observeTip >>
      ConsensusLog.info(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationReceived,
        "kind" -> "ViewChangeVote",
        "from" -> ConsensusLog.pid(origin),
        "fromView" -> fromView.toString,
        "toView" -> toView.toString
      ) >>
      storage
        .addViewChangeVote(origin, key, fromView, toView, signedVote)
        .flatMap(triggerUpdateIfChanged(queue, key)) >>
      queue.offer(io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand.CheckViewChangeAssembly(key))
  }

  private def handleEvictionVote(origin: PeerId, e: ConsensusPeerEvictionVote[_]): F[Unit] = {
    val key = e.key.asInstanceOf[Key]
    val observeTip = storage.observePeerAtKey(origin, key)
    val signedVote = e.vote
    val target = signedVote.value.targetPeer
    val signer = signedVote.proofs.head.id.toPeerId
    // Only accept the vote if the gossip sender is the signer. Votes arrive via `spreadDirect`
    // which pushes straight from the signer to each committee member; a peer relaying someone
    // else's vote is either buggy or adversarial. Rejecting relays here means the storage slot
    // is keyed by the actual signer PeerId, so duplicate-relay cannot inflate the quorum count
    // at certificate-assembly time.
    if (origin =!= signer) {
      observeTip >> ConsensusLog.warn(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationReceived,
        "kind" -> "EvictionVote",
        "rejected" -> "origin_signer_mismatch",
        "from" -> ConsensusLog.pid(origin),
        "signer" -> ConsensusLog.pid(signer),
        "target" -> ConsensusLog.pid(target)
      )
    } else {
      observeTip >> ConsensusLog.info(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationReceived,
        "kind" -> "EvictionVote",
        "from" -> ConsensusLog.pid(origin),
        "target" -> ConsensusLog.pid(target),
        "reason" -> signedVote.value.reason.toString
      ) >>
        storage
          .addEvictionVote(origin, key, signedVote)
          .flatMap(triggerUpdateIfChanged(queue, key)) >>
        queue.offer(io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand.CheckEvictionAssembly(key, target))
    }
  }

  /** Handle a gossiped, already-assembled `EvictionCertificate`. The cert carries quorum-many signed votes; we re-validate it structurally
    * here using the same builder the proposer uses, then store. Storage is idempotent (assembledEvictionCerts is a Set).
    *
    * No consensus behavior changes from receiving the cert in this PR — the read path (`getAssembledEvictionCertificates`, used by
    * advancers at proposal-build time) is unchanged. Only fan-out is wider: the cert reaches followers via direct gossip rather than only
    * via a subsequent Proposal's embedded `evictionCertificates` field. See the followup design at
    * `docs/consensus/eviction-cert-deterministic-shrinkage.md` for how the wider distribution is intended to support same-ordinal committee
    * shrinkage under a deterministic activation gate.
    */
  private def handleEvictionCertificate(origin: PeerId, e: ConsensusPeerEvictionCertificate[_]): F[Unit] = {
    val key = e.key.asInstanceOf[Key]
    val observeTip = storage.observePeerAtKey(origin, key)
    val cert = e.cert
    // Replay the structural validation the proposer performs at assembly time. We don't have the
    // sender's witness pool locally, so the strictest check (signer-in-witness-pool) cannot fire
    // exactly here — but the cert already carries quorum-many signed votes whose signatures and
    // (target, reason, facilitatorsHash, lastSnapshotHash) consistency are what the builder
    // verifies. validateProposalEcs at proposal-acceptance time provides the final canonical check
    // before the cert is applied to consensus state.
    val votesByKey: Map[PeerId, io.constellationnetwork.security.signature.Signed[
      io.constellationnetwork.node.shared.infrastructure.consensus.declaration.EvictionVote
    ]] =
      cert.votes.toSortedSet.toList.map(sv => (sv.proofs.head.id.toPeerId, sv)).toMap
    val witnessPool: Set[PeerId] = votesByKey.keySet // permissive; canonical pool is checked at proposal acceptance
    val q = votesByKey.size // cert was assembled at quorum; structural recheck only
    val rebuild = io.constellationnetwork.node.shared.infrastructure.consensus.engine.EvictionCertificateBuilder.build(
      cert.targetPeer,
      cert.reason,
      cert.facilitatorsHash,
      cert.lastSnapshotHash,
      votesByKey,
      q,
      witnessPool
    )
    rebuild match {
      case Left(error) =>
        observeTip >> ConsensusLog.warn(
          log,
          Category.Phase,
          key.toString,
          "n/a",
          LogEvent.Eviction,
          "carrier" -> "cert_gossip_rejected",
          "from" -> ConsensusLog.pid(origin),
          "target" -> ConsensusLog.pid(cert.targetPeer),
          "reason" -> error.code
        )
      case Right(_) =>
        observeTip >> ConsensusLog.info(
          log,
          Category.Phase,
          key.toString,
          "n/a",
          LogEvent.Eviction,
          "carrier" -> "cert_gossip_received",
          "from" -> ConsensusLog.pid(origin),
          "target" -> ConsensusLog.pid(cert.targetPeer),
          "votes" -> votesByKey.size.toString
        ) >> storage.storeAssembledEvictionCertificate(key, cert)
    }
  }

  private def handleAdmissionVote(origin: PeerId, a: ConsensusPeerAdmissionVote[_]): F[Unit] = {
    val key = a.key.asInstanceOf[Key]
    val observeTip = storage.observePeerAtKey(origin, key)
    val signedVote = a.vote
    val target = signedVote.value.targetPeer
    val signer = signedVote.proofs.head.id.toPeerId
    // Same origin==signer gate as B1 — see handleEvictionVote for the relay-rejection rationale.
    if (origin =!= signer) {
      observeTip >> ConsensusLog.warn(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationReceived,
        "kind" -> "AdmissionVote",
        "rejected" -> "origin_signer_mismatch",
        "from" -> ConsensusLog.pid(origin),
        "signer" -> ConsensusLog.pid(signer),
        "target" -> ConsensusLog.pid(target)
      )
    } else {
      observeTip >> ConsensusLog.info(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationReceived,
        "kind" -> "AdmissionVote",
        "from" -> ConsensusLog.pid(origin),
        "target" -> ConsensusLog.pid(target),
        "reason" -> signedVote.value.reason.toString
      ) >>
        storage
          .addAdmissionVote(origin, key, signedVote)
          .flatMap(triggerUpdateIfChanged(queue, key)) >>
        queue.offer(
          io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand.CheckAdmissionAssembly(key, target)
        )
    }
  }

  private def handleDeclarationAck(origin: PeerId, ack: ConsensusPeerDeclarationAck[_, _]): F[Unit] = {
    val key = ack.key.asInstanceOf[Key]
    storage.observePeerAtKey(origin, key) >>
      ConsensusLog.info(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationAckReceived,
        "kind" -> ack.kind.getClass.getSimpleName.stripSuffix("$"),
        "from" -> ConsensusLog.pid(origin),
        "ack" -> ack.ack.toString
      ) >>
      storage
        .addPeerDeclarationAck(origin, key, ack.kind.asInstanceOf[Kind], ack.ack)
        .flatMap(triggerUpdateIfChanged(queue, key))
  }

  private def handleWithdrawDeclaration(origin: PeerId, w: ConsensusWithdrawPeerDeclaration[_, _]): F[Unit] = {
    val key = w.key.asInstanceOf[Key]
    storage.observePeerAtKey(origin, key) >>
      ConsensusLog.info(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationWithdrawn,
        "kind" -> w.kind.getClass.getSimpleName.stripSuffix("$"),
        "from" -> ConsensusLog.pid(origin)
      ) >>
      storage
        .addWithdrawPeerDeclaration(origin, key, w.kind.asInstanceOf[Kind])
        .flatMap(triggerUpdateIfChanged(queue, key))
  }

  private def handleArtifact(key: Key, artifact: Artifact): F[Unit] =
    HasherSelector[F].withCurrent { implicit h =>
      storage.addArtifact(key, artifact)
    }.flatMap(triggerUpdateIfChanged(queue, key))
}
