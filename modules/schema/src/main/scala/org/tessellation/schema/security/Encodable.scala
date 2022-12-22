package org.tessellation.schema.security

trait Encodable {
  def toEncode: AnyRef = this
}
