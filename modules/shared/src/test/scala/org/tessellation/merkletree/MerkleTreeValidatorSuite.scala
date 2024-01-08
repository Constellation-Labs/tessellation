package org.tessellation.merkletree

import cats.data.{NonEmptyList, NonEmptySet, Validated}
import cats.effect.{Async, IO, Resource}
import cats.syntax.flatMap._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonHashSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}
import org.tessellation.security.{Hashed, Hasher}
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object MerkleTreeValidatorSuite extends MutableIOSuite {

  type Res = Hasher[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit res =>
      JsonHashSerializer.forSync[IO].asResource.map { implicit json =>
        Hasher.forSync[IO]
      }
    }

  test("should succeed when state matches snapshot's state proof") { implicit res =>
    val globalSnapshotInfo = GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap(Address("DAG2AUdecqFwEGcgAcH1ac2wrsg8acrgGwrQojzw") -> Balance(100L)),
      SortedMap.empty,
      SortedMap.empty
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
      SortedMap.empty
    )

    for {
      snapshot <- globalIncrementalSnapshot(globalSnapshotInfo)
      result <- StateProofValidator.validate(snapshot, GlobalSnapshotInfo.empty)
    } yield expect.same(Validated.Invalid(StateProofValidator.StateBroken(SnapshotOrdinal(NonNegLong(1L)), snapshot.hash)), result)
  }

  private def globalIncrementalSnapshot[F[_]: Async: Hasher](
    globalSnapshotInfo: GlobalSnapshotInfo
  ): F[Hashed[GlobalIncrementalSnapshot]] =
    globalSnapshotInfo.stateProof[F].flatMap { sp =>
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
          stateProof = sp
        ),
        NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(ID.Id(Hex("")), Signature(Hex("")))))
      ).toHashed[F]
    }

}
