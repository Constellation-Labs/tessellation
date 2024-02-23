package org.tessellation.block

import cats.data.{NonEmptyList, NonEmptySet}

import scala.collection.immutable.SortedSet

import org.tessellation.schema.generators.{signedOf, signedTransactionGen}
import org.tessellation.schema.{Block, BlockReference}
import org.tessellation.security.signature.Signed

import org.scalacheck.{Arbitrary, Gen}

object generators {

  val blockReferencesGen: Gen[NonEmptyList[BlockReference]] =
    Gen.nonEmptyListOf(Arbitrary.arbitrary[BlockReference]).map(NonEmptyList.fromListUnsafe(_))

  val blockGen: Gen[Block] =
    for {
      blockReferences <- blockReferencesGen
      signedTxn <- signedTransactionGen
    } yield Block(blockReferences, NonEmptySet.fromSetUnsafe(SortedSet(signedTxn)))

  val signedBlockGen: Gen[Signed[Block]] = signedOf(blockGen)
  implicit val signedBlockArbitrary: Arbitrary[Signed[Block]] = Arbitrary(signedBlockGen)

}
