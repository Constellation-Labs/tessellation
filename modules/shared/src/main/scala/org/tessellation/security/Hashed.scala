package org.tessellation.security
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed

import derevo.cats.{order, show}
import derevo.derive

@derive(order, show)
case class Hashed[A <: AnyRef](signed: Signed[A], hash: Hash, proofsHash: ProofsHash)

object Hashed {
  implicit def autoUnwrap[T <: AnyRef](t: Hashed[T]): T = t.signed
}
