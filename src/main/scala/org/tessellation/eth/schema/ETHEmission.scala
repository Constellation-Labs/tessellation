package org.tessellation.eth.schema

import org.tessellation.schema.Ω

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

case class ETHEmission(nativeETHTransaction: NativeETHTransaction, dagAddress: String) extends Ω {}
