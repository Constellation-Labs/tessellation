package org.tessellation.sdk.infrastructure.consensus

import cats.Show
import cats.effect.Async
import cats.syntax.flatMap._
import cats.syntax.semigroupk._
import cats.syntax.show._

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.gossip.PeerRumor
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.infrastructure.consensus.declaration._
import org.tessellation.sdk.infrastructure.consensus.message._
import org.tessellation.sdk.infrastructure.gossip.RumorHandler
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed

import org.typelevel.log4cats.slf4j.Slf4jLogger

object ConsensusHandler {

  def make[F[_]: Async: KryoSerializer: SecurityProvider, Event <: AnyRef: TypeTag: ClassTag, Key: Show: TypeTag: ClassTag, Artifact <: AnyRef: TypeTag](
    storage: ConsensusStorage[F, Event, Key, Artifact],
    manager: ConsensusManager[F, Event, Key, Artifact],
    whitelisting: Option[Set[PeerId]]
  ): RumorHandler[F] = {

    val logger = Slf4jLogger.getLogger[F]

    def filterWhitelisted[A](fn: PeerRumor[A] => F[Unit]): PeerRumor[A] => F[Unit] =
      rumor =>
        if (whitelisting.forall(_.contains(rumor.origin)))
          fn(rumor)
        else
          logger.warn(s"Non whitelisted peer ${rumor.origin} cannot participate in consensus")

    val eventHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusEvent[Event]]() {
      filterWhitelisted { rumor =>
        storage.addEvent(rumor.origin, (rumor.ordinal, rumor.content.value)) >>
          manager.checkForTrigger(rumor.content.value)
      }
    }

    val facilityHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Facility]]() {
        filterWhitelisted { rumor =>
          storage.addFacility(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
            manager.checkForStateUpdate(rumor.content.key)
        }
      }

    val proposalHandler = RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, Proposal]]() {
      filterWhitelisted { rumor =>
        storage.addProposal(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
          manager.checkForStateUpdate(rumor.content.key)
      }
    }

    val artifactHandler = RumorHandler.fromCommonRumorConsumer[F, ConsensusArtifact[Key, Artifact]] { rumor =>
      storage.addArtifact(rumor.content.key, rumor.content.artifact) >>=
        manager.checkForStateUpdate(rumor.content.key)
    }

    val majoritySignatureHandler =
      RumorHandler.fromPeerRumorConsumer[F, ConsensusPeerDeclaration[Key, MajoritySignature]]() {
        filterWhitelisted { rumor =>
          storage.addSignature(rumor.origin, rumor.content.key, rumor.content.declaration) >>=
            manager.checkForStateUpdate(rumor.content.key)
        }
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
