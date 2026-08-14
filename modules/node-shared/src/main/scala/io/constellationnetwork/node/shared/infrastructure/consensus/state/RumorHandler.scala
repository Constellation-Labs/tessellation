package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.Async
import cats.syntax.all._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog
import io.constellationnetwork.node.shared.infrastructure.consensus.ConsensusLog.{Category, Event => LogEvent}
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics.unsafeLabelName
import io.constellationnetwork.schema.gossip.{CommonRumor, Ordinal, PeerRumor}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.HasherSelector

import eu.timepit.refined.auto._

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
class RumorHandler[F[_]: Async: HasherSelector: Metrics, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
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
      case tv: ConsensusPeerTimeoutVote[_]           => handleTimeoutVote(origin, tv)
      case ov: ConsensusPeerOutcomeVote[_]           => handleOutcomeVote(origin, ov)
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
    // Per-declaration receipt: one line per (round, declaration) across every peer, so it scales with
    // committee size * declaration kinds * rounds and was a top log-volume source on large clusters
    // (IntegrationNet, v4.1.0). Demoted to debug -- aggregate receipt is tracked by the
    // `dag_rumors_consumed_total` / consensus declaration metrics, and the per-round FACILITATORS and
    // ADMISSION-gate summaries (still info) carry the diagnostic signal. Flip the
    // `io.constellationnetwork` logger to DEBUG to restore per-message tracing when deep-diagnosing.
    val logReceipt = ConsensusLog.debug(
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
    observeTip >> logReceipt >> op.flatMap(triggerUpdateIfChanged(queue, key)) >>
      schedulePostSignatureGraceCheck(key).whenA(kindLabel === "Signature" || kindLabel === "BinarySignature")
  }

  private def schedulePostSignatureGraceCheck(key: Key): F[Unit] =
    Async[F]
      .start(
        Async[F].sleep(ctx.config.signatureGracePeriod + 50.millis) >>
          queue.offer(ConsensusCommand.CheckUpdate(key))
      )
      .void

  private def handlePeerVote(origin: PeerId, v: ConsensusPeerVote[_]): F[Unit] = {
    val key = v.key.asInstanceOf[Key]
    val observeTip = storage.observePeerAtKey(origin, key)
    val signedVote = v.vote
    val fromView = signedVote.value.fromView
    val toView = signedVote.value.toView
    val signer = signedVote.proofs.head.id.toPeerId
    if (origin =!= signer) {
      // Parity with handleTimeoutVote: a relay must not inject a vote under a foreign signer's slot.
      observeTip >> ConsensusLog.warn(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationReceived,
        "kind" -> "ViewChangeVote",
        "rejected" -> "origin_signer_mismatch",
        "from" -> ConsensusLog.pid(origin),
        "signer" -> ConsensusLog.pid(signer),
        "fromView" -> fromView.toString,
        "toView" -> toView.toString
      )
    } else {
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
          .addViewChangeVote(signer, key, fromView, toView, signedVote)
          .flatMap(triggerUpdateIfChanged(queue, key)) >>
        queue.offer(ConsensusCommand.CheckViewChangeAssembly(key))
    }
  }

  private def handleTimeoutVote(origin: PeerId, v: ConsensusPeerTimeoutVote[_]): F[Unit] = {
    val key = v.key.asInstanceOf[Key]
    val observeTip = storage.observePeerAtKey(origin, key)
    val signedVote = v.vote
    val fromView = signedVote.value.fromView
    val toView = signedVote.value.toView
    val signer = signedVote.proofs.head.id.toPeerId
    if (origin =!= signer) {
      observeTip >> ConsensusLog.warn(
        log,
        Category.Facilitator,
        key.toString,
        "n/a",
        LogEvent.DeclarationReceived,
        "kind" -> "TimeoutVote",
        "rejected" -> "origin_signer_mismatch",
        "from" -> ConsensusLog.pid(origin),
        "signer" -> ConsensusLog.pid(signer),
        "fromView" -> fromView.toString,
        "toView" -> toView.toString
      )
    } else {
      observeTip >>
        ConsensusLog.info(
          log,
          Category.Facilitator,
          key.toString,
          "n/a",
          LogEvent.DeclarationReceived,
          "kind" -> "TimeoutVote",
          "from" -> ConsensusLog.pid(origin),
          "fromView" -> fromView.toString,
          "toView" -> toView.toString,
          "reason" -> signedVote.value.reason.toString
        ) >>
        storage
          .addTimeoutVote(origin, key, fromView, toView, signedVote)
          .flatMap(triggerUpdateIfChanged(queue, key)) >>
        queue.offer(ConsensusCommand.CheckTimeoutCertificateAssembly(key))
    }
  }

  private def handleOutcomeVote(origin: PeerId, v: ConsensusPeerOutcomeVote[_]): F[Unit] = {
    val key = v.key.asInstanceOf[Key]
    val vote = v.vote
    val signers = vote.proofs.toSortedSet.toList.map(_.id.toPeerId)

    storage.observePeerAtKey(origin, key) >>
      (signers match {
        case signer :: Nil if signer === origin =>
          ConsensusLog.debug(
            log,
            Category.Phase,
            key.toString,
            "n/a",
            LogEvent.DeclarationReceived,
            "kind" -> "OutcomeVote",
            "from" -> ConsensusLog.pid(origin),
            "view" -> vote.value.certifiedView.toString,
            "valueHash" -> vote.value.valueHash.show.take(12)
          ) >>
            storage.addOutcomeVote(origin, key, vote).flatMap(triggerUpdateIfChanged(queue, key))
        case _ =>
          ConsensusLog.warn(
            log,
            Category.Phase,
            key.toString,
            "n/a",
            LogEvent.DeclarationReceived,
            "kind" -> "OutcomeVote",
            "rejected" -> "origin_signer_mismatch_or_multiple_proofs",
            "from" -> ConsensusLog.pid(origin),
            "proofs" -> signers.size.toString
          )
      })
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
    storage.getState(key).flatMap { state =>
      val acceptsAtKey = ctx.membershipPolicy.acceptsEvictionVotesAt(state.exists(_.certifiedConsensusActive))

      if (!acceptsAtKey) {
        observeTip >> ConsensusLog.debug(
          log,
          Category.Facilitator,
          key.toString,
          "n/a",
          LogEvent.DeclarationReceived,
          "kind" -> "EvictionVote",
          "ignored" -> "membership_policy_or_inactive_v35",
          "from" -> ConsensusLog.pid(origin),
          "target" -> ConsensusLog.pid(target)
        )
      } else if (origin =!= signer) {
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
    * store it on this node, even if this node has not yet seen enough VCV votes locally to assemble its own.
    *
    * The VCC also rehydrates its signed votes into `resources.viewChangeVotes` and queues the normal assembly path. This makes a received
    * certificate an active pacemaker input: a peer that missed local quorum can still advance its view via the same builder/validation path
    * as peers that assembled the certificate locally. The VCC itself is not trusted as an imperative state mutation; malformed or
    * out-of-pool votes are rejected by `ViewChangeCertificateBuilder` in `CheckViewChangeAssembly`.
    */
  private def handleAssembledVcc(origin: PeerId, vc: ConsensusAssembledVcc[_]): F[Unit] = {
    val key = vc.key.asInstanceOf[Key]
    val votes = vc.vcc.votes.toSortedSet.toList
    val voteSigners = votes.map(_.proofs.head.id.toPeerId).toSet
    votes.map(_.value.lastSnapshotHash).toSet.toList match {
      case lastSnapshotHash :: Nil =>
        storage.observePeerAtKey(origin, key) >>
          storage.markAssembledVccReceived(key, origin, lastSnapshotHash, vc.vcc.fromView, vc.vcc.toView, voteSigners).flatMap {
            case true =>
              Metrics[F].incrementCounter(
                "dag_consensus_vcc_received_total",
                Seq(
                  unsafeLabelName("outcome") -> "processed",
                  unsafeLabelName("reason") -> "first_origin_parent_view"
                )
              ) >>
                ConsensusLog.info(
                  log,
                  Category.Phase,
                  key.toString,
                  "n/a",
                  LogEvent.ViewChange,
                  "received" -> "assembled_vcc",
                  "from" -> ConsensusLog.pid(origin),
                  "fromView" -> vc.vcc.fromView.toString,
                  "toView" -> vc.vcc.toView.toString,
                  "votes" -> votes.size.toString
                ) >>
                storage.storeAssembledVcc(key, vc.vcc) >>
                votes.traverse_ { signedVote =>
                  val signer = signedVote.proofs.head.id.toPeerId
                  storage.addViewChangeVote(signer, key, vc.vcc.fromView, vc.vcc.toView, signedVote).void
                } >>
                queue.offer(ConsensusCommand.CheckViewChangeAssembly(key))
            case false =>
              Metrics[F].incrementCounter(
                "dag_consensus_vcc_received_total",
                Seq(
                  unsafeLabelName("outcome") -> "suppressed",
                  unsafeLabelName("reason") -> "same_origin_parent_view"
                )
              )
          }
      case hashes =>
        ConsensusLog.warn(
          log,
          Category.Phase,
          key.toString,
          "n/a",
          LogEvent.ViewChange,
          "received" -> "assembled_vcc_rejected",
          "reason" -> "mixed_last_snapshot_hash",
          "from" -> ConsensusLog.pid(origin),
          "fromView" -> vc.vcc.fromView.toString,
          "toView" -> vc.vcc.toView.toString,
          "hashes" -> hashes.size.toString
        ) >>
          Metrics[F].incrementCounter(
            "dag_consensus_vcc_received_total",
            Seq(
              unsafeLabelName("outcome") -> "rejected",
              unsafeLabelName("reason") -> "mixed_last_snapshot_hash"
            )
          )
    }
  }
}
