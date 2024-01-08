package org.tessellation.security

import io.circe.Encoder

trait Encodable[A] {
  def toEncode: A
  def jsonEncoder: Encoder[A]
}
