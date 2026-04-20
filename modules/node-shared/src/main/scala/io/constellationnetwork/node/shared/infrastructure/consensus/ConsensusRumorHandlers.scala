package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.Async
import cats.effect.std.Queue
import cats.syntax.semigroupk._

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.infrastructure.consensus.declaration._
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusCommand, RumorHandlerWithQueue}
import io.constellationnetwork.node.shared.infrastructure.consensus.message._
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler
import io.constellationnetwork.security.HasherSelector

import io.circe.Decoder

class ConsensusRumorHandlers[F[
  _
]: Async: HasherSelector, Event: TypeTag: Decoder, Key: TypeTag: Decoder, Artifact: TypeTag: Decoder, Context, Status, Outcome, Kind: Decoder: TypeTag](
  queue: Queue[F, ConsensusCommand]
) {

  /** 1. Events */
  val eventHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusEvent[Event]](queue)

  /** 2. Facility */
  val facilityHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration[Key, Facility]](queue)

  /** 3. Proposal */
  val proposalHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration[Key, Proposal]](queue)

  /** 4. MajoritySignature */
  val signatureHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration[Key, MajoritySignature]](queue)

  /** 5. BinarySignature */
  val binarySignatureHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclaration[Key, BinarySignature]](queue)

  /** 6. PeerDeclarationAck */
  val ackHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerDeclarationAck[Key, Kind]](queue)

  /** 7. WithdrawPeerDeclaration */
  val withdrawHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusWithdrawPeerDeclaration[Key, Kind]](queue)

  /** 8. Artifact (Common rumor) */
  val artifactHandler: RumorHandler[F] =
    RumorHandlerWithQueue.common[F, ConsensusArtifact[Key, Artifact]](queue)

  /** 9. ViewChangeVote (signed, routed via ConsensusPeerVote — not ConsensusPeerDeclaration — so the per-vote Signed proof survives to the
    * VCC assembly stage).
    */
  val viewChangeVoteHandler: RumorHandler[F] =
    RumorHandlerWithQueue.peer[F, ConsensusPeerVote[Key]](queue)
}
