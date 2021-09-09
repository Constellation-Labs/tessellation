package org.tessellation.eth.schema

import io.circe.{Decoder, Encoder}
import org.tessellation.schema.Ω
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}

import scala.util.Random

case class NativeETHTransaction(
  nonce: String,
  gasPrice: Long,
  startGas: Long,
  to: String,
  value: Long
)

object NativeETHTransaction {

  def getRandom(): NativeETHTransaction = NativeETHTransaction(
    Random.nextString(30),
    Random.nextLong(),
    Random.nextLong(),
    Random.nextString(20),
    Random.nextLong()
  )
}

case class ETHEmission(ethTransactionHex: String) extends Ω {}

object ETHEmission {
  implicit val decoder: Decoder[ETHEmission] = deriveDecoder
  implicit val encoder: Encoder[ETHEmission] = deriveEncoder
}
