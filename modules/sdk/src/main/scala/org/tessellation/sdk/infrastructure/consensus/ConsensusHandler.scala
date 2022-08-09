package org.tessellation.sdk.infrastructure.consensus

import cats.Show
import cats.effect.Async
import cats.syntax.all._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.domain.consensus.ConsensusFunctions
import org.tessellation.sdk.infrastructure.consensus.declaration._
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

object ConsensusHandler {

  def make[F[
    _
  ]: Async: KryoSerializer: SecurityProvider, Event <: AnyRef: TypeTag: ClassTag, Key: Show: TypeTag: ClassTag, Artifact <: AnyRef: TypeTag](
    storage: ConsensusStorage[F, Event, Key, Artifact],
    manager: ConsensusManager[F, Key, Artifact],
    fns: ConsensusFunctions[F, Event, Key, Artifact]
  ): RumorHandler[F] = {

    val logger = Slf4jLogger.getLogger[F]

    val eventHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusEvent[Event]]() { rumor =>
      if (fns.triggerPredicate(rumor.content.value))
        storage.addTriggerEvent(rumor.origin, (rumor.ordinal, rumor.content.value)) >>
          manager.facilitateOnEvent
      else
        storage.addEvent(rumor.origin, (rumor.ordinal, rumor.content.value))
    }

    val facilityHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Facility]]() { rumor =>
        storage.addFacility(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
          manager.checkForStateUpdate(rumor.content.key)
      }

    val proposalHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Proposal]]() { rumor =>
      storage.addProposal(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
        manager.checkForStateUpdate(rumor.content.key)
    }

    val artifactHandler = RumorHandler.fromCommonRumorConsumer[F, ConsensusArtifact[Key, Artifact]] { rumor =>
      storage.addArtifact(rumor.content.key, rumor.content.artifact) >>=
        manager.checkForStateUpdate(rumor.content.key)
    }

    val majoritySignatureHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, MajoritySignature]]() { rumor =>
        storage.addSignature(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
          manager.checkForStateUpdate(rumor.content.key)
      }

    val signedArtifactHandler = RumorHandler.fromCommonRumorConsumer[F, ConsensusArtifact[Key, Signed[Artifact]]] { rumor =>
      logger.info(s"Signed artifact received ${rumor.content.key.show}")
    }

    eventHandler <+>
      facilityHandler <+>
      proposalHandler <+>
      artifactHandler <+>
      majoritySignatureHandler <+>
      signedArtifactHandler
  }

}
