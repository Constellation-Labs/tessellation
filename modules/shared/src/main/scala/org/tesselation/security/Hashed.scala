package org.tesselation.security

import org.tesselation.security.hash.Hash
import org.tesselation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class Hashed[A <: AnyRef](signed: Signed[A], hash: Hash)
