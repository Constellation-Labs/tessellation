package org.tessellation.ext.http4s

import org.tessellation.schema.hex.HexString64Spec
import org.tessellation.security.hash.Hash

import eu.timepit.refined.refineV

object HashVar {
  def unapply(str: String): Option[Hash] = refineV[HexString64Spec](str).map(Hash(_)).toOption
}
