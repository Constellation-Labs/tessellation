package io.constellationnetwork.kryo

import cats.syntax.all._

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import weaver.SimpleIOSuite

object CustomKryoSerializerSuite extends SimpleIOSuite {
  test("Should match exactly the hash from official Kryo serializer from Java 11") {
    val tx = Transaction(
      source = Address("DAG0KpQNqMsED4FC5grhFCBWG8iwU8Gm6aLhB9w5"),
      destination = Address("DAG0jfGbPHrkX9E1grPgTrSZHVZaYy8gqHeTjbaf"),
      amount = TransactionAmount(PosLong.unsafeFrom(1000000000L)),
      fee = TransactionFee.zero,
      parent = TransactionReference(
        ordinal = TransactionOrdinal.first,
        hash = Hash.empty
      ),
      salt = TransactionSalt(8894673975403785L)
    )
    val java11KryoTransactionHash = Hash("cb272c42cd09f593c39d834cf7e4165eb22d00210e0f2dbbca1e5be058498f54")

    for {
      customHash <- CustomKryoSerializer.hash(tx.toEncode)
    } yield expect(java11KryoTransactionHash === customHash)
  }
}
