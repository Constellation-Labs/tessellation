package org

import java.security.PublicKey

import cats.effect.Async

import org.tesselation.schema.peer.PeerId
import org.tesselation.security.SecurityProvider

import io.estatico.newtype.ops._

package object tesselation {

  implicit class PeerIdToPublicKey(id: PeerId) {

    def toPublic[F[_]: Async: SecurityProvider]: F[PublicKey] =
      id.coerce.toPublicKey
  }

}
