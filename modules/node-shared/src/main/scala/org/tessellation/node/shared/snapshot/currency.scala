package org.tessellation.node.shared.snapshot

import org.tessellation.currency.dataApplication.DataUpdate
import org.tessellation.currency.dataApplication.dataApplication.DataApplicationBlock
import org.tessellation.currency.schema.currency._
import org.tessellation.schema.Block
import org.tessellation.schema.currencyMessage.CurrencyMessage
import org.tessellation.security.signature.Signed

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.{Decoder, Encoder}

object currency {

  @derive(eqv)
  sealed trait CurrencySnapshotEvent

  object CurrencySnapshotEvent {
    implicit def decoder(implicit d: Decoder[DataUpdate]): Decoder[CurrencySnapshotEvent] = deriveDecoder

    implicit def encoder(implicit e: Encoder[DataUpdate]): Encoder[CurrencySnapshotEvent] = deriveEncoder
  }

  @derive(encoder, decoder, eqv)
  case class BlockEvent(value: Signed[Block]) extends CurrencySnapshotEvent

  @derive(eqv)
  case class DataApplicationBlockEvent(value: Signed[DataApplicationBlock]) extends CurrencySnapshotEvent

  object DataApplicationBlockEvent {
    implicit def decoder(implicit d: Decoder[DataUpdate]): Decoder[DataApplicationBlockEvent] = deriveDecoder

    implicit def encoder(implicit e: Encoder[DataUpdate]): Encoder[DataApplicationBlockEvent] = deriveEncoder
  }

  @derive(encoder, decoder, eqv)
  case class CurrencyMessageEvent(value: Signed[CurrencyMessage]) extends CurrencySnapshotEvent

  type CurrencySnapshotArtifact = CurrencyIncrementalSnapshot
}
