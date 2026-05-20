package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.Applicative
import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand
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
      case av: ConsensusPeerAdmissionVote[_]         => handleAdmissionVote(origin, av)
      case vc: ConsensusAssembledVcc[_]              => handleAssembledVcc(origin, vc)
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
    // post-mortem: peerRegistrations alone is set-once and goes stale.
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
      queue.offer(ConsensusCommand.CheckViewChangeAssembly(key))
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
        queue.offer(ConsensusCommand.CheckEvictionAssembly(key, target))
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
          ConsensusCommand.CheckAdmissionAssembly(key, target)
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

  /** Receive a locally-assembled `ViewChangeCertificate` from a peer that DID reach quorum for some `(fromView, toView)` transition and
    * store it on this node, even if this node has not yet seen enough VCV votes locally to assemble its own. Closes the per-peer assembly
    * asymmetry that would otherwise leave a lagging peer with an empty `assembledVccR` slot at the current view, wedging the leader path
    * with `vcc_missing_for_view_gt_0` on the next round. Trust model: the rumor envelope is signed by the gossip layer; the VCC itself
    * carries the per-vote `Signed[ViewChangeVote]` proofs that validators re-verify at proposal-acceptance time. Storing a malformed VCC
    * locally cannot finalize a round on its own -- it can only lead to this node's leadership turn failing its own proposal validation,
    * which is no worse than the current behaviour with `vcc_missing`.
    */
  private def handleAssembledVcc(origin: PeerId, vc: ConsensusAssembledVcc[_]): F[Unit] = {
    val key = vc.key.asInstanceOf[Key]
    storage.observePeerAtKey(origin, key) >>
      ConsensusLog.info(
        log,
        Category.Phase,
        key.toString,
        "n/a",
        LogEvent.ViewChange,
        "received" -> "assembled_vcc",
        "from" -> ConsensusLog.pid(origin),
        "fromView" -> vc.vcc.fromView.toString,
        "toView" -> vc.vcc.toView.toString
      ) >>
      storage.storeAssembledVcc(key, vc.vcc)
  }
}
