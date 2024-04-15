package org.tessellation.currency.l1.http

import cats.data.NonEmptySet
import cats.effect.std.{Queue, Random, Supervisor}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import org.tessellation.currency.dataApplication.{ConsensusInput, DataUpdate, L1NodeContext}
import org.tessellation.currency.l1.DummyDataApplicationL1Service
import org.tessellation.currency.l1.DummyDataApplicationState.{DummyUpdate, dummyUpdateGen}
import org.tessellation.currency.l1.node.L1NodeContext
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.JsonSerializer
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.domain.queue.ViewableQueue
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.schema._
import org.tessellation.schema.generators.signedOf
import org.tessellation.schema.peer.L0Peer
import org.tessellation.schema.snapshot.{Snapshot, SnapshotInfo}
import org.tessellation.security._
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar

import org.http4s.Method.GET
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalacheck.Gen
import suite.HttpSuite

object DataApplicationRoutesSuite extends HttpSuite {

  type Res = (SecurityProvider[IO], Hasher[IO], Supervisor[IO], Random[IO])

  def construct(updateQueue: ViewableQueue[F, Signed[DataUpdate]]): IO[HttpRoutes[IO]] =
    sharedResource.use { res =>
      implicit val (sp, h, sv, r) = res
      for {
        lastGlobalSnapshotStorage <- mockLastSnapshotStorage[GlobalIncrementalSnapshot, GlobalSnapshotInfo]
        lastCurrencySnapshotStorage <- mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
        implicit0(ctx: L1NodeContext[IO]) = L1NodeContext.make[IO](lastGlobalSnapshotStorage, lastCurrencySnapshotStorage)
        consensusQueue <- Queue.unbounded[F, Signed[ConsensusInput.PeerConsensusInput]]
        l0ClusterStorage <- mockL0ClusterStorage
        l1Service <- DummyDataApplicationL1Service.make[IO]
        dataApi = DataApplicationRoutes(
          consensusQueue,
          l0ClusterStorage,
          l1Service,
          updateQueue,
          lastGlobalSnapshotStorage,
          lastCurrencySnapshotStorage
        )
      } yield dataApi.publicRoutes
    }

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(json2bin: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h: Hasher[IO] = Hasher.forJson[IO]
    sv <- Supervisor[IO]
    r <- Random.scalaUtilRandom.asResource
  } yield (sp, h, sv, r)

  def mockLastSnapshotStorage[A <: Snapshot, B <: SnapshotInfo[_]]: IO[LastSnapshotStorage[IO, A, B]] = IO.pure(
    new LastSnapshotStorage[IO, A, B] {
      override def set(snapshot: Hashed[A], state: B): IO[Unit] = ???

      override def setInitial(snapshot: Hashed[A], state: B): IO[Unit] = ???

      override def get: IO[Option[Hashed[A]]] = ???

      override def getCombined: IO[Option[(Hashed[A], B)]] = ???

      override def getCombinedStream: fs2.Stream[IO, Option[(Hashed[A], B)]] = ???

      override def getOrdinal: IO[Option[SnapshotOrdinal]] = ???

      override def getHeight: IO[Option[height.Height]] = ???
    }
  )

  def mockL0ClusterStorage: IO[L0ClusterStorage[IO]] = IO.pure(
    new L0ClusterStorage[IO] {
      override def getPeers: IO[NonEmptySet[L0Peer]] = ???

      override def getPeer(id: peer.PeerId): IO[Option[L0Peer]] = ???

      override def getRandomPeer: IO[L0Peer] = ???

      override def addPeers(l0Peers: Set[L0Peer]): IO[Unit] = ???

      override def setPeers(l0Peers: NonEmptySet[L0Peer]): IO[Unit] = ???
    }
  )

  test("GET returns Http Status code 200 with empty array") {
    val req: Request[IO] = GET(uri"/data")

    for {
      dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
      endpoint <- construct(dataQueue)
      testResult <- expectHttpBodyAndStatus(endpoint, req)(List.empty[Signed[DummyUpdate]], Status.Ok)
    } yield testResult
  }

  test("GET returns single value") {
    val req: Request[IO] = GET(uri"/data")

    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
        _ <- dataQueue.offer(update)
        endpoint <- construct(dataQueue)
        testResult <- expectHttpBodyAndStatus(endpoint, req)(List(update), Status.Ok)
      } yield testResult
    }
  }

  test("GET returns multiple values") {
    val req: Request[IO] = GET(uri"/data")

    val gen = for {
      size <- Gen.chooseNum(1, 1)
      updates <- Gen.listOfN(size, signedOf(dummyUpdateGen))
    } yield updates

    forall(gen) { updates =>
      for {
        dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
        _ <- dataQueue.tryOfferN(updates).flatMap { rejected =>
          IO.raiseError(new RuntimeException("Updates failed to enter queue")).whenA(rejected.nonEmpty)
        }
        endpoint <- construct(dataQueue)
        testResult <- expectHttpBodyAndStatus(endpoint, req)(updates, Status.Ok)
      } yield testResult
    }
  }
}
