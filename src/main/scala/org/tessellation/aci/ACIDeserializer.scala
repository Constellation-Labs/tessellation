package org.tessellation.aci

import java.util.Base64

import cats.data.OptionT
import cats.effect.{Concurrent, ContextShift}
import cats.syntax.all._

class ACIDeserializer[F[_]: Concurrent: ContextShift](
  registry: ACIRegistry[F],
  kryoWrapper: KryoWrapper[F]
) {

  def deserialize[T](address: String, payload: String): OptionT[F, T] =
    for {
      cls <- registry.findClassByAddress(address)
      instanceOfAnyRef <- OptionT.liftF(
        kryoWrapper
          .deserialize(Base64.getDecoder.decode(payload))
          .map(_.asInstanceOf[T])
      )
      if instanceOfAnyRef.getClass == cls // TODO instead of filtering like this, return error to the client
    } yield instanceOfAnyRef
}
