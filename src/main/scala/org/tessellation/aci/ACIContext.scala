package org.tessellation.aci

import cats.effect.{Concurrent, ContextShift}
import org.tessellation.aci.endpoint.{
  AddACITypeEndpoint,
  ValidateACIObjectEndpoint
}

class ACIContext[F[_]: Concurrent: ContextShift] {

  val kryoWrapper: KryoWrapper[F] = new KryoWrapper[F]
  val repository: ACIRepository[F] = new ACIRepository[F]("/tmp/aci.db")
  val registry: ACIRegistry[F] = new ACIRegistry[F](repository, kryoWrapper)
  val deserializer: ACIDeserializer[F] =
    new ACIDeserializer[F](registry, kryoWrapper)
  val validateACIObjectEndpoint: ValidateACIObjectEndpoint[F] =
    new ValidateACIObjectEndpoint[F](deserializer)
  val addACITypeEndpoint: AddACITypeEndpoint[F] =
    new AddACITypeEndpoint[F](registry)
  val aciEndpoints: ACIEndpoints[F] =
    new ACIEndpoints[F](validateACIObjectEndpoint, addACITypeEndpoint)

}
