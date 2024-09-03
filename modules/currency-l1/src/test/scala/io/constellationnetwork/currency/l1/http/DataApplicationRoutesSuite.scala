package io.constellationnetwork.currency.l1.http

import cats.Applicative
import cats.data.NonEmptySet
import cats.data.Validated.invalidNec
import cats.effect.std.{Queue, Random, Supervisor}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.currency.l1.DummyDataApplicationL1Service
import io.constellationnetwork.currency.l1.DummyDataApplicationState.{DummyUpdate, dummyUpdateGen}
import io.constellationnetwork.currency.l1.node.L1NodeContext
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.queue.ViewableQueue
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.generators.signedOf
import io.constellationnetwork.schema.peer.L0Peer
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar

import io.circe.Json
import org.http4s.Method.{GET, POST}
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalacheck.Gen
import suite.HttpSuite

object DataApplicationRoutesSuite extends HttpSuite {

  type Res = (SecurityProvider[IO], Hasher[IO], Supervisor[IO], Random[IO])

  val defaultL1Service = new DummyDataApplicationL1Service
  val defaultGlobalSnapshotStorage = mockLastSnapshotStorage[GlobalIncrementalSnapshot, GlobalSnapshotInfo]()
  val defaultCurrencySnapshotStorage = mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo]()

  def construct(
    updateQueue: ViewableQueue[F, Signed[DataUpdate]],
    l1Service: BaseDataApplicationL1Service[IO] = defaultL1Service,
    lastGlobalSnapshotStorage: LastSnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] = defaultGlobalSnapshotStorage
  ): IO[HttpRoutes[IO]] =
    sharedResource.use { res =>
      implicit val (sp, h, sv, r) = res
      for {
        consensusQueue <- Queue.unbounded[IO, Signed[ConsensusInput.PeerConsensusInput]]
        implicit0(ctx: L1NodeContext[IO]) = L1NodeContext.make[IO](lastGlobalSnapshotStorage, defaultCurrencySnapshotStorage)
        l0ClusterStorage <- mockL0ClusterStorage
        dataApi = DataApplicationRoutes(
          consensusQueue,
          l0ClusterStorage,
          l1Service,
          updateQueue,
          lastGlobalSnapshotStorage,
          defaultCurrencySnapshotStorage
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

  def mockLastSnapshotStorage[A <: Snapshot, B <: SnapshotInfo[_]](
    getOrdinalFn: IO[Option[SnapshotOrdinal]] = SnapshotOrdinal.MinValue.some.pure[IO]
  ): LastSnapshotStorage[IO, A, B] =
    new LastSnapshotStorage[IO, A, B] {
      override def set(snapshot: Hashed[A], state: B): IO[Unit] = ???

      override def setInitial(snapshot: Hashed[A], state: B): IO[Unit] = ???

      override def get: IO[Option[Hashed[A]]] = ???

      override def getCombined: IO[Option[(Hashed[A], B)]] = ???

      override def getCombinedStream: fs2.Stream[IO, Option[(Hashed[A], B)]] = ???

      override def getOrdinal: IO[Option[SnapshotOrdinal]] = getOrdinalFn

      override def getHeight: IO[Option[height.Height]] = ???
    }

  def mockL0ClusterStorage: IO[L0ClusterStorage[IO]] = IO.pure(
    new L0ClusterStorage[IO] {
      override def getPeers: IO[NonEmptySet[L0Peer]] = ???

      override def getPeer(id: peer.PeerId): IO[Option[L0Peer]] = ???

      override def getRandomPeer: IO[L0Peer] = ???

      override def addPeers(l0Peers: Set[L0Peer]): IO[Unit] = ???

      override def setPeers(l0Peers: NonEmptySet[L0Peer]): IO[Unit] = ???
    }
  )

  def valid = ().validNec.pure[IO]
  def invalid = invalidNec[DataApplicationValidationError, Unit](Noop).pure[IO]

  def makeValidatingService(
    validateUpdateFn: IO[dataApplication.DataApplicationValidationErrorOr[Unit]],
    validateFeeFn: IO[dataApplication.DataApplicationValidationErrorOr[Unit]]
  ): BaseDataApplicationL1Service[IO] =
    new DummyDataApplicationL1Service {
      override def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[IO]): IO[DataApplicationValidationErrorOr[Unit]] =
        validateUpdateFn

      override def validateFee(gsOrdinal: SnapshotOrdinal)(update: Signed[DataUpdate])(
        implicit context: L1NodeContext[IO],
        A: Applicative[IO]
      ): IO[dataApplication.DataApplicationValidationErrorOr[Unit]] = validateFeeFn
    }

  test("GET /data returns Http Status code 200 with empty array") {
    val req: Request[IO] = GET(uri"/data")

    for {
      dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
      endpoint <- construct(dataQueue)
      testResult <- expectHttpBodyAndStatus(endpoint, req)(List.empty[Signed[DummyUpdate]], Status.Ok)
    } yield testResult
  }

  test("GET /data returns single value") {
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

  test("GET /data returns multiple values") {
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

  test("POST /data/validate returns OK with empty JSON object if all validations pass") {
    import org.http4s.circe.CirceEntityEncoder._
    val req: Request[IO] = POST(uri"/data/validate")

    val l1Service = makeValidatingService(validateUpdateFn = valid, validateFeeFn = valid)

    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
        endpoint <- construct(dataQueue, l1Service)
        testResult <- expectHttpBodyAndStatus(endpoint, req.withEntity(update))(Json.obj(), Status.Ok)
      } yield testResult
    }
  }

  test("POST /data/validate returns BadRequest if validateFee fails") {
    import org.http4s.circe.CirceEntityEncoder._
    val req: Request[IO] = POST(uri"/data/validate")

    val l1Service = makeValidatingService(validateUpdateFn = valid, validateFeeFn = invalid)

    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
        endpoint <- construct(dataQueue, l1Service)
        testResult <- expectHttpStatus(endpoint, req.withEntity(update))(Status.BadRequest)
      } yield testResult
    }
  }

  test("POST /data/validate returns InternalServerError if validateUpdate fails") {
    import org.http4s.circe.CirceEntityEncoder._
    val req: Request[IO] = POST(uri"/data/validate")

    val l1Service = makeValidatingService(validateUpdateFn = invalid, validateFeeFn = valid)

    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
        endpoint <- construct(dataQueue, l1Service)
        testResult <- expectHttpStatus(endpoint, req.withEntity(update))(Status.BadRequest)
      } yield testResult
    }
  }

  test("POST /data/validate returns InternalServerError if validateUpdate and validateFee fails") {
    import org.http4s.circe.CirceEntityEncoder._
    val req: Request[IO] = POST(uri"/data/validate")

    val l1Service = makeValidatingService(validateUpdateFn = invalid, validateFeeFn = invalid)

    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
        endpoint <- construct(dataQueue, l1Service)
        testResult <- expectHttpStatus(endpoint, req.withEntity(update))(Status.BadRequest)
      } yield testResult
    }
  }

  test("POST /data/validate returns InternalServerError if global snapshot ordinal not available") {
    import org.http4s.circe.CirceEntityEncoder._
    val req: Request[IO] = POST(uri"/data/validate")

    val l1Service = makeValidatingService(validateUpdateFn = valid, validateFeeFn = valid)
    val globalSnapshotStorage = mockLastSnapshotStorage[GlobalIncrementalSnapshot, GlobalSnapshotInfo](
      getOrdinalFn = none.pure[IO]
    )
    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, Signed[DataUpdate]]
        endpoint <- construct(dataQueue, l1Service, globalSnapshotStorage)
        testResult <- expectHttpStatus(endpoint, req.withEntity(update))(Status.InternalServerError)
      } yield testResult
    }
  }
}
