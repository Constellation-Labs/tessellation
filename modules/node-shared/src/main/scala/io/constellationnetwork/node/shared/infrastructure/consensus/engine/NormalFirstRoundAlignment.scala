package io.constellationnetwork.node.shared.infrastructure.consensus.engine

import scala.collection.immutable.SortedSet

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.Facility
import io.constellationnetwork.schema.peer.PeerId

/** Layer-specific, process-local policy for aligning a normal post-bootstrap rollback round.
  *
  * This is deliberately not a consensus/configuration schema. `committeeOf` extracts the already-committed committee from the typed
  * outcome, while `facilityMatches` recognizes the layer's existing first-round Facility fields. The policy controls only when a local
  * process starts; it never changes the committee, quorum, declaration bytes, hashes, or state proof.
  *
  * Currency L0 and true bootstrap leave this policy absent. Global L0 enables it only after the carried bootstrap window has completed.
  */
final case class NormalFirstRoundAlignment[Key, Outcome](
  committeeOf: Outcome => Option[SortedSet[PeerId]],
  facilityMatches: (Key, Outcome, Facility) => Boolean
)
