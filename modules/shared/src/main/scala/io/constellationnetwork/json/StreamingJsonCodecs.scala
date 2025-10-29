// src/main/scala/io/constellationnetwork/json/StreamingJsonCodecs.scala
package io.constellationnetwork.json

import scala.collection.immutable._

import io.circe.magnolia.derivation.encoder.semiauto.Typeclass
import io.circe.{Encoder, KeyEncoder}

object StreamingJsonCodecs {

  // === ONLY Magnolia Typeclass overrides (NO Encoder implicits!) ===

  implicit def magnoliaSortedMap[K: KeyEncoder, V: Encoder]: Typeclass[SortedMap[K, V]] =
    StreamingCollectionEncoder.sortedMapStreamingEncoder[K, V]

  implicit def magnoliaSortedSet[A: Encoder]: Typeclass[SortedSet[A]] =
    StreamingCollectionEncoder.sortedSetStreamingEncoder[A]

  implicit def magnoliaTreeMap[K: KeyEncoder, V: Encoder]: Typeclass[TreeMap[K, V]] =
    StreamingCollectionEncoder.treeMapStreamingEncoder[K, V]

  implicit def magnoliaTreeSet[A: Encoder]: Typeclass[TreeSet[A]] =
    StreamingCollectionEncoder.treeSetStreamingEncoder[A]

  implicit def magnoliaMap[K: KeyEncoder, V: Encoder]: Typeclass[Map[K, V]] =
    StreamingCollectionEncoder.mapStreamingEncoder[K, V]

  implicit def magnoliaSet[A: Encoder]: Typeclass[Set[A]] =
    StreamingCollectionEncoder.setStreamingEncoder[A]

  implicit def magnoliaList[A: Encoder]: Typeclass[List[A]] =
    StreamingCollectionEncoder.listStreamingEncoder[A]

  implicit def magnoliaVector[A: Encoder]: Typeclass[Vector[A]] =
    StreamingCollectionEncoder.vectorStreamingEncoder[A]

  implicit def magnoliaSeq[A: Encoder]: Typeclass[Seq[A]] =
    StreamingCollectionEncoder.seqStreamingEncoder[A]

  implicit def magnoliaOption[A: Encoder]: Typeclass[Option[A]] =
    StreamingCollectionEncoder.optionStreamingEncoder[A]
}
