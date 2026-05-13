package io.constellationnetwork.node.shared.domain.cluster.services

import io.constellationnetwork.schema.cluster.ClusterSessionToken
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest(implicit hasher: Hasher[F]): F[RegistrationRequest]
  def signRequest(signRequest: SignRequest)(implicit hasher: Hasher[F]): F[Signed[SignRequest]]

  /** Soft leave: subject to the wedge guard. Refuses with `ClusterLeaveRefused` when the consensus layer reports a sustained
    * quorum-infeasible wedge with no peer ahead AND self is in {Observing, WaitingForReady, Ready}. Use `leave(force = true)` to bypass.
    */
  def leave(): F[Unit]

  /** Operator-forced leave. Bypasses the wedge guard. Always proceeds with the Leaving -> Offline -> restart flow. */
  def leave(force: Boolean): F[Unit]

  def info(implicit hasher: Hasher[F]): F[Set[PeerInfo]]

  def createSession: F[ClusterSessionToken]
}
