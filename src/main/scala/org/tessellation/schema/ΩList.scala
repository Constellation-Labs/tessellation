package org.tessellation.schema

abstract class ΩList extends Ω {
  def ::(elem: Ω): ΩList = new ::(elem, this)
}
case class ::(h: Ω, t: ΩList) extends ΩList {
  override def toString: String = s"$h :: $t"
}
case object ΩNil extends ΩList {
  override def toString: String = "ΩNil"
}
