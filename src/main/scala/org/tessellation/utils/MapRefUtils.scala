package org.tessellation.utils

import java.util.concurrent.ConcurrentHashMap

import cats.effect.Sync
import io.chrisdavenport.mapref.MapRef

object MapRefUtils {

  def ofConcurrentHashMap[F[_]: Sync, K, V](
    initialCapacity: Int = 16,
    loadFactor: Float = 0.75f,
    concurrencyLevel: Int = 16
  ): MapRef[F, K, Option[V]] =
    MapRef.fromConcurrentHashMap(
      new ConcurrentHashMap[K, V](initialCapacity, loadFactor, concurrencyLevel)
    )

}
