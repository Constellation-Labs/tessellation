package org.tessellation.security

import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import derevo.cats.{eqv, show}
import derevo.derive

// TODO: maybe a proofsHash should have it's own type?
@derive(eqv, show)
case class Hashed[A <: AnyRef](signed: Signed[A], hash: Hash, proofsHash: Hash)
