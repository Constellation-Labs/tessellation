package org.tessellation.ext.cats

import cats.data.{NonEmptyMap, NonEmptySet}

import scala.collection.immutable.SortedSet

package object data {
  implicit class NonEmptyMapOps[K, A](value: NonEmptyMap[K, A]) {

    def values(implicit ordering: Ordering[A]): NonEmptySet[A] =
      NonEmptySet.fromSetUnsafe(SortedSet.from(value.toSortedMap.values))
  }
}
