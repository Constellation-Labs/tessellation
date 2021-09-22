package org.tesselation.domain.cluster

import org.tesselation.schema.cluster.PeerToJoin
import org.tesselation.schema.peer.{JoinRequest, RegistrationRequest}
//import org.typelevel.ci.CIString

trait Cluster[F[_]] {
  def join(toPeer: PeerToJoin): F[Unit]
  def getRegistrationRequest: F[RegistrationRequest]
  def joinRequest(joinRequest: JoinRequest): F[Unit]
}
//
//object Cluster {
//  val `X-Session-Token` = CIString("X-Session-Token")
//}
