package io.constellationnetwork.currency.l0.snapshot.synchronous

import cats.effect.Async
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.currency.l0.snapshot.synchronous.declaration._
import io.constellationnetwork.currency.l0.snapshot.synchronous.message._
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.security.HasherSelector

import io.circe.Decoder

class ConsensusRumorHandlers[F[
  _
]: Async: HasherSelector, Event, Key: TypeTag: Decoder, Artifact: TypeTag: Decoder, Context, Status, Outcome, Kind: Decoder: TypeTag](
  storage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
  manager: ConsensusManager[F, Key, Artifact, Context, Status, Outcome, Kind],
  expectedDomain: Key => F[Option[AttemptDomain]]
) {

  def checkForStateUpdate(key: Key)(maybeResources: Option[ConsensusResources[Artifact, Kind]]): F[Unit] =
    maybeResources.traverse(manager.checkForStateUpdate(key)).void

  val facilityHandler: RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Facility]]() { rumor =>
      expectedDomain(rumor.content.key).flatMap { domain =>
        storage.addFacility(rumor.origin, rumor.content.key, rumor.content.declaration, domain) >>=
          checkForStateUpdate(rumor.content.key)
      }
    }

  val proposalHandler: RumorHandler[F] = RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Proposal]]() { rumor =>
    expectedDomain(rumor.content.key).flatMap { domain =>
      storage.addProposal(rumor.origin, rumor.content.key, rumor.content.declaration, domain) >>=
        checkForStateUpdate(rumor.content.key)
    }
  }

  val artifactHandler: RumorHandler[F] = RumorHandler.fromCommonRumorConsumer[F, ConsensusArtifact[Key, Artifact]] { rumor =>
    HasherSelector[F].withCurrent { implicit hasher =>
      storage.addArtifact(rumor.content.key, rumor.content.artifact)
    } >>=
      checkForStateUpdate(rumor.content.key)
  }

  val signatureHandler: RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, MajoritySignature]]() { rumor =>
      expectedDomain(rumor.content.key).flatMap { domain =>
        storage.addSignature(rumor.origin, rumor.content.key, rumor.content.declaration, domain) >>=
          checkForStateUpdate(rumor.content.key)
      }
    }

  val binarySignatureHandler: RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, BinarySignature]]() { rumor =>
      expectedDomain(rumor.content.key).flatMap { domain =>
        storage.addBinarySignature(rumor.origin, rumor.content.key, rumor.content.declaration, domain) >>=
          checkForStateUpdate(rumor.content.key)
      }
    }

  val peerDeclarationAckHandler: RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclarationAck[Key, Kind]]() { rumor =>
      expectedDomain(rumor.content.key).flatMap { domain =>
        storage.addPeerDeclarationAck(
          rumor.origin,
          rumor.content.key,
          rumor.content.kind,
          rumor.content.ack,
          rumor.content.domain,
          domain
        ) >>= checkForStateUpdate(rumor.content.key)
      }
    }

  val withdrawPeerDeclarationHandler: RumorHandler[F] =
    RumorHandler.fromPeerRumorConsumer[F, ConsensusWithdrawPeerDeclaration[Key, Kind]]() { rumor =>
      storage.addWithdrawPeerDeclaration(rumor.origin, rumor.content.key, rumor.content.kind) >>=
        checkForStateUpdate(rumor.content.key)
    }
}
