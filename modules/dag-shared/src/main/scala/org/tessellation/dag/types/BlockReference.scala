package org.tessellation.dag.types

import cats.Order

import org.tessellation.schema.height.Height
import org.tessellation.security.hash.ProofsHash

import derevo.cats.show
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.ops._

@derive(encoder, decoder, show)
case class BlockReference(hash: ProofsHash, height: Height)

object BlockReference {
  implicit val blockReferenceOrder: Order[BlockReference] = (x: BlockReference, y: BlockReference) =>
    implicitly[Order[Long]].compare(x.height.coerce, y.height.coerce)
}
