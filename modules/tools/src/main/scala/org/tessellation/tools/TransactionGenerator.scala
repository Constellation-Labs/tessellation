package org.tessellation.tools

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.{Chunk, Stream}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.transaction.{
  Transaction,
  TransactionAmount,
  TransactionFee,
  TransactionReference,
  TransactionSalt
}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.signature.Signed
import org.tessellation.ext.crypto._
import org.tessellation.schema.address.Address
import org.tessellation.security.key.ops._
import eu.timepit.refined.auto._

import java.security.KeyPair

object TransactionGenerator {

  case class AddressParams(
    address: Address,
    keyPair: KeyPair,
    initialTxRef: TransactionReference
  )

  object AddressParams {

    def apply(keyPair: KeyPair, initialTxRef: TransactionReference): AddressParams =
      AddressParams(keyPair.getPublic.toAddress, keyPair, initialTxRef)
    def apply(keyPair: KeyPair): AddressParams = AddressParams(keyPair, TransactionReference.empty)
  }

  def infiniteTransactionStream[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    chunkSize: PosInt,
    addressParams: List[AddressParams]
  ): Stream[F, Signed[Transaction]] =
    addressParams.reverse.map {
      case AddressParams(source, key, initialTxRef) =>
        val otherWallets = addressParams.filter(_.address =!= source).map(_.address)

        def tx(lastTxRef: TransactionReference): F[Signed[Transaction]] =
          for {
            destination <- Random[F]
              .shuffleList(otherWallets)
              .map(_.headOption)
              .flatMap(_.liftTo[F](new Throwable("Not enough wallets")))
            amount = TransactionAmount(PosLong.MinValue)
            fee = TransactionFee(NonNegLong.MinValue)
            salt <- Random[F].nextLong.map(TransactionSalt.apply)
            tx = Transaction(source, destination, amount, fee, lastTxRef, salt)
            signedTx <- tx.sign(key)
          } yield signedTx

        def txStream(lastRef: TransactionReference): Stream[F, Signed[Transaction]] =
          for {
            signedTx <- Stream.eval(tx(lastRef))
            txRef <- Stream.eval(signedTx.value.hashF.map(TransactionReference(signedTx.ordinal, _)))
            result <- Stream(signedTx) ++ txStream(txRef)
          } yield result

        txStream(initialTxRef).chunkN(chunkSize)
    }.foldLeft[Stream[F, Chunk[Signed[Transaction]]]](Stream.constant(Chunk.empty)) { (acc, s) =>
        acc.zipWith(s)((accChunk, currChunk) => accChunk |+| currChunk)
      }
      .flatMap(Stream.chunk)

}
