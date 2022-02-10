package org.tessellation.schema

import cats.Order
import cats.syntax.semigroup._

import org.tessellation.schema.address.Address
import org.tessellation.security.Encodable
import org.tessellation.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import eu.timepit.refined.auto.{autoRefineV, autoUnwrap}
import eu.timepit.refined.types.numeric.{NonNegBigInt, PosBigInt}
import io.estatico.newtype.macros.newtype
import io.estatico.newtype.ops._
import monocle.Lens
import monocle.macros.GenLens

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

  object TransactionReference {
    val empty: TransactionReference = TransactionReference(Hash(""), TransactionOrdinal(BigInt(0L)))

    val _Hash: Lens[TransactionReference, Hash] = GenLens[TransactionReference](_.hash)
    val _Ordinal: Lens[TransactionReference, TransactionOrdinal] = GenLens[TransactionReference](_.ordinal)
  }

  @derive(decoder, encoder, eqv, show)
  @newtype
  case class TransactionSalt(value: Long)

  @derive(decoder, encoder, eqv, show)
  case class TransactionData(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee
  )

  @derive(decoder, encoder, eqv, show)
  case class Transaction(
    source: Address,
    destination: Address,
    amount: TransactionAmount,
    fee: TransactionFee,
    parent: TransactionReference,
    //TODO: check if we can remove the salt and still have hash compatible with bolos app (Ledger)
    salt: TransactionSalt
  ) extends Fiber[TransactionReference, TransactionData]
      with Encodable {
    import Transaction._

    def reference = parent
    def data = TransactionData(source, destination, amount, fee)

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

    val ordinal: TransactionOrdinal = _ParentOrdinal.get(this).next
  }

  object Transaction {
    def runLengthEncoding(hashes: Seq[String]): String = hashes.fold("")((acc, hash) => s"$acc${hash.length}$hash")

    val _Source: Lens[Transaction, Address] = GenLens[Transaction](_.source)
    val _Destination: Lens[Transaction, Address] = GenLens[Transaction](_.destination)

    val _Amount: Lens[Transaction, TransactionAmount] = GenLens[Transaction](_.amount)
    val _Fee: Lens[Transaction, TransactionFee] = GenLens[Transaction](_.fee)
    val _Parent: Lens[Transaction, TransactionReference] = GenLens[Transaction](_.parent)

    val _ParentHash: Lens[Transaction, Hash] = _Parent.andThen(TransactionReference._Hash)
    val _ParentOrdinal: Lens[Transaction, TransactionOrdinal] = _Parent.andThen(TransactionReference._Ordinal)

    implicit val transactionOrder: Order[Transaction] = (x: Transaction, y: Transaction) =>
      implicitly[Order[BigInt]].compare(x.ordinal.coerce, y.ordinal.coerce)

    implicit val transactionOrdering: Ordering[Transaction] = transactionOrder.toOrdering
  }
}
