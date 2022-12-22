package org.tessellation.schema

import java.security.PublicKey

import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.SecurityProvider
import org.tessellation.schema.security.hex.Hex
import org.tessellation.schema.security.key.ops._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import io.estatico.newtype.macros.newtype

object ID {

  @derive(arbitrary, decoder, encoder, eqv, show, order)
  @newtype
  case class Id(hex: Hex) {
    def toPublicKey[F[_]: Async: SecurityProvider]: F[PublicKey] = hex.toPublicKey

    def toAddress[F[_]: Async: SecurityProvider]: F[Address] = toPublicKey.map(_.toAddress)
  }

  implicit class IdOps(id: Id) {
    def toPeerId: PeerId = PeerId._Id.reverseGet(id)
  }
}
