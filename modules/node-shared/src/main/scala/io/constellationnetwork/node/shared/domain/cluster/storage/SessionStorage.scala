package io.constellationnetwork.node.shared.domain.cluster.storage

import io.constellationnetwork.schema.cluster.SessionToken

trait SessionStorage[F[_]] {
  def createToken: F[SessionToken]
  def getToken: F[Option[SessionToken]]
  def clearToken: F[Unit]
}
