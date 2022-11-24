package org.tessellation.schema

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.signature.Signed

trait BaseBlockReference {
  val parents: NonEmptyList[BlockReference]
}

trait BaseBlockData[A <: Transaction] {
  val transactions: NonEmptySet[Signed[Transaction]]
}

trait Block[A <: Transaction] extends Fiber[BaseBlockReference, BaseBlockData[A]] {
  val parent: NonEmptyList[BlockReference]
  val transactions: NonEmptySet[Signed[A]]

  val height: Height = parent.maximum.height.next
}
