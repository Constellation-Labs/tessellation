package io.constellationnetwork.json

import scala.collection.immutable._

import io.circe._

trait StreamingCollectionEncoder[A] extends Encoder[A] {
  def streamEncode(value: A, printer: Printer, appendable: Appendable): Unit
}

sealed trait LowPriorityStreamingCollectionEncoders {
  implicit def fallbackStreamingEncoder[A](implicit enc: Encoder[A]): StreamingCollectionEncoder[A] =
    new StreamingCollectionEncoder[A] {
      def apply(a: A): Json = enc(a)
      def streamEncode(a: A, p: Printer, ap: Appendable): Unit =
        p.unsafePrintToAppendable(enc(a), ap)
    }
}

object StreamingCollectionEncoder extends LowPriorityStreamingCollectionEncoders {

  private def encodeValue[V](v: V, p: Printer, a: Appendable)(implicit enc: Encoder[V]): Unit =
    enc match {
      case sce: StreamingCollectionEncoder[V] @unchecked =>
        sce.streamEncode(v, p, a)
      case _ =>
        p.unsafePrintToAppendable(enc(v), a)
    }

  private def streamMap[K, V](
    iter: Iterator[(K, V)],
    p: Printer,
    a: Appendable,
    encV: Encoder[V],
    toKey: K => String
  ): Unit = {
    a.append('{')
    var first = true
    while (iter.hasNext) {
      val (k, v) = iter.next()
      if (first) first = false else a.append(',')
      p.unsafePrintToAppendable(Json.fromString(toKey(k)), a)
      a.append(':')
      encodeValue(v, p, a)(encV)
    }
    a.append('}')
    ()
  }

  private def streamIterable[A](
    iter: Iterator[A],
    p: Printer,
    a: Appendable,
    encA: Encoder[A]
  ): Unit = {
    a.append('[')
    var first = true
    while (iter.hasNext) {
      val e = iter.next()
      if (first) first = false else a.append(',')
      encodeValue(e, p, a)(encA)
    }
    a.append(']')
    ()
  }

  implicit def treeMapStreamingEncoder[K, V](
    implicit K: KeyEncoder[K],
    V: Encoder[V]
  ): StreamingCollectionEncoder[TreeMap[K, V]] =
    new StreamingCollectionEncoder[TreeMap[K, V]] {
      private val encV: Encoder[V] = V
      private val keyEncK: KeyEncoder[K] = K

      def apply(m: TreeMap[K, V]): Json =
        Json.fromJsonObject(
          JsonObject.fromIterable(
            m.view.map { case (k, v) => keyEncK(k) -> encV(v) }
          )
        )

      def streamEncode(m: TreeMap[K, V], p: Printer, a: Appendable): Unit =
        streamMap(m.iterator, p, a, encV, keyEncK.apply)
    }

  implicit def treeSetStreamingEncoder[A](implicit encA: Encoder[A], ordA: Ordering[A]): StreamingCollectionEncoder[TreeSet[A]] =
    new StreamingCollectionEncoder[TreeSet[A]] {
      private val encA_val: Encoder[A] = encA

      def apply(s: TreeSet[A]): Json =
        Json.fromValues(s.iterator.map(encA_val.apply).to(Iterable))

      def streamEncode(s: TreeSet[A], p: Printer, a: Appendable): Unit =
        streamIterable(s.iterator, p, a, encA_val)
    }

  implicit def sortedMapStreamingEncoder[K, V](
    implicit K: KeyEncoder[K],
    V: Encoder[V]
  ): StreamingCollectionEncoder[SortedMap[K, V]] =
    new StreamingCollectionEncoder[SortedMap[K, V]] {
      private val encV: Encoder[V] = V
      private val keyEncK: KeyEncoder[K] = K

      def apply(m: SortedMap[K, V]): Json =
        Json.fromJsonObject(
          JsonObject.fromIterable(
            m.view.map { case (k, v) => keyEncK(k) -> encV(v) }
          )
        )

      def streamEncode(m: SortedMap[K, V], p: Printer, a: Appendable): Unit =
        streamMap(m.iterator, p, a, encV, keyEncK.apply)
    }

  implicit def sortedSetStreamingEncoder[A](implicit encA: Encoder[A], ordA: Ordering[A]): StreamingCollectionEncoder[SortedSet[A]] =
    new StreamingCollectionEncoder[SortedSet[A]] {
      private val encA_val: Encoder[A] = encA

      def apply(s: SortedSet[A]): Json =
        Json.fromValues(s.iterator.map(encA_val.apply).to(Iterable))

      def streamEncode(s: SortedSet[A], p: Printer, a: Appendable): Unit =
        streamIterable(s.iterator, p, a, encA_val)
    }

  implicit def mapStreamingEncoder[K, V](
    implicit K: KeyEncoder[K],
    V: Encoder[V]
  ): StreamingCollectionEncoder[Map[K, V]] =
    new StreamingCollectionEncoder[Map[K, V]] {
      private val encV: Encoder[V] = V
      private val keyEncK: KeyEncoder[K] = K

      def apply(m: Map[K, V]): Json =
        Json.fromJsonObject(
          JsonObject.fromIterable(
            m.view.map { case (k, v) => keyEncK(k) -> encV(v) }
          )
        )

      def streamEncode(m: Map[K, V], p: Printer, a: Appendable): Unit =
        streamMap(m.iterator, p, a, encV, keyEncK.apply)
    }

  /** ⚡ Streaming Set encoder — does not sort to preserve streaming semantics */
  implicit def setStreamingEncoder[A](implicit encA: Encoder[A]): StreamingCollectionEncoder[Set[A]] =
    new StreamingCollectionEncoder[Set[A]] {
      private val encA_val: Encoder[A] = encA

      def apply(s: Set[A]): Json =
        Json.fromValues(s.iterator.map(encA_val.apply).to(Iterable))

      def streamEncode(s: Set[A], p: Printer, a: Appendable): Unit =
        streamIterable(s.iterator, p, a, encA_val)
    }

  implicit def listStreamingEncoder[A](implicit encA: Encoder[A]): StreamingCollectionEncoder[List[A]] =
    new StreamingCollectionEncoder[List[A]] {
      private val encA_val: Encoder[A] = encA

      def apply(l: List[A]): Json =
        Json.fromValues(l.iterator.map(encA_val.apply).to(Iterable))

      def streamEncode(l: List[A], p: Printer, a: Appendable): Unit =
        streamIterable(l.iterator, p, a, encA_val)
    }

  implicit def vectorStreamingEncoder[A](implicit encA: Encoder[A]): StreamingCollectionEncoder[Vector[A]] =
    new StreamingCollectionEncoder[Vector[A]] {
      private val encA_val: Encoder[A] = encA

      def apply(v: Vector[A]): Json =
        Json.fromValues(v.iterator.map(encA_val.apply).to(Iterable))

      def streamEncode(v: Vector[A], p: Printer, a: Appendable): Unit =
        streamIterable(v.iterator, p, a, encA_val)
    }

  implicit def seqStreamingEncoder[A](implicit encA: Encoder[A]): StreamingCollectionEncoder[Seq[A]] =
    new StreamingCollectionEncoder[Seq[A]] {
      private val encA_val: Encoder[A] = encA

      def apply(s: Seq[A]): Json =
        Json.fromValues(s.iterator.map(encA_val.apply).to(Iterable))

      def streamEncode(s: Seq[A], p: Printer, a: Appendable): Unit =
        streamIterable(s.iterator, p, a, encA_val)
    }

  implicit def optionStreamingEncoder[A](implicit encA: Encoder[A]): StreamingCollectionEncoder[Option[A]] =
    new StreamingCollectionEncoder[Option[A]] {
      private val encA_val: Encoder[A] = encA

      def apply(o: Option[A]): Json =
        o.map(encA_val.apply).getOrElse(Json.Null)

      def streamEncode(o: Option[A], p: Printer, a: Appendable): Unit =
        o match {
          case Some(v) => encodeValue(v, p, a)(encA_val)
          case None =>
            a.append("null")
            ()
        }
    }
}
