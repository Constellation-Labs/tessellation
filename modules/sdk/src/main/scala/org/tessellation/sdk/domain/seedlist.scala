package org.tessellation.sdk.domain

import cats.syntax.contravariant._
import cats.syntax.either._
import cats.syntax.option._
import cats.{Order, Show}

import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.trust._

import com.comcast.ip4s.IpAddress
import derevo.cats.{eqv, show}
import derevo.derive
import fs2.data.csv._
import fs2.data.csv.generic.semiauto.deriveRowDecoder
import io.estatico.newtype.macros.newtype

object seedlist {

  @derive(eqv, show)
  @newtype
  case class Alias(value: String)

  object Alias {

    implicit val maybeAliasCellDecoder: CellDecoder[Option[Alias]] = CellDecoder.stringDecoder.map { stringValue =>
      if ("".equals(stringValue)) none else Alias(stringValue).some
    }

  }

  @derive(show)
  case class SeedlistEntry(
    peerId: PeerId,
    ipAddress: Option[IpAddress],
    alias: Option[Alias],
    bias: Option[TrustValueRefined]
  )

  object SeedlistEntry {

    implicit val showIpAddress: Show[IpAddress] = Show.show[IpAddress](_.toUriString)

    implicit val maybeIpAddressCellDecoder: CellDecoder[Option[IpAddress]] =
      CellDecoder.stringDecoder.emap { value =>
        if ("".equals(value))
          none.asRight[DecoderError]
        else
          IpAddress.fromString(value) match {
            case v @ Some(_) => v.asRight[DecoderError]
            case None        => Left(new DecoderError(s"Invalid IP address: $value"))
          }
      }

    implicit object SeedlistRowDecoder extends RowDecoder[SeedlistEntry] {

      val decoder4Fields: RowDecoder[(PeerId, Option[IpAddress], Option[Alias], Option[TrustValueRefined])] =
        deriveRowDecoder

      def apply(row: Row): DecoderResult[SeedlistEntry] =
        if (row.values.size == 1) {
          CellDecoder[PeerId].apply(row.values.head).map {
            SeedlistEntry(_, none, none, none)
          }
        } else if (row.values.size == 4) {
          decoder4Fields(row).map {
            case (peerId, maybeIpAddress, maybeAlias, maybeBias) =>
              SeedlistEntry(peerId, maybeIpAddress, maybeAlias, maybeBias)
          }
        } else {
          Left(new DecoderError(s"Rows must have 1 or 4 fields, but found ${row.values.size}"))
        }
    }

    implicit val order: Order[SeedlistEntry] = Order[PeerId].contramap(_.peerId)
    implicit val ordering: Ordering[SeedlistEntry] = order.toOrdering

  }

}
