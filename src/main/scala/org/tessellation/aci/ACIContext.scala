package org.tessellation.aci

import cats.effect.{Concurrent, ConcurrentEffect, ContextShift}
import cats.syntax.all._
import io.chrisdavenport.mapref.MapRef
import org.http4s._
import org.tessellation.aci.endpoint.{ReceiveInputEndpoint, UploadJarEndpoint}
import org.tessellation.utils.MapRefUtils

class ACIContext[F[_]: Concurrent: ContextShift: ConcurrentEffect] {

  val repository: ACIRepository[F] = new ACIRepository[F]("/tmp/aci.db")
  val runtimeCache: MapRef[F, String, Option[StateChannelRuntime]] = MapRefUtils.ofConcurrentHashMap()

  val runtimeLoader: RuntimeLoader[F] = new RuntimeLoader[F]()
  val registry: ACIRegistry[F] = new ACIRegistry[F](repository, runtimeLoader, runtimeCache)

  val receiveInputEndpoint: ReceiveInputEndpoint[F] =
    new ReceiveInputEndpoint[F](registry)

  val addACITypeEndpoint: UploadJarEndpoint[F] =
    new UploadJarEndpoint[F](registry)

  val aciRoutes: HttpRoutes[F] = receiveInputEndpoint.routes <+> addACITypeEndpoint.routes

}
