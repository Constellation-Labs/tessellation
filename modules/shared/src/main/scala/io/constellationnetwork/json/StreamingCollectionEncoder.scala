package io.constellationnetwork.json

import scala.collection.immutable._

import io.circe._

trait StreamingCollectionEncoder[A] extends Encoder[A] {
  def streamEncode(value: A, printer: Printer, appendable: Appendable): Unit
}

object StreamingCollectionEncoder {

  /** Fast-path: recurse when the inner encoder is also streaming. */
  private def encodeValue[V](v: V, p: Printer, a: Appendable)(implicit enc: Encoder[V]): Unit =
    enc match {
      case sce: StreamingCollectionEncoder[V] @unchecked => sce.streamEncode(v, p, a)
      case _                                             => p.unsafePrintToAppendable(enc(v), a)
    }

  /** Stream a map-like structure. */
  private def streamMap[K, V](
    iter: Iterator[(K, V)],
    p: Printer,
    a: Appendable
  )(implicit V: Encoder[V], K: K => String): Unit = {
    a.append('{')
    var first = true
    while (iter.hasNext) {
      val (k, v) = iter.next()
      if (first) first = false else a.append(',')
      p.unsafePrintToAppendable(Json.fromString(K(k)), a)
      a.append(':')
      encodeValue(v, p, a)
    }
    a.append('}')
    ()
  }

  /** Stream an iterable-like structure. */
  private def streamIterable[A](
    iter: Iterator[A],
    p: Printer,
    a: Appendable
  )(implicit enc: Encoder[A]): Unit = {
    a.append('[')
    var first = true
    while (iter.hasNext) {
      val e = iter.next()
      if (first) first = false else a.append(',')
      encodeValue(e, p, a)
    }
    a.append(']')
    ()
  }

  // ------------------------------------------------------------------------
  //  Concrete encoders – no materialisation at all
  // ------------------------------------------------------------------------

  def treeMapStreamingEncoder[K: KeyEncoder, V: Encoder]: StreamingCollectionEncoder[TreeMap[K, V]] =
    new StreamingCollectionEncoder[TreeMap[K, V]] {
      def apply(m: TreeMap[K, V]): Json = Json.fromJsonObject(JsonObject.empty) // never used
      def streamEncode(m: TreeMap[K, V], p: Printer, a: Appendable): Unit =
        streamMap(m.iterator, p, a)(Encoder[V], KeyEncoder[K].apply)
    }

  def treeSetStreamingEncoder[A: Encoder]: StreamingCollectionEncoder[TreeSet[A]] =
    new StreamingCollectionEncoder[TreeSet[A]] {
      def apply(s: TreeSet[A]): Json = Json.fromValues(Nil) // never used
      def streamEncode(s: TreeSet[A], p: Printer, a: Appendable): Unit =
        streamIterable(s.iterator, p, a)
    }

  def sortedMapStreamingEncoder[K: KeyEncoder, V: Encoder]: StreamingCollectionEncoder[SortedMap[K, V]] =
    new StreamingCollectionEncoder[SortedMap[K, V]] {
      def apply(m: SortedMap[K, V]): Json = Json.fromJsonObject(JsonObject.empty) // never used
      def streamEncode(m: SortedMap[K, V], p: Printer, a: Appendable): Unit =
        streamMap(m.iterator, p, a)(Encoder[V], KeyEncoder[K].apply)
    }

  def sortedSetStreamingEncoder[A: Encoder]: StreamingCollectionEncoder[SortedSet[A]] =
    new StreamingCollectionEncoder[SortedSet[A]] {
      def apply(s: SortedSet[A]): Json = Json.fromValues(Nil) // never used
      def streamEncode(s: SortedSet[A], p: Printer, a: Appendable): Unit =
        streamIterable(s.iterator, p, a)
    }

  def mapStreamingEncoder[K: KeyEncoder, V: Encoder]: StreamingCollectionEncoder[Map[K, V]] =
    new StreamingCollectionEncoder[Map[K, V]] {
      def apply(m: Map[K, V]): Json = Json.fromJsonObject(JsonObject.empty) // never used
      def streamEncode(m: Map[K, V], p: Printer, a: Appendable): Unit =
        streamMap(m.iterator, p, a)(Encoder[V], KeyEncoder[K].apply)
    }

  def setStreamingEncoder[A: Encoder]: StreamingCollectionEncoder[Set[A]] =
    new StreamingCollectionEncoder[Set[A]] {
      def apply(s: Set[A]): Json = Json.fromValues(Nil) // never used
      def streamEncode(s: Set[A], p: Printer, a: Appendable): Unit =
        streamIterable(s.iterator, p, a)
    }

  def listStreamingEncoder[A: Encoder]: StreamingCollectionEncoder[List[A]] =
    new StreamingCollectionEncoder[List[A]] {
      def apply(l: List[A]): Json = Json.fromValues(Nil) // never used
      def streamEncode(l: List[A], p: Printer, a: Appendable): Unit =
        streamIterable(l.iterator, p, a)
    }

  def vectorStreamingEncoder[A: Encoder]: StreamingCollectionEncoder[Vector[A]] =
    new StreamingCollectionEncoder[Vector[A]] {
      def apply(v: Vector[A]): Json = Json.fromValues(Nil) // never used
      def streamEncode(v: Vector[A], p: Printer, a: Appendable): Unit =
        streamIterable(v.iterator, p, a)
    }

  def seqStreamingEncoder[A: Encoder]: StreamingCollectionEncoder[Seq[A]] =
    new StreamingCollectionEncoder[Seq[A]] {
      def apply(s: Seq[A]): Json = Json.fromValues(Nil) // never used
      def streamEncode(s: Seq[A], p: Printer, a: Appendable): Unit =
        streamIterable(s.iterator, p, a)
    }

  def optionStreamingEncoder[A: Encoder]: StreamingCollectionEncoder[Option[A]] =
    new StreamingCollectionEncoder[Option[A]] {
      def apply(o: Option[A]): Json = o.map(Encoder[A].apply).getOrElse(Json.Null) // never used
      def streamEncode(o: Option[A], p: Printer, a: Appendable): Unit =
        o match {
          case Some(v) => encodeValue(v, p, a)
          case None =>
            a.append("null")
            ()
        }
    }

  /** Fallback – still streaming but goes through the normal encoder. */
  def wrapEncoder[A](enc: Encoder[A]): StreamingCollectionEncoder[A] =
    new StreamingCollectionEncoder[A] {
      def apply(a: A): Json = enc(a)
      def streamEncode(a: A, p: Printer, ap: Appendable): Unit =
        p.unsafePrintToAppendable(enc(a), ap)
    }
}
