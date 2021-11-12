package org.tessellation.domain.gossip

import scala.reflect.runtime.universe.TypeTag

trait Gossip[F[_]] {

  def spread[A <: AnyRef: TypeTag](rumorContent: A): F[Unit]

}
