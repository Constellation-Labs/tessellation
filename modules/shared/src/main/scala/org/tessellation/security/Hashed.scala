package org.tessellation.security

import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
case class Hashed[A <: AnyRef](signed: Signed[A], hash: Hash, proofsHash: ProofsHash)
