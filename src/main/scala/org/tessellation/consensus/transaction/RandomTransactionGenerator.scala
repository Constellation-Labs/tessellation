package org.tessellation.consensus.transaction

import cats.effect.IO
import cats.effect.concurrent.Ref
import org.tessellation.Log
import org.tessellation.consensus.L1Transaction

import scala.util.Random

class RandomTransactionGenerator {
  private val addresses = Set("A", "B", "C")
  private val generatedTxs: Ref[IO, Map[String, String]] = Ref.unsafe(Map.empty)

  def generateRandomTransaction(): IO[L1Transaction] =
    for {
      src <- getRandomSrcAddress
      dst <- getRandomDstAddress(src)
      a <- getRandomValue
      parentHash <- getPreviouslyGeneratedTransactionHash(src)
      tx = L1Transaction(a, src, dst, parentHash)
      _ <- updatePreviouslyGeneratedTransactionHash(src, tx.hash)
      _ <- IO {
        Log.blue(s"Generated transaction: $tx with hash ${tx.hash}")
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

  private def getPreviouslyGeneratedTransactionHash(src: String): IO[String] =
    generatedTxs.get
      .map(_.getOrElse(src, ""))

  private def updatePreviouslyGeneratedTransactionHash(src: String, hash: String): IO[Unit] =
    generatedTxs.modify(txs => (txs.updated(src, hash), ()))
}

object RandomTransactionGenerator {
  def apply(): RandomTransactionGenerator = new RandomTransactionGenerator()
}
