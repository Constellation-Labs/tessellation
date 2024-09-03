package io.constellationnetwork.schema

import cats.effect.Async
import cats.syntax.functor._

import io.constellationnetwork.ext.derevo.ordering
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.ProofsHash
import io.constellationnetwork.security.signature.Signed

import derevo.cats.{order, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import derevo.scalacheck.arbitrary

@derive(arbitrary, encoder, decoder, order, ordering, show)
case class BlockReference(height: Height, hash: ProofsHash)

object BlockReference {
  def of[F[_]: Async: Hasher](block: Signed[Block]): F[BlockReference] =
    block.proofsHash.map(BlockReference(block.height, _))
}
