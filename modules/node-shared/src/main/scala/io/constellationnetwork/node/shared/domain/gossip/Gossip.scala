package io.constellationnetwork.node.shared.domain.gossip

import scala.reflect.runtime.universe.TypeTag

import io.constellationnetwork.schema.gossip.RumorRaw
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.Hashed

import io.circe.Encoder

trait Gossip[F[_]] {

  def spread[A: TypeTag: Encoder](rumorContent: A): F[Unit]

  def spreadCommon[A: TypeTag: Encoder](rumorContent: A): F[Unit]

  /** Spread via normal gossip AND push directly to specific peers for low-latency delivery.
    *
    * Falls back to gossip-only propagation if direct push fails for any peer.
    */
  def spreadDirect[A: TypeTag: Encoder](rumorContent: A, targets: Set[PeerId]): F[Unit]

  /** Register the direct push callback. Called after consensus client is created to break the circular dependency. */
  def setDirectPushFn(fn: Gossip.DirectPushFn[F]): F[Unit]

}

object Gossip {

  /** Callback for direct push delivery of consensus rumors. */
  type DirectPushFn[F[_]] = (Hashed[RumorRaw], Set[PeerId]) => F[Unit]
}
