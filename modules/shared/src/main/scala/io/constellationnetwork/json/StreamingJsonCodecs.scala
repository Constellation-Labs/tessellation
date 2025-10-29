package io.constellationnetwork.json

import scala.collection.immutable._

import io.circe.{Encoder, KeyEncoder}

object StreamingJsonCodecs {
  implicit def magnoliaSortedMap[K: KeyEncoder: Ordering, V: Encoder]: Encoder[SortedMap[K, V]] =
    StreamingCollectionEncoder.sortedMapStreamingEncoder[K, V]

  implicit def magnoliaSortedSet[A: Encoder: Ordering]: Encoder[SortedSet[A]] =
    StreamingCollectionEncoder.sortedSetStreamingEncoder[A]

  implicit def magnoliaTreeMap[K: KeyEncoder: Ordering, V: Encoder]: Encoder[TreeMap[K, V]] =
    StreamingCollectionEncoder.treeMapStreamingEncoder[K, V]

  implicit def magnoliaTreeSet[A: Encoder: Ordering]: Encoder[TreeSet[A]] =
    StreamingCollectionEncoder.treeSetStreamingEncoder[A]

  implicit def magnoliaMap[K: KeyEncoder, V: Encoder]: Encoder[Map[K, V]] =
    StreamingCollectionEncoder.mapStreamingEncoder[K, V]

  implicit def magnoliaSet[A: Encoder: Ordering]: Encoder[Set[A]] =
    StreamingCollectionEncoder.setStreamingEncoder[A]

  implicit def magnoliaList[A: Encoder]: Encoder[List[A]] =
    StreamingCollectionEncoder.listStreamingEncoder[A]

  implicit def magnoliaVector[A: Encoder]: Encoder[Vector[A]] =
    StreamingCollectionEncoder.vectorStreamingEncoder[A]

  implicit def magnoliaSeq[A: Encoder]: Encoder[Seq[A]] =
    StreamingCollectionEncoder.seqStreamingEncoder[A]

  implicit def magnoliaOption[A: Encoder]: Encoder[Option[A]] =
    StreamingCollectionEncoder.optionStreamingEncoder[A]
}
