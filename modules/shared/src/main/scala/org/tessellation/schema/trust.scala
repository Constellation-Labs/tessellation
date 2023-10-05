package org.tessellation.schema

import cats.Show
import cats.syntax.either._
import cats.syntax.eq._

import org.tessellation.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.Interval
import eu.timepit.refined.refineV
import fs2.data.csv.{CellDecoder, DecoderError}
import io.circe.refined._
import io.estatico.newtype.macros.newtype
import io.getquill.MappedEncoding

object trust {

  type TrustValueRefinement = Interval.Closed[-1.0, 1.0]
  type TrustValueRefined = Double Refined TrustValueRefinement

  implicit def showTrustValue: Show[TrustValueRefined] = s => s"TrustValue(value=${s.value})"

  implicit val trustValueRefinedCellDecoder: CellDecoder[TrustValueRefined] =
    CellDecoder.doubleDecoder.emap {
      refineV[TrustValueRefinement](_)
        .leftMap(new DecoderError(_))
    }

  val defaultPeerTrustScore: TrustValueRefined = 1e-4

  @derive(show)
  @newtype
  case class Score(value: TrustValueRefined)

  object Score {

    implicit val quillEncode: MappedEncoding[Score, Double] =
      MappedEncoding[Score, Double](_.value.value)

    implicit val quillDecode: MappedEncoding[Double, Score] = MappedEncoding[Double, Score](
      refineV[TrustValueRefinement].apply[Double](_).leftMap(new Throwable(_)) match {
        case Left(err)    => throw err
        case Right(value) => Score(value)
      }
    )
  }

  @derive(show)
  @newtype
  case class Rating(value: TrustValueRefined)

  object Rating {

    implicit val quillEncode: MappedEncoding[Rating, Double] =
      MappedEncoding[Rating, Double](_.value.value)

    implicit val quillDecode: MappedEncoding[Double, Rating] = MappedEncoding[Double, Rating](
      refineV[TrustValueRefinement].apply[Double](_).leftMap(new Throwable(_)) match {
        case Left(err)    => throw err
        case Right(value) => Rating(value)
      }
    )
  }

  @derive(show)
  @newtype
  case class ObservationAdjustment(value: TrustValueRefined)

  object ObservationAdjustment {

    implicit val quillEncode: MappedEncoding[ObservationAdjustment, Double] =
      MappedEncoding[ObservationAdjustment, Double](_.value.value)

    implicit val quillDecode: MappedEncoding[Double, ObservationAdjustment] =
      MappedEncoding[Double, ObservationAdjustment](
        refineV[TrustValueRefinement].apply[Double](_).leftMap(new Throwable(_)) match {
          case Left(err)    => throw err
          case Right(value) => ObservationAdjustment(value)
        }
      )
  }

  @derive(show)
  case class TrustDbValues(
    peerId: PeerId,
    score: Option[Score],
    rating: Option[Rating],
    observationAdjustment: Option[ObservationAdjustment]
  )

  @derive(decoder, encoder, show, eqv)
  case class PeerObservationAdjustmentUpdate(id: PeerId, trust: TrustValueRefined)

  @derive(decoder, encoder, show, eqv)
  case class PeerObservationAdjustmentUpdateBatch(updates: List[PeerObservationAdjustmentUpdate])

  @derive(decoder, eqv, encoder, show)
  case class TrustInfo(
    trustLabel: Option[Double] = None,
    predictedTrust: Option[Double] = None,
    observationAdjustmentTrust: Option[Double] = None,
    peerLabels: Map[PeerId, Double] = Map.empty
  ) {

    lazy val publicTrust: Option[Double] =
      trustLabel
        .map(t => Math.max(-1, t + observationAdjustmentTrust.getOrElse(0d)))
        .orElse(observationAdjustmentTrust.map(t => Math.max(-1, t)))
  }

  @derive(eqv, decoder, encoder, show)
  case class TrustScores(
    value: Map[PeerId, Double]
  )

  object TrustScores {
    val empty: TrustScores = TrustScores(Map.empty[PeerId, Double])
  }

  @derive(decoder, encoder, show)
  case class TrustLabels(
    value: Map[PeerId, Double]
  )

  @derive(eqv, decoder, encoder, show)
  case class PublicTrust(
    labels: Map[PeerId, Double]
  ) {

    def isEmpty: Boolean = labels === Map.empty

  }

  object PublicTrust {
    val empty: PublicTrust = PublicTrust(Map.empty)
  }

  @derive(eqv, decoder, encoder, show)
  case class SnapshotOrdinalPublicTrust(
    ordinal: SnapshotOrdinal,
    labels: PublicTrust
  )
}
