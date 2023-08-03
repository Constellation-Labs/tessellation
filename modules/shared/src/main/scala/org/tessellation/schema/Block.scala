package org.tessellation.schema

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.reducible._

import org.tessellation.ext.cats.syntax.next._
import org.tessellation.schema.height.Height
import org.tessellation.schema.transaction.Transaction
import org.tessellation.security.Hashed
import org.tessellation.security.signature.Signed

case class ParentBlockReference(parents: NonEmptyList[BlockReference])
case class BlockData(transactions: NonEmptySet[Signed[Transaction]])

trait Block extends Fiber[ParentBlockReference, BlockData] {
  val parent: NonEmptyList[BlockReference]
  val transactions: NonEmptySet[Signed[Transaction]]

  val height: Height = parent.maximum.height.next

  def reference: ParentBlockReference = ParentBlockReference(parent)

  def data: BlockData = BlockData(transactions)
}

object Block {

  implicit class HashedOps(hashedBlock: Hashed[Block]) {
    def ownReference = BlockReference(hashedBlock.height, hashedBlock.proofsHash)
  }

  trait BlockConstructor[B <: Block] {
    def create(parents: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[Transaction]]): B
  }
}
