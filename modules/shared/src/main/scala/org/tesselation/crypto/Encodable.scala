package org.tesselation.crypto

trait Encodable {
  def toEncode: AnyRef = this
}
