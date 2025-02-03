package io.constellationnetwork.schema

import java.security.PublicKey

import cats.effect.Async
import cats.syntax.functor._

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.SecurityProvider
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops._

import derevo.cats.{eqv, order, show}
import derevo.circe.magnolia._
import derevo.derive
import derevo.scalacheck.arbitrary
import io.estatico.newtype.macros.newtype

object ID {

  @derive(arbitrary, decoder, encoder, keyDecoder, keyEncoder, eqv, show, order, ordering)
  @newtype
  case class Id(hex: Hex) {
    def toPublicKey[F[_]: Async: SecurityProvider]: F[PublicKey] = hex.toPublicKey

    def toAddress[F[_]: Async: SecurityProvider]: F[Address] = toPublicKey.map(_.toAddress)
  }

  implicit class IdOps(id: Id) {
    def toPeerId: PeerId = PeerId._Id.reverseGet(id)
  }
}
