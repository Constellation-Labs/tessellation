package io.constellationnetwork.block

import java.util.UUID

import cats.data.{NonEmptyList, NonEmptySet}

import scala.collection.immutable.SortedSet

import io.constellationnetwork.schema.generators.{signedOf, signedTokenLockGen, signedTransactionGen}
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.tokenLock.TokenLockBlock
import io.constellationnetwork.schema.{Block, BlockReference}
import io.constellationnetwork.security.signature.Signed

import org.scalacheck.{Arbitrary, Gen}

object generators {

  val blockReferencesGen: Gen[NonEmptyList[BlockReference]] =
    Gen.nonEmptyListOf(Arbitrary.arbitrary[BlockReference]).map(NonEmptyList.fromListUnsafe(_))

  val blockGen: Gen[Block] =
    for {
      blockReferences <- blockReferencesGen
      signedTxn <- signedTransactionGen
    } yield Block(blockReferences, NonEmptySet.fromSetUnsafe(SortedSet(signedTxn)))

  val tokenLockBlockGen: Gen[TokenLockBlock] =
    for {
      signedTxn <- signedTokenLockGen
      roundId <- Gen.delay(RoundId(UUID.randomUUID()))
    } yield TokenLockBlock(roundId, NonEmptySet.fromSetUnsafe(SortedSet(signedTxn)))

  val signedBlockGen: Gen[Signed[Block]] = signedOf(blockGen)
  val signedTokenLockBlockGen: Gen[Signed[TokenLockBlock]] = signedOf(tokenLockBlockGen)
  implicit val signedBlockArbitrary: Arbitrary[Signed[Block]] = Arbitrary(signedBlockGen)

}
