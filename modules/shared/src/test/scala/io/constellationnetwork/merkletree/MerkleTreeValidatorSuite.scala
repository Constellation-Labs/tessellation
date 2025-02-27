package io.constellationnetwork.merkletree

import cats.data.{NonEmptyList, NonEmptySet, Validated}
import cats.effect._
import cats.syntax.flatMap._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object MerkleTreeValidatorSuite extends MutableIOSuite {

  type Res = Hasher[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit res =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forJson[IO]
      }
    }

  test("should succeed when state matches snapshot's state proof") { implicit res =>
    val globalSnapshotInfo = GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap(Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> Balance(100L)),
      SortedMap.empty,
      SortedMap.empty,
      None,
      None,
      None,
      None,
      None,
      Some(SortedMap.empty)
    )
    for {
      snapshot <- globalIncrementalSnapshot(globalSnapshotInfo)
      result <- StateProofValidator.validate(snapshot, globalSnapshotInfo)
    } yield expect.same(Validated.Valid(()), result)
  }

  test("should fail when state doesn't match snapshot's state proof for") { implicit res =>
    val globalSnapshotInfo = GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap(Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> Balance(100L)),
      SortedMap.empty,
      SortedMap.empty,
      None,
      None,
      None,
      None,
      None,
      Some(SortedMap.empty)
    )

    for {
      snapshot <- globalIncrementalSnapshot(globalSnapshotInfo)
      result <- StateProofValidator.validate(snapshot, GlobalSnapshotInfo.empty)
    } yield expect.same(Validated.Invalid(StateProofValidator.StateBroken(SnapshotOrdinal(NonNegLong(1L)), snapshot.hash)), result)
  }

  private def globalIncrementalSnapshot[F[_]: Async: Hasher](
    globalSnapshotInfo: GlobalSnapshotInfo
  ): F[Hashed[GlobalIncrementalSnapshot]] =
    globalSnapshotInfo.stateProof[F](SnapshotOrdinal(NonNegLong(1L))).flatMap { sp =>
      Signed(
        GlobalIncrementalSnapshot(
          SnapshotOrdinal(NonNegLong(1L)),
          Height.MinValue,
          SubHeight.MinValue,
          Hash.empty,
          SortedSet.empty,
          SortedMap.empty,
          SortedSet.empty,
          EpochProgress.MinValue,
          NonEmptyList.of(PeerId(Hex(""))),
          SnapshotTips(SortedSet.empty, SortedSet.empty),
          stateProof = sp,
          Some(SortedSet.empty),
          Some(SortedSet.empty),
          Some(SortedMap.empty),
          None
        ),
        NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(ID.Id(Hex("")), Signature(Hex("")))))
      ).toHashed[F]
    }

}
