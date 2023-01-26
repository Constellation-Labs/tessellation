package org.tessellation.schema

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

case class ParentBlockReference(parents: NonEmptyList[BlockReference])
case class BlockData[A <: Transaction](transactions: NonEmptySet[Signed[A]])

trait Block[A <: Transaction] extends Fiber[ParentBlockReference, BlockData[A]] {
  val parent: NonEmptyList[BlockReference]
  val transactions: NonEmptySet[Signed[A]]

  val height: Height = parent.maximum.height.next
}
