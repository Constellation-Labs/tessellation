package org.tessellation.node.shared.infrastructure

import cats.data.{Kleisli, OptionT}

import org.tessellation.schema.gossip.RumorRaw
import org.tessellation.schema.peer.PeerId

package object gossip {

  type RumorHandler[F[_]] = Kleisli[OptionT[F, *], (RumorRaw, PeerId), Unit]

  val rumorLoggerName = "RumorLogger"

}
