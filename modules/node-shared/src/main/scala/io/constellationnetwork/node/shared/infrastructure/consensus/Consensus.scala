package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.kernel.Ref

import io.constellationnetwork.node.shared.domain.consensus.ConsensusFunctions
import io.constellationnetwork.node.shared.infrastructure.consensus.engine.{ConsensusHealthStatus, ConsensusManager}
import io.constellationnetwork.node.shared.infrastructure.gossip.RumorHandler

class Consensus[F[_], Event, Key, Artifact, Context, Status, Outcome, Kind](
  val handler: RumorHandler[F],
  val storage: ConsensusStorage[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
  val manager: ConsensusManager[F, Event, Key, Artifact, Context, Status, Outcome, Kind],
  val routes: ConsensusRoutes[F, Key, Artifact, Context, Status, Outcome, Kind],
  val consensusFns: ConsensusFunctions[F, Event, Key, Artifact, Context],
  val healthRef: Option[Ref[F, ConsensusHealthStatus]] = None,
  val triggerEventConsensus: Option[F[Unit]] = None,
  /** Locally persisted, already validated historical outcomes. Global L0 uses this to resume an exact certified outcome during a cold
    * rollback start; other layers retain the inert default.
    */
  val historicalOutcome: Option[Key => F[Option[Outcome]]] = None,
  /** Layer trust-boundary validation for a locally persisted outcome before it is installed as a rollback parent. Global L0 supplies the
    * same certified-lineage validator used by downloads.
    */
  val validateOutcomeForInitialization: Option[Outcome => F[Unit]] = None
)
