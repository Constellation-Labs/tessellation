package io.constellationnetwork.node.shared.infrastructure

import cats.data.{Kleisli, OptionT}

import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.peer.PeerId

package object gossip {

  type RumorHandler[F[_]] = Kleisli[OptionT[F, *], (RumorRaw, PeerId), Unit]

  val rumorLoggerName = "RumorLogger"

}
