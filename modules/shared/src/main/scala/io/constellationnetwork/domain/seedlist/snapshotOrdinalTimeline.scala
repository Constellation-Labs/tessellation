package io.constellationnetwork.domain.seedlist

import cats.data.{NonEmptyList, Validated}
import cats.syntax.all._

import fs2.data.csv.{CellDecoder, DecoderError}
import io.estatico.newtype.macros.newtype

object snapshotOrdinalTimeline {

  @newtype
  case class SnapshotOrdinalTimeline(values: NonEmptyList[Range])

  implicit val optionalSnapshotOrdinalTimelineCellDecoder: CellDecoder[Option[SnapshotOrdinalTimeline]] =
    CellDecoder.stringDecoder.emap { s =>
      decode(s)
        .leftMap(
          _.map(_.getMessage).toList
            .mkString("; ")
        )
        .leftMap(new DecoderError(_))
        .map(_.some)
        .toEither
    }

  def decode(input: String): Validated[NonEmptyList[Throwable], SnapshotOrdinalTimeline] =
    ranges
      .decode(input)
      .map(SnapshotOrdinalTimeline(_))

}
