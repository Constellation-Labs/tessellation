package org.tessellation.schema

import java.security.PublicKey

import cats.Show
import cats.effect.Async
import cats.syntax.functor._

import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops._

import derevo.cats.order
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary
import eu.timepit.refined.cats._
import io.circe.refined._
import io.estatico.newtype.macros.newtype

import hex._

object id {

  @derive(arbitrary, decoder, encoder, order)
  @newtype
  case class Id(hex: HexString128) {
    def toPublicKey[F[_]: Async: SecurityProvider]: F[PublicKey] = hex.toPublicKey[F]

    def toAddress[F[_]: Async: SecurityProvider]: F[Address] = toPublicKey.map(_.toAddress)
  }

  case object Id {
    implicit val show: Show[Id] = _.shortValue

    implicit class IdOps(id: Id) {
      def toPeerId: PeerId = PeerId._Id.reverseGet(id)

      def shortValue: String = id.hex.value.take(8)
    }
  }
}
