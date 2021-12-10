package org.tessellation.dag.types

import cats.data.NonEmptyList
import cats.syntax.reducible._

import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import io.estatico.newtype.ops._

@derive(encoder, decoder)
case class DAGBlock(transactions: Set[Signed[Transaction]], parent: NonEmptyList[BlockReference]) {
  val height: Height = Height(parent.minimum.height.coerce + 1L)
}
