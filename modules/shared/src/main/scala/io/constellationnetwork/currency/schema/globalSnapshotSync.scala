package io.constellationnetwork.currency.schema

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.SessionToken
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object globalSnapshotSync {

  @derive(eqv, show, encoder, decoder, order, ordering)
  case class GlobalSnapshotSync(ordinal: SnapshotOrdinal, hash: Hash, session: SessionToken)
}
