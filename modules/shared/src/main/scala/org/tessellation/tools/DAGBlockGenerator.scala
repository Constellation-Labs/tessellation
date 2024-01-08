package org.tessellation.tools

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.ext.crypto._
import org.tessellation.schema.transaction.Transaction
import org.tessellation.schema.{Block, BlockAsActiveTip, BlockReference}
import org.tessellation.security.signature.Signed
import org.tessellation.security.{Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import fs2.Stream

object DAGBlockGenerator {

  def createDAGs[F[_]: Async: Random: Hasher: SecurityProvider](
    transactionsChunks: List[NonEmptySet[Signed[Transaction]]],
    initialReferences: NonEmptyList[BlockReference],
    keys: NonEmptyList[KeyPair]
  ) = {

    def block(references: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[Transaction]]): F[Signed[Block]] =
      for {
        parents <- Random[F]
          .shuffleList(references.distinct.toList)
          .map(_.take(2))
        _ <- (new Throwable("Not enough parents")).raiseError[F, Unit].whenA(parents.size < 2)
        block = Block(NonEmptyList.fromListUnsafe(parents), transactions)
        signedBlock <- block.sign(keys)
      } yield signedBlock

    def blockStream(
      initialReferences: NonEmptyList[BlockReference],
      transactionsChunks: List[NonEmptySet[Signed[Transaction]]]
    ): Stream[F, BlockAsActiveTip] =
      for {
        signedBlock <- if (transactionsChunks.nonEmpty) Stream.eval(block(initialReferences, transactionsChunks.head)) else Stream.empty
        blockRef <- Stream.eval(signedBlock.toHashed.map(_.ownReference))
        updatedReferences = NonEmptyList.fromListUnsafe(
          blockRef :: blockRef :: removeReferences(Nil, signedBlock.parent.toList, initialReferences.toList)
        )
        result <- Stream(BlockAsActiveTip(signedBlock, 0L)) ++ blockStream(updatedReferences, transactionsChunks.tail)
      } yield result

    blockStream(initialReferences, transactionsChunks)
  }

  private def removeReferences(
    acc: List[BlockReference],
    toRemove: List[BlockReference],
    initialReferences: List[BlockReference]
  ): List[BlockReference] = initialReferences match {
    case Nil                                     => acc
    case head :: tail if toRemove.contains(head) => removeReferences(acc, toRemove.filterNot(_ == head), tail)
    case head :: tail                            => removeReferences(head :: acc, toRemove, tail)
  }
}
