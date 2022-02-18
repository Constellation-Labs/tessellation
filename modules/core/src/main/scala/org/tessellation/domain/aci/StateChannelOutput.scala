package org.tessellation.domain.aci

import cats.syntax.option._

import scala.reflect.runtime.universe.TypeTag

import org.tessellation.kernel.{Gistable, Ω}
import org.tessellation.schema.address.Address

import shapeless.Typeable

case class StateChannelOutput(
  address: Address,
  output: Ω,
  outputBytes: Array[Byte]
) {

  def takeGistedOutputOf[T <: Ω: TypeTag: Typeable]: Option[StateChannelGistedOutput[T]] =
    output match {
      case value: Gistable[_] =>
        Typeable[T].cast(value.gist).map(StateChannelGistedOutput(address, _, outputBytes))
      case _ =>
        none[StateChannelGistedOutput[T]]
    }
}
