package org.tessellation.schema.ext.http4s

import cats.syntax.option._

import org.tessellation.schema.security.hash.Hash

object HashVar {
  def unapply(str: String): Option[Hash] = Hash(str).some
}
