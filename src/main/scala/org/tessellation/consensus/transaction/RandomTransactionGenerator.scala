package org.tessellation.consensus.transaction

import cats.effect.IO
import cats.effect.concurrent.Ref
import org.tessellation.Log
import org.tessellation.consensus.L1Transaction

import scala.util.Random

class RandomTransactionGenerator(src: Option[String] = None) {
  private val addresses = Set("A", "B", "C")
  private val generatedTxs: Ref[IO, Map[String, L1Transaction]] = Ref.unsafe(Map.empty)

  def generateRandomTransaction(): IO[L1Transaction] =
    for {
      src <- src.fold(getRandomSrcAddress)(IO.pure)
      dst <- getRandomDstAddress(src)
      a <- getRandomValue
      tx <- generatedTxs.modify { txs =>
        val tx = txs
          .get(src)
          .map(prevTx => L1Transaction(a, src, dst, prevTx.hash, prevTx.ordinal + 1))
          .getOrElse(L1Transaction(a, src, dst, "", 0))
        (txs.updated(src, tx), tx)
      }
      _ <- IO {
        Log.blue(s"Generated transaction: $tx")
      }
    } yield tx

  private def getRandomSrcAddress: IO[String] = IO {
    Random.shuffle(addresses).head
  }

  private def getRandomDstAddress(src: String): IO[String] = IO {
    Random.shuffle(addresses - src).head
  }

  private def getRandomValue: IO[Int] = IO {
    Random.nextInt(Integer.MAX_VALUE)
  }
}

object RandomTransactionGenerator {
  def apply(src: Option[String] = None): RandomTransactionGenerator = new RandomTransactionGenerator(src)
}
