package org.tesselation.schema

import org.tesselation.schema.peer.PeerId

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive

object trust {

  @derive(decoder, encoder, show)
  case class InternalTrustUpdate(id: PeerId, trust: Double)

  @derive(decoder, encoder, show)
  case class InternalTrustUpdateBatch(updates: List[InternalTrustUpdate])

  @derive(decoder, encoder, show)
  case class TrustInfo(
    trustLabel: Option[Double] = None,
    predictedTrust: Option[Double] = None,
    observationAdjustmentTrust: Option[Double] = None,
    peerLabels: Map[PeerId, Double] = Map.empty
  ) {

    val publicTrust: Option[Double] =
      trustLabel
        .map(t => Math.max(-1, t + observationAdjustmentTrust.getOrElse(0d)))
        .orElse(observationAdjustmentTrust.map(t => Math.max(-1, t)))
  }

  @derive(decoder, encoder, show)
  case class PublicTrust(
    labels: Map[PeerId, Double]
  )

}
