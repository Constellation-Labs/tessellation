package io.constellationnetwork.ext.collection

import cats.Monad
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import io.chrisdavenport.mapref.MapRef

object MapRefUtils {

  implicit class MapRefOps[F[_]: Monad, K, V](val mapRef: MapRef[F, K, Option[V]]) {

    def toMap: F[Map[K, V]] =
      for {
        keys <- mapRef.keys
        keyValues <- keys.traverseFilter { id =>
          mapRef(id).get.map(_.map((id, _)))
        }
      } yield keyValues.toMap

    def clear: F[Unit] =
      for {
        keys <- mapRef.keys
        _ <- keys.traverse { id =>
          mapRef(id).set(none)
        }
      } yield ()
  }
}
