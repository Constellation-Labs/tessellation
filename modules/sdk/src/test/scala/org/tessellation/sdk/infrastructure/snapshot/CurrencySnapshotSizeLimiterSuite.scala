package org.tessellation.sdk.infrastructure.snapshot

import cats.effect.{IO, Resource}
import cats.syntax.applicative._

import scala.collection.immutable.SortedSet

import org.tessellation.block.generators.signedBlockGen
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotStateProof}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.{BlockAsActiveTip, SnapshotOrdinal, SnapshotTips}
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.hash.Hash
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object CurrencySnapshotSizeLimiterSuite extends MutableIOSuite with Checkers {

  override type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, KryoSerializer[IO]] = KryoSerializer.forAsync[IO](sdkKryoRegistrar)

  private def genCurrencyIncrementalWithBlocks = for {
    blocks <- Gen.listOf(signedBlockGen)
    asActiveTips = blocks.map(BlockAsActiveTip(_, 1L))
  } yield
    CurrencyIncrementalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Hash.empty,
      asActiveTips.toSortedSet,
      SortedSet.empty,
      SnapshotTips(SortedSet.empty, SortedSet.empty),
      CurrencySnapshotStateProof(Hash.empty, Hash.empty),
      EpochProgress.MinValue,
      None
    )

  test("limiter rejects excess blocks to fit the size limit") { implicit kryo =>
    val maxSizeInBytes: Long = 400 * 1024

    forall(genCurrencyIncrementalWithBlocks) { snapshot =>
      for {
        limiter <- CurrencySnapshotSizeLimiter.make[IO].pure[IO]
        result <- limiter.limit(snapshot, maxSizeInBytes)
        succeeded = result match {
          case Some(result) =>
            val originalSizeFits = result.oldSizeInBytes <= maxSizeInBytes
            val newSizeFits = result.newSizeInBytes <= maxSizeInBytes
            val hasExceededBlocks = result.oldSizeInBytes != result.newSizeInBytes && result.excessBlocks.nonEmpty
            originalSizeFits || (newSizeFits && hasExceededBlocks)
          case None => false
        }
      } yield expect.same(succeeded, true)
    }
  }
}
