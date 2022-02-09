package org.tessellation.sdk.infrastructure

import cats.data.{Kleisli, OptionT}

import org.tessellation.schema.gossip.RumorBinary
import org.tessellation.schema.peer.PeerId

package object gossip {

  type RumorHandler[F[_]] = Kleisli[OptionT[F, *], (RumorBinary, PeerId), Unit]

}
