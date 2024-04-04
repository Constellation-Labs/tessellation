package org.tessellation.ext

import scala.collection.immutable.SortedSet

import _root_.cats.Order
import _root_.cats.data.NonEmptySet
import io.circe.Decoder

object codecs {

  object NonEmptySetCodec {

    def decoder[A: Decoder: Ordering]: Decoder[NonEmptySet[A]] =
      Decoder.decodeNonEmptySet[A](Decoder[A], Order.fromOrdering).map { nes =>
        NonEmptySet.fromSetUnsafe(SortedSet.from(nes.toSortedSet))
      }
  }
}
