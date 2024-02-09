package org.tessellation.json

import cats.data.NonEmptySet
import cats.effect.kernel.Sync
import cats.effect.{IO, Resource}
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.security._
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object JsonBinarySerializerSuite extends MutableIOSuite {

  type Res = Hasher[IO]

  val hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = JsonHash }

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit res =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forSync[IO](hashSelect)
      }
    }

  test("should deserialize properly serialized object") { implicit res =>
    currencyIncrementalSnapshot[IO](Hash.empty, CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty)).map { signedSnapshot =>
      val serialized = JsonBinarySerializer.serialize(signedSnapshot)
      val deserialized = JsonBinarySerializer.deserialize[Signed[CurrencyIncrementalSnapshot]](serialized)
      expect.same(Right(signedSnapshot), deserialized)
    }
  }

  test("should not deserialize different serialized object") { implicit res =>
    currencyIncrementalSnapshot[IO](Hash.empty, CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty)).map { signedSnapshot =>
      val serialized = JsonBinarySerializer.serialize(signedSnapshot)
      val deserialized = JsonBinarySerializer.deserialize[CurrencySnapshot](serialized)
      expect.same(true, deserialized.isLeft)
    }
  }

  private[json] def currencyIncrementalSnapshot[F[_]: Sync: Hasher](
    hash: Hash,
    currencySnapshotInfo: CurrencySnapshotInfo
  ): F[Signed[CurrencyIncrementalSnapshot]] =
    currencySnapshotInfo.stateProof[F](SnapshotOrdinal(NonNegLong(56L)), hashSelect).map { sp =>
      Signed(
        CurrencyIncrementalSnapshot(
          SnapshotOrdinal(NonNegLong(56L)),
          Height(123L),
          SubHeight(1L),
          hash,
          SortedSet.empty,
          SortedSet.empty,
          SnapshotTips(
            SortedSet(
              DeprecatedTip(BlockReference(Height(122L), ProofsHash("aaaa")), SnapshotOrdinal(55L)),
              DeprecatedTip(BlockReference(Height(122L), ProofsHash("cccc")), SnapshotOrdinal(55L))
            ),
            SortedSet(ActiveTip(BlockReference(Height(122L), ProofsHash("bbbb")), 2L, SnapshotOrdinal(55L)))
          ),
          stateProof = sp,
          epochProgress = EpochProgress.MinValue
        ),
        NonEmptySet.one(SignatureProof(ID.Id(Hex("")), Signature(Hex(""))))
      )
    }
}
