package org.tessellation.syntax

import scala.collection.immutable.{SortedMap, SortedSet}

trait SortedCollectionSyntax {

  implicit def sortedSetSyntax[A: Ordering](iterable: IterableOnce[A]): SortedSetOps[A] =
    new SortedSetOps[A](iterable)

  implicit def sortedMapSyntax[K: Ordering, V](iterable: IterableOnce[(K, V)]): SortedMapOps[K, V] =
    new SortedMapOps[K, V](iterable)

}

final class SortedSetOps[A: Ordering](iterable: IterableOnce[A]) {
  def toSortedSet: SortedSet[A] = SortedSet.from(iterable)
}

final class SortedMapOps[K: Ordering, V](iterable: IterableOnce[(K, V)]) {
  def toSortedMap: SortedMap[K, V] = SortedMap.from(iterable)
}
