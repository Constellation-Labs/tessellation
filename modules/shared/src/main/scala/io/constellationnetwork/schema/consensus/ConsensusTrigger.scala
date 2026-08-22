package io.constellationnetwork.schema.consensus

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

/** Consensus-round pacing cause carried in facilities, proposals and certified values.
  *
  * This wire type lives in shared because v35 public lineage evidence embeds the exact
  * certified ProposalValue. Moving it here changes neither its two JSON labels nor its
  * repository-Hasher encoding; node-shared retains source-compatible aliases.
  */
@derive(order, show, encoder, decoder)
sealed trait ConsensusTrigger

case object EventTrigger extends ConsensusTrigger
case object TimeTrigger extends ConsensusTrigger
