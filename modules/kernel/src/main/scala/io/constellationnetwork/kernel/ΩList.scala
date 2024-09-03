package io.constellationnetwork.kernel

import scala.annotation.tailrec

sealed abstract class ΩList extends Ω {

  def ::(elem: Ω): ΩList =
    elem match {
      case xs @ ::(_, _) => xs ::: this
      case ΩNil          => this
      case elem          => new ::(elem, this)
    }

  private def :::(prefix: ΩList): ΩList = {
    def reverse(toReverse: ΩList): ΩList = {
      @tailrec
      def go(xs: ΩList, reversed: ΩList): ΩList =
        xs match {
          case ΩNil     => reversed
          case ::(h, t) => go(t, new ::(h, reversed))
        }

      go(toReverse, ΩNil)
    }

    @tailrec
    def go(prefix: ΩList, current: ΩList): ΩList =
      (prefix, current) match {
        case (ΩNil, curr)     => curr
        case (::(h, t), curr) => go(t, new ::(h, curr))
      }

    (prefix, this) match {
      case (xs, ΩNil)     => xs
      case (ΩNil, suffix) => suffix
      case (xs, suffix)   => go(reverse(xs), suffix)
    }
  }
}

case class ::(h: Ω, t: ΩList) extends ΩList {
  override def toString: String = s"$h :: $t"
}

case object ΩNil extends ΩList {
  override def toString: String = "ΩNil"
}
