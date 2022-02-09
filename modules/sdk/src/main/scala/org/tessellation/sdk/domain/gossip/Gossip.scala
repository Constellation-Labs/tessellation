package org.tessellation.sdk.domain.gossip

import scala.reflect.runtime.universe.TypeTag

trait Gossip[F[_]] {

  def spread[A <: AnyRef: TypeTag](rumorContent: A): F[Unit]

  def spreadCommon[A <: AnyRef: TypeTag](rumorContent: A): F[Unit]

}
