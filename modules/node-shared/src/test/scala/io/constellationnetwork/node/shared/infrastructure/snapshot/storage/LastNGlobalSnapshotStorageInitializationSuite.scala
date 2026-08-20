package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.LastGlobalSnapshotsSyncConfig
import io.constellationnetwork.node.shared.domain.snapshot.services.GlobalL0Service
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.{Hashed, Hasher}

import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import weaver.SimpleIOSuite

object LastNGlobalSnapshotStorageInitializationSuite extends SimpleIOSuite {

  private implicit val stateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  private val info =
    GlobalSnapshotInfo(
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      SortedMap.empty,
      None,
      None,
      None,
      None,
      None,
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty),
      Some(SortedMap.empty)
    )

  private def snapshot(ordinal: SnapshotOrdinal)(implicit
    hasher: Hasher[IO],
    serializer: JsonSerializer[IO]
  ): IO[Hashed[GlobalIncrementalSnapshot]] =
    info.stateProof[IO](ordinal).flatMap { stateProof =>
      Signed(
        GlobalIncrementalSnapshot(
          ordinal,
          Height.MinValue,
          SubHeight.MinValue,
          Hash.empty,
          SortedSet.empty,
          SortedMap.empty,
          SortedSet.empty,
          None,
          EpochProgress.MinValue,
          NonEmptyList.one(PeerId(Hex("peer"))),
          SnapshotTips(SortedSet.empty, SortedSet.empty),
          stateProof,
          Some(SortedSet.empty),
          Some(SortedSet.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedSet.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty),
          Some(SortedMap.empty)
        ),
        NonEmptySet.one(SignatureProof(ID.Id(Hex("peer")), Signature(Hex("signature"))))
      ).toHashed[IO]
    }

  test("a failed required-window fetch leaves initialization retryable in the same process") {
    for {
      implicit0(serializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO]
      implicit0(hasher: Hasher[IO]) = Hasher.forJson[IO]
      storage <- LastNGlobalSnapshotStorage.make[IO](LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(50)))
      parent <- snapshot(SnapshotOrdinal.unsafeApply(100L))
      failTargetOnce <- Ref.of[IO, Boolean](true)
      fetcher = new GlobalL0Service[IO] {
        def pullLatestSnapshot: IO[LatestSnapshotTuple] = ???
        def pullLatestSnapshotFromRandomPeer: IO[LatestSnapshotTuple] = ???
        def pullLatestSnapshotIfNewer(localOrdinal: SnapshotOrdinal, localHash: Hash): IO[Option[LatestSnapshotTuple]] = ???
        def queryLatestEpochProgress: IO[Option[EpochProgress]] = ???
        def pullGlobalSnapshots: IO[Either[LatestSnapshotTuple, List[Hashed[GlobalIncrementalSnapshot]]]] = ???
        def pullGlobalSnapshots(ordinal: SnapshotOrdinal): IO[Either[LatestSnapshotTuple, List[Hashed[GlobalIncrementalSnapshot]]]] = ???
        def pullGlobalSnapshot(ordinal: SnapshotOrdinal): IO[Option[Hashed[GlobalIncrementalSnapshot]]] =
          if (ordinal === SnapshotOrdinal.unsafeApply(98L))
            failTargetOnce.getAndSet(false).flatMap {
              case true  => none[Hashed[GlobalIncrementalSnapshot]].pure[IO]
              case false => snapshot(ordinal).map(_.some)
            }
          else snapshot(ordinal).map(_.some)
        def pullGlobalSnapshot(hash: Hash): IO[Option[Hashed[GlobalIncrementalSnapshot]]] = ???
      }
      first <- storage.setInitialFetchingGL0(parent, info, fetcher.asLeft.some, none).attempt
      afterFailure <- storage.getCombined
      _ <- storage.setInitialFetchingGL0(parent, info, fetcher.asLeft.some, none)
      afterRetry <- storage.getCombined
      retained <- storage.getLastN
    } yield
      expect(first.isLeft) &&
        expect(afterFailure.isEmpty) &&
        expect(afterRetry.exists(_._1.hash === parent.hash)) &&
        expect(retained.exists(_.ordinal === SnapshotOrdinal.unsafeApply(98L)))
  }
}
