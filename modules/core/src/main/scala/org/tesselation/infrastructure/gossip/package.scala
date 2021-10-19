package org.tesselation.infrastructure

import cats.data.{Kleisli, OptionT}

import org.tesselation.crypto.Signed
import org.tesselation.schema.gossip.Rumor

package object gossip {

  type RumorHandler[F[_]] = Kleisli[OptionT[F, *], Signed[Rumor], Unit]

}
