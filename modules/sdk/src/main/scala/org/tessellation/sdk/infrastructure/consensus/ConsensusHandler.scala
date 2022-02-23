package org.tessellation.sdk.infrastructure.consensus

import cats.Show
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.semigroupk._
import cats.syntax.show._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

object ConsensusHandler {

  def make[F[_]: Async: KryoSerializer: SecurityProvider, Event <: AnyRef: TypeTag: ClassTag, Key: Show: TypeTag: ClassTag, Artifact <: AnyRef: TypeTag](
    storage: ConsensusStorage[F, Event, Key, Artifact],
    manager: ConsensusManager[F, Event, Key, Artifact]
  ): RumorHandler[F] = {

    val logger = Slf4jLogger.getLogger[F]

    val eventHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusEvent[Event]]() { rumor =>
      storage.addEvent(rumor.origin, (rumor.ordinal, rumor.content.value)) >>
        manager.checkForTrigger(rumor.content.value)
    }

    val facilityHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusFacility[Key]]() { rumor =>
      storage.addFacility(rumor.origin, rumor.content.key, rumor.content.bound) >>= manager
        .checkForStateUpdate(
          rumor.content.key
        )
    }

    val proposalHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusProposal[Key]]() { rumor =>
      storage.addProposal(rumor.origin, rumor.content.key, rumor.content.hash) >>= manager
        .checkForStateUpdate(
          rumor.content.key
        )
    }

    val artifactHandler = RumorHandler.fromCommonRumorConsumer[F, ConsensusArtifact[Key, Artifact]] { rumor =>
      storage.addArtifact(rumor.content.key, rumor.content.artifact) >>= manager.checkForStateUpdate(
        rumor.content.key
      )
    }

    val majoritySignatureHandler = RumorHandler.fromPeerRumorConsumer[F, MajoritySignature[Key]]() { rumor =>
      storage.addSignature(rumor.origin, rumor.content.key, rumor.content.signature) >>= manager
        .checkForStateUpdate(
          rumor.content.key
        )
    }

    val signedArtifactHandler = RumorHandler.fromCommonRumorConsumer[F, ConsensusArtifact[Key, Signed[Artifact]]] {
      rumor =>
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
