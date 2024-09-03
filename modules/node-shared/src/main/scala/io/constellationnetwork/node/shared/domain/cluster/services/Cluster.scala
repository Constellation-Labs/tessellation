package io.constellationnetwork.node.shared.domain.cluster.services

import io.constellationnetwork.schema.cluster.ClusterSessionToken
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest(implicit hasher: Hasher[F]): F[RegistrationRequest]
  def signRequest(signRequest: SignRequest)(implicit hasher: Hasher[F]): F[Signed[SignRequest]]
  def leave(): F[Unit]

  def info(implicit hasher: Hasher[F]): F[Set[PeerInfo]]

  def createSession: F[ClusterSessionToken]
}
