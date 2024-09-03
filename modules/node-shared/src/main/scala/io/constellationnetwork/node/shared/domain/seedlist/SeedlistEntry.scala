package io.constellationnetwork.node.shared.domain.seedlist

import cats.Order
import cats.syntax.all._

import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry.Alias
import io.constellationnetwork.node.shared.domain.seedlist.snapshotOrdinalTimeline.SnapshotOrdinalTimeline
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.trust._

import com.comcast.ip4s.{IpAddress, Port}
import derevo.cats.eqv
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.And
import eu.timepit.refined.cats._
import eu.timepit.refined.collection.NonEmpty
import eu.timepit.refined.refineV
import eu.timepit.refined.string.Trimmed
import fs2.data.csv._
import fs2.data.csv.generic.semiauto.deriveRowDecoder
import io.estatico.newtype.macros.newtype

case class ConnectionInfo(ipAddress: IpAddress, p2pPort: Port)

case class SeedlistEntry(
  peerId: PeerId,
  connectionInfo: Option[ConnectionInfo],
  alias: Option[Alias],
  bias: Option[TrustValueRefined],
  ordinalTimeline: Option[SnapshotOrdinalTimeline]
)

object SeedlistEntry {

  type AliasRefinement = Trimmed And NonEmpty
  private type AliasRefined = String Refined AliasRefinement

  @derive(eqv)
  @newtype
  case class Alias(value: AliasRefined)

  object Alias {

    implicit val optionalAliasCellDecoder: CellDecoder[Option[Alias]] =
      CellDecoder.stringDecoder.map { s =>
        refineV[AliasRefinement](s.trim).map(Alias(_)).toOption
      }

  }

  implicit val maybeIpAddressCellDecoder: CellDecoder[Option[IpAddress]] =
    CellDecoder.fromString(IpAddress.fromString)

  implicit val maybePortCellDecoder: CellDecoder[Option[Port]] =
    CellDecoder.fromString(Port.fromString)

  implicit object SeedlistRowDecoder extends RowDecoder[SeedlistEntry] {

    val decoder5Fields: RowDecoder[
      (
        PeerId,
        Option[IpAddress],
        Option[Port],
        Option[Alias],
        Option[TrustValueRefined]
      )
    ] =
      deriveRowDecoder

    val decoder6Fields: RowDecoder[
      (
        PeerId,
        Option[IpAddress],
        Option[Port],
        Option[Alias],
        Option[TrustValueRefined],
        Option[SnapshotOrdinalTimeline]
      )
    ] =
      deriveRowDecoder

    def apply(row: Row): DecoderResult[SeedlistEntry] =
      row.values.size match {
        case 1 =>
          CellDecoder[PeerId].apply(row.values.head).map {
            SeedlistEntry(_, none, none, none, none)
          }
        case 5 =>
          decoder5Fields(row).map {
            case (peerId, maybeIpAddress, maybePort, maybeAlias, maybeBias) =>
              SeedlistEntry(
                peerId,
                (maybeIpAddress, maybePort).mapN(ConnectionInfo),
                maybeAlias,
                maybeBias,
                none
              )
          }
        case 6 =>
          decoder6Fields(row).map {
            case (peerId, maybeIpAddress, maybePort, maybeAlias, maybeBias, maybeOrdinalTimeline) =>
              SeedlistEntry(
                peerId,
                (maybeIpAddress, maybePort).mapN(ConnectionInfo),
                maybeAlias,
                maybeBias,
                maybeOrdinalTimeline
              )
          }
        case _ => Left(new DecoderError(s"Rows must have 1, 5, or 6 fields, but found ${row.values.size}"))
      }
  }

  implicit val order: Order[SeedlistEntry] = Order[PeerId].contramap(_.peerId)
  implicit val ordering: Ordering[SeedlistEntry] = order.toOrdering
}
