package org.tessellation.node.shared.infrastructure.consensus

import cats.Show
import cats.effect.Async
import cats.syntax.all._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.node.shared.domain.consensus.ConsensusFunctions
import org.tessellation.node.shared.infrastructure.consensus.declaration._
import org.tessellation.node.shared.infrastructure.consensus.message._
import org.tessellation.node.shared.infrastructure.gossip.RumorHandler
import org.tessellation.security.SecurityProvider

import io.circe.Decoder

object ConsensusHandler {

  def make[F[
    _
  ]: Async: SecurityProvider, Event: TypeTag: Decoder, Key: Show: TypeTag: Decoder, Artifact: TypeTag: Decoder, Context: Decoder](
    storage: ConsensusStorage[F, Event, Key, Artifact, Context],
    manager: ConsensusManager[F, Key, Artifact, Context],
    fns: ConsensusFunctions[F, Event, Key, Artifact, Context]
  ): RumorHandler[F] = {

    val eventHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusEvent[Event]]() { rumor =>
      if (fns.triggerPredicate(rumor.content.value))
        storage.addTriggerEvent(rumor.origin, (rumor.ordinal, rumor.content.value)) >>
          manager.facilitateOnEvent
      else
        storage.addEvent(rumor.origin, (rumor.ordinal, rumor.content.value))
    }

    def checkForStateUpdate(key: Key)(maybeResources: Option[ConsensusResources[Artifact]]): F[Unit] =
      maybeResources.traverse(manager.checkForStateUpdate(key)).void

    val facilityHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Facility]]() { rumor =>
        storage.addFacility(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
          checkForStateUpdate(rumor.content.key)
      }

    val proposalHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Proposal]]() { rumor =>
      storage.addProposal(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
        checkForStateUpdate(rumor.content.key)
    }

    val artifactHandler = RumorHandler.fromCommonRumorConsumer[F, ConsensusArtifact[Key, Artifact]] { rumor =>
      storage.addArtifact(rumor.content.key, rumor.content.artifact) >>=
        checkForStateUpdate(rumor.content.key)
    }

    val signatureHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, MajoritySignature]]() { rumor =>
        storage.addSignature(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
          checkForStateUpdate(rumor.content.key)
      }

    val peerDeclarationAckHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclarationAck[Key]]() { rumor =>
        storage.addPeerDeclarationAck(rumor.origin, rumor.content.key, rumor.content.kind, rumor.content.ack) >>=
          checkForStateUpdate(rumor.content.key)
      }

    val withdrawPeerDeclarationHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusWithdrawPeerDeclaration[Key]]() { rumor =>
        storage.addWithdrawPeerDeclaration(rumor.origin, rumor.content.key, rumor.content.kind) >>=
          checkForStateUpdate(rumor.content.key)
      }

    eventHandler <+>
      facilityHandler <+>
      proposalHandler <+>
      signatureHandler <+>
      peerDeclarationAckHandler <+>
      artifactHandler <+>
      withdrawPeerDeclarationHandler
  }

}
