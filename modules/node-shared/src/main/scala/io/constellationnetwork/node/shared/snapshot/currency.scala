package io.constellationnetwork.node.shared.snapshot

import io.constellationnetwork.currency.dataApplication.DataTransaction
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationBlock
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.currencyMessage.CurrencyMessage
import io.constellationnetwork.security.signature.Signed

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

object currency {

  @derive(eqv)
  sealed trait CurrencySnapshotEvent

  object CurrencySnapshotEvent {
    implicit def decoder(implicit d: Decoder[DataTransaction]): Decoder[CurrencySnapshotEvent] = deriveDecoder

    implicit def encoder(implicit e: Encoder[DataTransaction]): Encoder[CurrencySnapshotEvent] = deriveEncoder
  }

  @derive(encoder, decoder, eqv)
  case class BlockEvent(value: Signed[Block]) extends CurrencySnapshotEvent

  @derive(eqv)
  case class DataApplicationBlockEvent(value: Signed[DataApplicationBlock]) extends CurrencySnapshotEvent

  object DataApplicationBlockEvent {
    implicit def decoder(implicit d: Decoder[DataTransaction]): Decoder[DataApplicationBlockEvent] = deriveDecoder

    implicit def encoder(implicit e: Encoder[DataTransaction]): Encoder[DataApplicationBlockEvent] = deriveEncoder
  }

  @derive(encoder, decoder, eqv)
  case class CurrencyMessageEvent(value: Signed[CurrencyMessage]) extends CurrencySnapshotEvent

  type CurrencySnapshotArtifact = CurrencyIncrementalSnapshot
}
