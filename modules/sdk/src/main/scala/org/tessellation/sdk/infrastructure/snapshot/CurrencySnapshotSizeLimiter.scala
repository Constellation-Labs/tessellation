package org.tessellation.sdk.infrastructure.snapshot

import cats.MonadThrow
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.collection.immutable.SortedSet

import org.tessellation.currency.schema.currency.CurrencyIncrementalSnapshot
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.BlockAsActiveTip
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import monocle.macros.syntax.lens._
case class SnapshotSizeLimiterResult(
  snapshot: CurrencyIncrementalSnapshot,
  oldSizeInBytes: Long,
  newSizeInBytes: Long,
  excessBlocks: SortedSet[BlockAsActiveTip]
)

trait CurrencySnapshotSizeLimiter[F[_]] {
  def limit(snapshot: CurrencyIncrementalSnapshot, maxSizeInBytes: Long): F[Option[SnapshotSizeLimiterResult]]
}

object CurrencySnapshotSizeLimiter {
  def make[F[_]: MonadThrow: KryoSerializer]: CurrencySnapshotSizeLimiter[F] =
    (snapshot: CurrencyIncrementalSnapshot, maxSizeInBytes: Long) => {
      type Success = SnapshotSizeLimiterResult
      type Agg = (CurrencyIncrementalSnapshot, SortedSet[BlockAsActiveTip])
      type Result = Option[Success]

      snapshot.toBinaryF.flatMap { initialBytes: Array[Byte] =>
        val oldSize = initialBytes.length

        (snapshot, SortedSet.empty[BlockAsActiveTip]).tailRecM[F, Result] {
          case (snapshot, rejected) =>
            println(s"Blocks:  ${snapshot.blocks.size} Rejected: ${rejected.size}")
            for {
              bytes: Array[Byte] <- snapshot.toBinaryF
              size = bytes.length
              _ = println(s"Size: ${size} (expected: ${maxSizeInBytes})")
              result <-
                if (size <= maxSizeInBytes) {
                  Some(
                    SnapshotSizeLimiterResult(
                      snapshot,
                      oldSize.toLong,
                      size.toLong,
                      rejected
                    )
                  ).asRight[Agg].pure[F]
                } else {
                  snapshot.blocks.toList match {
                    case toReject :: toLeave =>
                      (snapshot.focus(_.blocks).replace(toLeave.toSortedSet), rejected + toReject).asLeft[Result].pure[F]
                    case _ => None.asRight[Agg].pure[F]
                  }
                }
            } yield result
        }

      }

    }
}
