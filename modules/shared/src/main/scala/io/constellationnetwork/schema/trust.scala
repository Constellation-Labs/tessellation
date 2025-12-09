package io.constellationnetwork.schema

import cats.Show
import cats.syntax.either._
import cats.syntax.eq._

import io.constellationnetwork.schema.peer.PeerId

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.api.Refined
import eu.timepit.refined.cats._
import eu.timepit.refined.numeric.Interval
import eu.timepit.refined.refineV
import fs2.data.csv.{CellDecoder, DecoderError}
import io.circe.refined._
import io.estatico.newtype.macros.newtype

object trust {

  type TrustValueRefinement = Interval.Closed[-1.0, 1.0]
  type TrustValueRefined = Double Refined TrustValueRefinement

  implicit def showTrustValue: Show[TrustValueRefined] = s => s"TrustValue(value=${s.value})"

  implicit val trustValueRefinedCellDecoder: CellDecoder[TrustValueRefined] =
    CellDecoder.doubleDecoder.emap {
      refineV[TrustValueRefinement](_)
        .leftMap(new DecoderError(_))
    }
  @derive(show)
  @newtype
  case class Score(value: TrustValueRefined)

  @derive(show)
  @newtype
  case class Rating(value: TrustValueRefined)

  @derive(show)
  @newtype
  case class ObservationAdjustment(value: TrustValueRefined)

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
    scores: Map[PeerId, Double]
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
