package org.tesselation.security

trait Encodable {
  def toEncode: AnyRef = this
}
