package io.constellationnetwork.dag.l0.http.routes

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.http4s.Method.GET
import org.http4s._
import org.http4s.client.dsl.io._
import suite.HttpSuite

/** Regression test for the v4.1.0 token-lock serving fix.
  *
  * After the MPT migration, GlobalSnapshotInfo carried in snapshotStorage.head holds only the per-snapshot
  * DELTA of activeTokenLocks; the authoritative full state lives in the MPT. This pins that GET
  * /token-locks/:address reads the MPT full state, so a lock committed in an earlier snapshot (absent from
  * the head delta) is still served -- the bug behind the delegated-staking / token-lock-replacement e2e
  * failures. If a future change reverts to reading head.info.activeTokenLocks, this test fails.
  */
object GL0TokenLockRoutesSuite extends HttpSuite {

  implicit val globalStateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  type Res = (Hasher[IO], SecurityProvider[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
    } yield (h, sp, j)

  // The route only calls `head`; every other method is unused in this path.
  private def stubStorage(
    headValue: Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
  ): SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] =
    new SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
      def prepend(snapshot: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo)(implicit hasher: Hasher[IO]): IO[Boolean] = ???
      def head: IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = headValue.pure[IO]
      def headSnapshot: IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???
      def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???
      def getHashed(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hashed[GlobalIncrementalSnapshot]]] = ???
      def get(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] = ???
      def getHash(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hash]] = ???
      def setHeadForRecovery(snapshot: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo)(implicit hasher: Hasher[IO]): IO[Unit] =
        ???
    }

  test("GET /token-locks serves an MPT-resident lock even when the head info delta is empty") {
    case (hsh, sp, jsn) =>
      implicit val hasher: Hasher[IO] = hsh
      implicit val secProvider: SecurityProvider[IO] = sp
      implicit val json: JsonSerializer[IO] = jsn

      for {
        kp <- KeyPairGenerator.makeKeyPair[IO]
        address <- kp.getPublic.toId.toAddress
        tokenLock = TokenLock(
          address,
          TokenLockAmount(100L),
          TokenLockFee(0L),
          TokenLockReference(TokenLockOrdinal(1L), Hash("ref123")),
          none,
          none,
          none
        )
        signedTokenLock <- Signed.forAsyncHasher(tokenLock, kp)

        // MPT holds the FULL state (the lock); the served head holds an EMPTY activeTokenLocks delta.
        mptProducer <- InMemoryMerklePatriciaProducer.make[IO]()
        mptStore <- MptStore.make[IO, GlobalStateKey](mptProducer, GlobalStateKey.toHex[IO])
        gsiWithLock = GlobalSnapshotInfo.empty.copy(
          activeTokenLocks = Some(SortedMap(address -> SortedSet(signedTokenLock)))
        )
        _ <- mptStore.syncFromGlobalSnapshotInfo(gsiWithLock, SnapshotOrdinal.MinValue)

        // A head whose GlobalSnapshotInfo has NO token locks (the per-snapshot delta).
        genesisSigned <- Signed.forAsyncHasher[IO, GlobalSnapshot](
          GlobalSnapshot.mkGenesis(Map.empty[Address, Balance], EpochProgress.MinValue),
          kp
        )
        genesisHashed <- genesisSigned.toHashed
        incremental <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](genesisHashed)
        signedIncremental <- Signed.forAsyncHasher(incremental, kp)
        storage = stubStorage(Some((signedIncremental, GlobalSnapshotInfo.empty)))

        routes = GL0TokenLockRoutes[IO](storage, mptStore).publicRoutes
        req = GET(Uri.unsafeFromString(s"/token-locks/${address.value.value}"))
        result <- expectHttpBodyAndStatus(routes, req)(List(tokenLock), Status.Ok)
      } yield result
  }
}
