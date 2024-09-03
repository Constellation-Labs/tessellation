package io.constellationnetwork.ext.http4s

import cats.syntax.option._

import io.constellationnetwork.security.hash.Hash

object HashVar {
  def unapply(str: String): Option[Hash] = Hash(str).some
}
