package org.tessellation.domain.aci

import cats.syntax.option._

import org.tessellation.kernel.{Gistable, Ω}
import org.tessellation.schema.address.Address

import shapeless.Typeable

case class StateChannelOutput(
  address: Address,
  output: Ω,
  outputBytes: Array[Byte]
) {

  def takeGistedOutputOf[A <: Ω](implicit T: Typeable[A]): Option[StateChannelGistedOutput[A]] =
    output match {
      case value: Gistable[_] =>
        T.cast(value.gist).map(StateChannelGistedOutput(address, _, outputBytes))
      case _ =>
        none[StateChannelGistedOutput[A]]
    }
}
