package org.tessellation.infrastructure

import cats.data.{Kleisli, OptionT}

import org.tessellation.domain.gossip.RumorStorage
import org.tessellation.schema.gossip.Rumor

package object gossip {

  type RumorHandler[F[_]] = Kleisli[OptionT[F, *], (Rumor, RumorStorage[F]), Unit]

}
