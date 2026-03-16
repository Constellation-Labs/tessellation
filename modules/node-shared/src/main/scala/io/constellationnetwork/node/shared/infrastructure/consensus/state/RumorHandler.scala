package io.constellationnetwork.node.shared.infrastructure.consensus.state

import cats.effect.kernel.Async
import cats.syntax.all._

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.ConsensusCommand._
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
  *       │                    ├── ConsensusEvent → storage.addEvent() or addTriggerEvent()
  *       │                    │
  *       │                    └── ConsensusArtifact → storage.addArtifact()
  *       │
  *       └── CommonRumor? → processCommonRumor()
  *
  *   After storing: triggerUpdateIfChanged(key) → queue.offer(CheckUpdate(key))
  * }}}
  *
  * ==Key Method==
  *
  * '''process(rumor):''' Routes to appropriate handler based on rumor type
  */
class RumorHandler[F[_]: Async: HasherSelector, Event, Key, Artifact, Ctx, Status, Outcome, Kind](
  ctx: ConsensusEngineContext[F, Event, Key, Artifact, Ctx, Status, Outcome, Kind]
) {

  import ctx.{fns, logger => log, queue, storage}
  import ConsensusHelpers.triggerUpdateIfChanged

  def process(rumor: Either[PeerRumor[_], CommonRumor[_]]): F[Unit] =
    rumor.fold(processPeerRumor, processCommonRumor)

  private def processPeerRumor(rumor: PeerRumor[_]): F[Unit] =
    rumor match {
      case PeerRumor(origin, ordinal, content) => dispatchContent(origin, ordinal, content)
      case other                               => log.warn(s"Unknown rumor wrapper: ${other.getClass.getSimpleName}")
    }

  private def dispatchContent(origin: PeerId, ordinal: Ordinal, content: Any): F[Unit] =
    content match {
      case d: ConsensusPeerDeclaration[_, _]         => handleDeclaration(origin, d)
      case a: ConsensusPeerDeclarationAck[_, _]      => handleDeclarationAck(origin, a)
      case w: ConsensusWithdrawPeerDeclaration[_, _] => handleWithdrawDeclaration(origin, w)
      case ConsensusEvent(event)                     => handleEvent(Some(origin -> ordinal), event.asInstanceOf[Event])
      case ConsensusArtifact(key, artifact)          => handleArtifact(key.asInstanceOf[Key], artifact.asInstanceOf[Artifact])
      case other                                     => log.warn(s"Unknown peer rumor content: ${other.getClass.getSimpleName}")
    }

  private def processCommonRumor(rumor: CommonRumor[_]): F[Unit] =
    rumor.content match {
      case ConsensusArtifact(key, artifact) => handleArtifact(key.asInstanceOf[Key], artifact.asInstanceOf[Artifact])
      case ConsensusEvent(event) if fns.triggerPredicate(event.asInstanceOf[Event]) => queue.offer(FacilitateByEvent)
      case ConsensusEvent(_)                                                        => Async[F].unit
      case other => log.warn(s"Unknown common rumor content: ${other.getClass.getSimpleName}")
    }

  private def handleDeclaration(origin: PeerId, decl: ConsensusPeerDeclaration[_, _]): F[Unit] = {
    val key = decl.key.asInstanceOf[Key]
    val op = decl.declaration match {
      case f: Facility          => storage.addFacility(origin, key, f)
      case p: Proposal          => storage.addProposal(origin, key, p)
      case s: MajoritySignature => storage.addSignature(origin, key, s)
      case b: BinarySignature   => storage.addBinarySignature(origin, key, b)
      case other =>
        new IllegalArgumentException(s"Unexpected declaration: ${other.getClass.getName}").raiseError[F, Option[Any]]
    }
    op.flatMap(triggerUpdateIfChanged(queue, key))
  }

  private def handleDeclarationAck(origin: PeerId, ack: ConsensusPeerDeclarationAck[_, _]): F[Unit] = {
    val key = ack.key.asInstanceOf[Key]
    storage
      .addPeerDeclarationAck(origin, key, ack.kind.asInstanceOf[Kind], ack.ack)
      .flatMap(triggerUpdateIfChanged(queue, key))
  }

  private def handleWithdrawDeclaration(origin: PeerId, w: ConsensusWithdrawPeerDeclaration[_, _]): F[Unit] = {
    val key = w.key.asInstanceOf[Key]
    storage
      .addWithdrawPeerDeclaration(origin, key, w.kind.asInstanceOf[Kind])
      .flatMap(triggerUpdateIfChanged(queue, key))
  }

  private def handleEvent(source: Option[(PeerId, Ordinal)], event: Event): F[Unit] =
    source match {
      case Some((origin, ordinal)) =>
        if (fns.triggerPredicate(event))
          storage.addTriggerEvent(origin, (ordinal, event)) *> queue.offer(FacilitateByEvent)
        else
          storage.addEvent(origin, (ordinal, event))
      case None =>
        if (fns.triggerPredicate(event)) queue.offer(FacilitateByEvent)
        else Async[F].unit
    }

  private def handleArtifact(key: Key, artifact: Artifact): F[Unit] =
    HasherSelector[F].withCurrent { implicit h =>
      storage.addArtifact(key, artifact)
    }.flatMap(triggerUpdateIfChanged(queue, key))
}
