package org.tessellation.security

trait Encodable {
  def toEncode: AnyRef = this
}
