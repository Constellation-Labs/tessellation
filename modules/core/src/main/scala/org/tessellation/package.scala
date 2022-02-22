package org

import java.security.PublicKey

import cats.effect.Async

import org.tessellation.schema.peer.PeerId
import org.tessellation.security.SecurityProvider

import io.estatico.newtype.ops._

package object tessellation {

  val coreKryoRegistrar: Map[Class[_], Int] = Map(
    // classOf[T] -> 700
  )

  implicit class PeerIdToPublicKey(id: PeerId) {

    def toPublic[F[_]: Async: SecurityProvider]: F[PublicKey] =
      id.coerce.toPublicKey
  }

}
