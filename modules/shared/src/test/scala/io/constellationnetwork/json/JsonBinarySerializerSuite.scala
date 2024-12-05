package io.constellationnetwork.json

import cats.data.NonEmptySet
import cats.effect.kernel.Sync
import cats.effect.{IO, Resource}
import cats.syntax.functor._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object JsonBinarySerializerSuite extends MutableIOSuite {

  type Res = Hasher[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit res =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forJson[IO]
      }
    }

  test("should deserialize properly serialized object") { implicit res =>
    currencyIncrementalSnapshot[IO](
      Hash.empty,
      CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty, None, None, None, None, None, None, None)
    ).map { signedSnapshot =>
      val serialized = JsonBinarySerializer.serialize(signedSnapshot)
      val deserialized = JsonBinarySerializer.deserialize[Signed[CurrencyIncrementalSnapshot]](serialized)
      expect.same(Right(signedSnapshot), deserialized)
    }
  }

  test("should not deserialize different serialized object") { implicit res =>
    currencyIncrementalSnapshot[IO](
      Hash.empty,
      CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty, None, None, None, None, None, None, None)
    ).map { signedSnapshot =>
      val serialized = JsonBinarySerializer.serialize(signedSnapshot)
      val deserialized = JsonBinarySerializer.deserialize[CurrencySnapshot](serialized)
      expect.same(true, deserialized.isLeft)
    }
  }

  private[json] def currencyIncrementalSnapshot[F[_]: Sync: Hasher](
    hash: Hash,
    currencySnapshotInfo: CurrencySnapshotInfo
  ): F[Signed[CurrencyIncrementalSnapshot]] =
    currencySnapshotInfo.stateProof[F](SnapshotOrdinal(NonNegLong(56L))).map { sp =>
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
          epochProgress = EpochProgress.MinValue,
          None,
          None,
          None,
          None,
          None,
          None,
          None
        ),
        NonEmptySet.one(SignatureProof(ID.Id(Hex("")), Signature(Hex(""))))
      )
    }
}
