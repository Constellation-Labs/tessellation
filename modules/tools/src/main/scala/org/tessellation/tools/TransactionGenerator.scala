package org.tessellation.tools

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all._

import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.transaction._
import org.tessellation.security.SecurityProvider
import org.tessellation.security.key.ops._
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.{Pure, Stream}

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

  private val chunkMinSize = 100

  def infiniteTransactionStream[F[_]: Async: Random: KryoSerializer: SecurityProvider](
    chunkSize: PosInt,
    feeValue: NonNegLong,
    addressParams: NonEmptyList[AddressParams]
  ): Stream[F, Signed[DAGTransaction]] =
    addressParams.reverse.map {
      case AddressParams(source, key, initialTxRef) =>
        val otherWallets = addressParams.filter(_.address =!= source).map(_.address)

        def tx(lastTxRef: TransactionReference): F[Signed[DAGTransaction]] =
          for {
            destination <- Random[F]
              .shuffleList(otherWallets)
              .map(_.headOption)
              .flatMap(_.liftTo[F](new Throwable("Not enough wallets")))
            amount = TransactionAmount(PosLong.MinValue)
            fee = TransactionFee(feeValue)
            salt <- Random[F].nextLong.map(TransactionSalt.apply)
            tx = DAGTransaction(source, destination, amount, fee, lastTxRef, salt)
            signedTx <- tx.sign(key)
          } yield signedTx

        def txStream(lastRef: TransactionReference): Stream[F, Signed[DAGTransaction]] =
          for {
            signedTx <- Stream.eval(tx(lastRef))
            txRef <- Stream.eval(signedTx.value.hashF.map(TransactionReference(signedTx.ordinal, _)))
            result <- Stream(signedTx) ++ txStream(txRef)
          } yield result

        txStream(initialTxRef).chunkN(chunkSize)
    }.foldLeft[Stream[F, Stream[Pure, Signed[DAGTransaction]]]](Stream.constant(Stream.empty)) { (acc, s) =>
      acc.zipWith(s)((acc, currChunk) => acc.cons(currChunk))
    }.flatten
      .chunkMin(chunkMinSize)
      .unchunks
      .prefetch

}
