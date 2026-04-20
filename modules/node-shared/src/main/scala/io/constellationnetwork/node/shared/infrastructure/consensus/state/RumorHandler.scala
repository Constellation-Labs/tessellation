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
      case a: ConsensusPeerDeclarationAck[_, _]      => handleDeclarationAck(origin, a)
      case w: ConsensusWithdrawPeerDeclaration[_, _] => handleWithdrawDeclaration(origin, w)
      case ConsensusArtifact(key, artifact)          => handleArtifact(key.asInstanceOf[Key], artifact.asInstanceOf[Artifact])
      case other                                     => log.warn(s"Unknown peer rumor content: $other")
    }

  private def processCommonRumor(rumor: CommonRumor[_]): F[Unit] =
    rumor.content match {
      case ConsensusArtifact(key, artifact) => handleArtifact(key.asInstanceOf[Key], artifact.asInstanceOf[Artifact])
      case other                            => log.warn(s"Unknown common rumor content: $other")
    }

  private def handleDeclaration(origin: PeerId, decl: ConsensusPeerDeclaration[_, _]): F[Unit] = {
    val key = decl.key.asInstanceOf[Key]
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
    logReceipt >> op.flatMap(triggerUpdateIfChanged(queue, key))
  }

  private def handlePeerVote(origin: PeerId, v: ConsensusPeerVote[_]): F[Unit] = {
    val key = v.key.asInstanceOf[Key]
    val signedVote = v.vote
    val fromView = signedVote.value.fromView
    val toView = signedVote.value.toView
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

  private def handleDeclarationAck(origin: PeerId, ack: ConsensusPeerDeclarationAck[_, _]): F[Unit] = {
    val key = ack.key.asInstanceOf[Key]
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
