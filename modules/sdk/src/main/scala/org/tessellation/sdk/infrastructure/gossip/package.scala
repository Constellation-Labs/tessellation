package org.tessellation.sdk.infrastructure

import cats.data.{Kleisli, OptionT}

import org.tessellation.schema.gossip.Rumor
import org.tessellation.sdk.domain.gossip.RumorStorage

package object gossip {

  type RumorHandler[F[_]] = Kleisli[OptionT[F, *], (Rumor, RumorStorage[F]), Unit]

}
