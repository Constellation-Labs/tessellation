package org

import java.security.PublicKey

import cats.effect.Async

import org.tesselation.keytool.security.{SecurityProvider, hexToPublicKey}
import org.tesselation.schema.peer.PeerId

package object tesselation {

  implicit class PeerIdToPublicKey(id: PeerId) {

    def toPublic[F[_]: Async: SecurityProvider]: F[PublicKey] =
      hexToPublicKey(id.value)
  }

}
