package org.tessellation.node.shared.domain.cluster.services

import org.tessellation.schema.cluster.ClusterSessionToken
import org.tessellation.schema.peer._
import org.tessellation.security.signature.Signed

trait Cluster[F[_]] {
  def getRegistrationRequest: F[RegistrationRequest]
  def signRequest(signRequest: SignRequest): F[Signed[SignRequest]]
  def leave(): F[Unit]

  def info: F[Set[PeerInfo]]

  def createSession: F[ClusterSessionToken]
}
