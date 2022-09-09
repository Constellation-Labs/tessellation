package org.tessellation.sdk.domain.gossip

import scala.reflect.runtime.universe.TypeTag

import io.circe.Encoder

trait Gossip[F[_]] {

  def spread[A: TypeTag: Encoder](rumorContent: A): F[Unit]

  def spreadCommon[A: TypeTag: Encoder](rumorContent: A): F[Unit]

}
