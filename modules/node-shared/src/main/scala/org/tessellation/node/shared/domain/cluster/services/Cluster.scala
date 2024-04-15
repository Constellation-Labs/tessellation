package org.tessellation.node.shared.domain.cluster.services

import org.tessellation.schema.cluster.ClusterSessionToken
import org.tessellation.schema.peer._
import org.tessellation.security.Hasher
import org.tessellation.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest(implicit hasher: Hasher[F]): F[RegistrationRequest]
  def signRequest(signRequest: SignRequest)(implicit hasher: Hasher[F]): F[Signed[SignRequest]]
  def leave(): F[Unit]

  def info(implicit hasher: Hasher[F]): F[Set[PeerInfo]]

  def createSession: F[ClusterSessionToken]
}
