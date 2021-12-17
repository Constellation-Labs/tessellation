package org.tessellation.schema
import cats.Order
import cats.syntax.semigroup._

import org.tessellation.schema.address.Address
import org.tessellation.security.Encodable
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegBigInt, PosBigInt}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._

object transaction {

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class TransactionAmount(value: PosBigInt)

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class TransactionFee(value: NonNegBigInt)

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class TransactionOrdinal(value: NonNegBigInt) {
    def next: TransactionOrdinal = TransactionOrdinal(value |+| BigInt(1))
  }

  object TransactionOrdinal {
    val first: TransactionOrdinal = TransactionOrdinal(BigInt(1))
  }

  @derive(decoder, encoder, eqv, show)
  case class TransactionReference(hash: Hash, ordinal: TransactionOrdinal)

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class TransactionSalt(value: Long)

  // TODO: figure out the Fiber usage for transaction
  @derive(decoder, encoder, eqv, show)
  case class Transaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    //TODO: check if we can remove the salt and still have hash compatible with bolos app (Ledger)
    salt: TransactionSalt
  ) extends Encodable {
    import Transaction._

    // WARN: Transactions hash needs to be calculated with Kryo instance having setReferences=true, to be backward compatible
    override def toEncode: String =
      "2" +
        runLengthEncoding(
          Seq(
            source.coerce,
            destination.coerce,
            amount.coerce.value.toString(16),
            parent.hash.coerce,
            parent.ordinal.coerce.value.toString(),
            fee.coerce.value.toString(),
            salt.coerce.toHexString
          )
        )

    val ordinal: TransactionOrdinal = parent.ordinal.next
  }

  implicit val signedTransactionOrder: Order[Signed[Transaction]] = (x: Signed[Transaction], y: Signed[Transaction]) =>
    implicitly[Order[BigInt]].compare(x.value.ordinal.coerce, y.value.ordinal.coerce)

  implicit val signedTransactionOrdering: Ordering[Signed[Transaction]] = signedTransactionOrder.toOrdering

  object Transaction {
    def runLengthEncoding(hashes: Seq[String]): String = hashes.fold("")((acc, hash) => s"$acc${hash.length}$hash")
  }

  object TransactionReference {
    val empty: TransactionReference = TransactionReference(Hash(""), TransactionOrdinal(BigInt(0L)))
  }
}
