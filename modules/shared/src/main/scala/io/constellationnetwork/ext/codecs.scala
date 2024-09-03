package io.constellationnetwork.ext

import scala.collection.immutable.SortedSet

import _root_.cats.Order
import _root_.cats.data.NonEmptySet
import io.circe.{Decoder, Encoder}

object codecs {

  object NonEmptySetCodec {

    def encoder[A: Encoder]: Encoder[NonEmptySet[A]] =
      Encoder.encodeNonEmptySet[A](Encoder[A])

    def decoder[A: Decoder: Ordering]: Decoder[NonEmptySet[A]] =
      Decoder.decodeNonEmptySet[A](Decoder[A], Order.fromOrdering).map { nes =>
        NonEmptySet.fromSetUnsafe(SortedSet.from(nes.toSortedSet))
      }
  }
}
