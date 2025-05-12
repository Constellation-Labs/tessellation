package io.constellationnetwork.currency.l1.http

import java.security.KeyPair

import cats.Applicative
import cats.data.Validated.invalidNec
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.kernel.Sync
import cats.effect.std.{Queue, Random, Supervisor}
import cats.effect.{Concurrent, IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.Errors.Noop
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.DataApplicationValidationErrorOr
import io.constellationnetwork.currency.l1.DummyDataApplicationState.{DummyUpdate, dummyUpdateGen}
import io.constellationnetwork.currency.l1.node.L1NodeContext
import io.constellationnetwork.currency.l1.{DummyDataApplicationL1Service, DummyDataApplicationState}
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo, CurrencySnapshotStateProof}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.queue.ViewableQueue
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.IdentifierStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generators.{addressGen, amountGen, signedOf}
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.L0Peer
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe._
import io.circe.generic.semiauto.{deriveDecoder, deriveEncoder}
import io.circe.syntax.EncoderOps
import org.http4s.Method.{GET, POST}
import org.http4s._
import org.http4s.circe.{jsonEncoderOf, jsonOf}
import org.http4s.client.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import org.scalacheck.Gen
import suite.HttpSuite

object DataApplicationRoutesSuite extends HttpSuite {

  type Res = (SecurityProvider[IO], Hasher[IO], Supervisor[IO], Random[IO], JsonSerializer[IO])

  val defaultL1Service = new DummyDataApplicationL1Service
  val defaultGlobalSnapshotStorage = mockLastSnapshotStorage[GlobalIncrementalSnapshot, GlobalSnapshotInfo]()
  val defaultCurrencySnapshotStorage = mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo]()

  case class DataTransactionRequest(
    data: Signed[DataUpdate],
    fee: Option[Signed[FeeTransaction]]
  )

  object DataTransactionRequest {
    implicit def dataUpdateEncoder: Encoder[DataUpdate] = DummyDataApplicationState.dataUpateEncoder
    implicit def dataUpdateDecoder: Decoder[DataUpdate] = DummyDataApplicationState.dataUpdateDecoder

    implicit val encoder: Encoder[DataTransactionRequest] = deriveEncoder[DataTransactionRequest]
    implicit val decoder: Decoder[DataTransactionRequest] = deriveDecoder[DataTransactionRequest]

    implicit def entityEncoder[G[_]: Applicative]: EntityEncoder[G, DataTransactionRequest] = jsonEncoderOf[G, DataTransactionRequest]
    implicit def entityDecoder[G[_]: Sync: Concurrent]: EntityDecoder[G, DataTransactionRequest] = jsonOf[G, DataTransactionRequest]
  }

  def construct(
    dataTransactionsQueue: ViewableQueue[F, DataTransactions],
    l1Service: BaseDataApplicationL1Service[IO] = defaultL1Service,
    lastGlobalSnapshotStorage: LastSnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] = defaultGlobalSnapshotStorage,
    lastCurrencySnapshotStorage: LastSnapshotStorage[IO, CurrencyIncrementalSnapshot, CurrencySnapshotInfo] = defaultCurrencySnapshotStorage
  ): IO[HttpRoutes[IO]] =
    sharedResource.use { res =>
      implicit val (sp, h, sv, r, js) = res
      for {
        consensusQueue <- Queue.unbounded[IO, Signed[ConsensusInput.PeerConsensusInput]]
        identifierStorage <- IdentifierStorage.make[IO]
        implicit0(ctx: L1NodeContext[IO]) = L1NodeContext
          .make[IO](lastGlobalSnapshotStorage, lastCurrencySnapshotStorage, identifierStorage)
        l0ClusterStorage <- mockL0ClusterStorage
        dataApi = DataApplicationRoutes(
          consensusQueue,
          l0ClusterStorage,
          l1Service,
          dataTransactionsQueue,
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
  } yield (sp, h, sv, r, json2bin)

  def mockLastSnapshotStorage[A <: Snapshot, B <: SnapshotInfo[_]](
    getOrdinalFn: IO[Option[SnapshotOrdinal]] = SnapshotOrdinal.MinValue.some.pure[IO],
    getCombinedFn: IO[Option[(Hashed[A], B)]] = IO.none
  ): LastSnapshotStorage[IO, A, B] =
    new LastSnapshotStorage[IO, A, B] {
      override def set(snapshot: Hashed[A], state: B): IO[Unit] = ???

      override def setInitial(snapshot: Hashed[A], state: B): IO[Unit] = ???

      override def get: IO[Option[Hashed[A]]] = ???

      override def getCombined: IO[Option[(Hashed[A], B)]] = getCombinedFn

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

      override def getRandomPeerExistentOnList(peers: List[peer.PeerId]): IO[Option[L0Peer]] = ???
    }
  )

  def valid = ().validNec.pure[IO]
  def invalid = invalidNec[DataApplicationValidationError, Unit](Noop).pure[IO]

  def makeValidatingService(
    validateUpdateFn: IO[dataApplication.DataApplicationValidationErrorOr[Unit]],
    validateFeeFn: IO[dataApplication.DataApplicationValidationErrorOr[Unit]],
    estimateFeeResult: Option[EstimatedFee] = None
  ): BaseDataApplicationL1Service[IO] =
    new DummyDataApplicationL1Service {
      override def validateUpdate(update: DataUpdate)(implicit context: L1NodeContext[IO]): IO[DataApplicationValidationErrorOr[Unit]] =
        validateUpdateFn

      override def estimateFee(
        gsOrdinal: SnapshotOrdinal
      )(update: DataUpdate)(implicit context: L1NodeContext[IO], A: Applicative[IO]): IO[EstimatedFee] =
        estimateFeeResult match {
          case None         => super.estimateFee(gsOrdinal)(update)
          case Some(result) => result.pure[IO]
        }
    }

  private def signDataTransaction[A](
    dataUpdate: A,
    keypair: KeyPair,
    serializeUpdate: A => IO[Array[Byte]]
  )(implicit sp: SecurityProvider[IO]): IO[Signed[A]] =
    serializeUpdate(dataUpdate).flatMap { bytes =>
      val hash = Hash.fromBytes(bytes)
      SignatureProof.fromHash[IO](keypair, hash).map(r => Signed(dataUpdate, NonEmptySet.one(r)))
    }

  val currencyIncrementalSnapshotGen: Gen[Signed[CurrencyIncrementalSnapshot]] =
    signedOf(
      CurrencyIncrementalSnapshot(
        ordinal = SnapshotOrdinal.MinValue,
        height = Height.MinValue,
        subHeight = SubHeight.MinValue,
        lastSnapshotHash = Hash.empty,
        blocks = SortedSet.empty,
        rewards = SortedSet.empty,
        tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
        stateProof = CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
        epochProgress = EpochProgress.MinValue,
        dataApplication = None,
        messages = None,
        None,
        None,
        None,
        None,
        None,
        None
      )
    )
  test("GET /data returns Http Status code 200 with empty array") {
    val req: Request[IO] = GET(uri"/data")

    for {
      dataQueue <- ViewableQueue.make[F, DataTransactions]
      endpoint <- construct(dataQueue)
      testResult <- expectHttpBodyAndStatus(endpoint, req)(List.empty[Signed[DummyUpdate]], Status.Ok)
    } yield testResult
  }

  test("GET /data returns single value") {
    val req: Request[IO] = GET(uri"/data")

    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, DataTransactions]
        _ <- dataQueue.offer(NonEmptyList.one[Signed[DataTransaction]](update))
        endpoint <- construct(dataQueue)
        testResult <- expectHttpBodyAndStatus(endpoint, req)(List(NonEmptyList.one(update)), Status.Ok)
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
        dataQueue <- ViewableQueue.make[F, DataTransactions]
        updatesFormatted = updates.map(update => NonEmptyList.of(update.asInstanceOf[Signed[DataTransaction]]))
        _ <- dataQueue.tryOfferN(updatesFormatted).flatMap { rejected =>
          IO.raiseError(new RuntimeException("Updates failed to enter queue")).whenA(rejected.nonEmpty)
        }
        endpoint <- construct(dataQueue)
        implicit0(encoder: Encoder[DataTransaction]) = DataTransaction.encoder(defaultL1Service.dataEncoder)
        testResult <- expectHttpBodyAndStatus(endpoint, req)(updatesFormatted, Status.Ok)
      } yield testResult
    }
  }

  test("POST /data/estimate-fee returns OK and estimated fee object if validateUpdate passes") {
    import org.http4s.circe.CirceEntityEncoder._
    val req: Request[IO] = POST(uri"/data/estimate-fee")

    val estimatedFeeGen = for {
      fee <- amountGen
      address <- addressGen
    } yield EstimatedFee(fee, address)

    val gen = for {
      update <- signedOf(dummyUpdateGen)
      maybeEstimatedFee <- Gen.option(estimatedFeeGen)
    } yield (update, maybeEstimatedFee)

    forall(gen) {
      case (update, maybeEstimateFee) =>
        for {
          dataQueue <- ViewableQueue.make[F, DataTransactions]
          l1Service = makeValidatingService(validateUpdateFn = valid, validateFeeFn = invalid, estimateFeeResult = maybeEstimateFee)
          updateHash <- EstimatedFee.getUpdateHash(update, l1Service.serializeUpdate)
          defaultResponse = EstimatedFeeResponse(EstimatedFee.empty, updateHash)
          expectedResponse = maybeEstimateFee
            .map(estimatedFee => EstimatedFeeResponse(estimatedFee, updateHash).asJson)
            .getOrElse(defaultResponse.asJson)
          endpoint <- construct(dataQueue, l1Service)
          testResult <- expectHttpBodyAndStatus(endpoint, req.withEntity(update.value))(expectedResponse, Status.Ok)
        } yield testResult
    }
  }

  test("POST /data/estimate-fee returns InternalServerError if validateUpdate fails") {
    import org.http4s.circe.CirceEntityEncoder._
    val req: Request[IO] = POST(uri"/data/estimate-fee")

    val l1Service = makeValidatingService(validateUpdateFn = invalid, validateFeeFn = invalid)

    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, DataTransactions]
        endpoint <- construct(dataQueue, l1Service)
        testResult <- expectHttpStatus(endpoint, req.withEntity(update.value))(Status.BadRequest)
      } yield testResult
    }
  }

  test("POST /data/estimate-fee returns InternalServerError if global snapshot ordinal not available") {
    import org.http4s.circe.CirceEntityEncoder._
    val req: Request[IO] = POST(uri"/data/estimate-fee")

    val l1Service = makeValidatingService(validateUpdateFn = valid, validateFeeFn = invalid)
    val globalSnapshotStorage = mockLastSnapshotStorage[GlobalIncrementalSnapshot, GlobalSnapshotInfo](
      getOrdinalFn = none.pure[IO]
    )
    forall(signedOf(dummyUpdateGen)) { update =>
      for {
        dataQueue <- ViewableQueue.make[F, DataTransactions]
        endpoint <- construct(dataQueue, l1Service, globalSnapshotStorage)
        testResult <- expectHttpStatus(endpoint, req.withEntity(update.value))(Status.InternalServerError)
      } yield testResult
    }
  }

  test("POST /data returns OK - single data update") { implicit res =>
    implicit val (sp, _, _, _, js) = res

    val req: Request[IO] = POST(uri"/data")

    val estimatedFeeGen = for {
      fee <- amountGen
      address <- addressGen
    } yield EstimatedFee(fee, address)

    val gen = for {
      update <- signedOf(dummyUpdateGen)
      currencyIncrementalSnapshot <- currencyIncrementalSnapshotGen
      maybeEstimatedFee <- Gen.option(estimatedFeeGen)
    } yield (update, currencyIncrementalSnapshot, maybeEstimatedFee)

    forall(gen) {
      case (update, currencyIncrementalSnapshot, maybeEstimateFee) =>
        for {
          dataQueue <- ViewableQueue.make[F, DataTransactions]
          l1Service = makeValidatingService(validateUpdateFn = valid, validateFeeFn = invalid, estimateFeeResult = maybeEstimateFee)
          keypair <- KeyPairGenerator.makeKeyPair
          signedUpdate <- signDataTransaction[DataUpdate](update, keypair, l1Service.serializeUpdate)

          mocked = mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
            getCombinedFn = IO.pure(
              Some(
                (
                  Hashed(currencyIncrementalSnapshot, Hash.empty, ProofsHash(Hash.empty.value)),
                  CurrencySnapshotInfo(
                    SortedMap.empty,
                    SortedMap.empty,
                    none,
                    none,
                    none,
                    none,
                    none,
                    none,
                    none
                  )
                )
              )
            )
          )
          updateHash <- EstimatedFee.getUpdateHash(update, l1Service.serializeUpdate)
          expectedResponse = JsonObject("hash" -> Json.fromString(updateHash.value))

          endpoint <- construct(dataQueue, l1Service, defaultGlobalSnapshotStorage, mocked)
          testResult <- expectHttpBodyAndStatus(endpoint, req.withEntity(signedUpdate)(l1Service.signedDataEntityEncoder))(
            expectedResponse,
            Status.Ok
          )
        } yield testResult
    }
  }

  test("POST /data returns OK - data transactions - only data update") { implicit res =>
    implicit val (sp, _, _, _, js) = res

    val req: Request[IO] = POST(uri"/data")

    val estimatedFeeGen = for {
      fee <- amountGen
      address <- addressGen
    } yield EstimatedFee(fee, address)

    val gen = for {
      update <- signedOf(dummyUpdateGen)
      currencyIncrementalSnapshot <- currencyIncrementalSnapshotGen
      maybeEstimatedFee <- Gen.option(estimatedFeeGen)
    } yield (update, currencyIncrementalSnapshot, maybeEstimatedFee)

    forall(gen) {
      case (update, currencyIncrementalSnapshot, maybeEstimateFee) =>
        for {
          dataQueue <- ViewableQueue.make[F, DataTransactions]
          l1Service = makeValidatingService(validateUpdateFn = valid, validateFeeFn = invalid, estimateFeeResult = maybeEstimateFee)
          keypair <- KeyPairGenerator.makeKeyPair
          signedUpdate <- signDataTransaction[DataUpdate](update, keypair, l1Service.serializeUpdate)
          dataTransaction = DataTransactionRequest(signedUpdate, none)

          mocked = mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
            getCombinedFn = IO.pure(
              Some(
                (
                  Hashed(currencyIncrementalSnapshot, Hash.empty, ProofsHash(Hash.empty.value)),
                  CurrencySnapshotInfo(
                    SortedMap.empty,
                    SortedMap.empty,
                    none,
                    none,
                    none,
                    none,
                    none,
                    none,
                    none
                  )
                )
              )
            )
          )
          updateHash <- EstimatedFee.getUpdateHash(update, l1Service.serializeUpdate)
          expectedResponse = JsonObject(
            "hash" -> Json.fromString(updateHash.value),
            "feeHash" -> Json.Null
          )

          endpoint <- construct(dataQueue, l1Service, defaultGlobalSnapshotStorage, mocked)
          testResult <- expectHttpBodyAndStatus(endpoint, req.withEntity(dataTransaction)(DataTransactionRequest.entityEncoder))(
            expectedResponse,
            Status.Ok
          )
        } yield testResult
    }
  }

  test("POST /data returns OK - data transactions - data update and fee transaction") { implicit res =>
    implicit val (sp, _, _, _, js) = res

    val req: Request[IO] = POST(uri"/data")

    val estimatedFeeGen = for {
      fee <- amountGen
      address <- addressGen
    } yield EstimatedFee(fee, address)

    val gen = for {
      update <- signedOf(dummyUpdateGen)
      currencyIncrementalSnapshot <- currencyIncrementalSnapshotGen
      maybeEstimatedFee <- Gen.option(estimatedFeeGen)
    } yield (update, currencyIncrementalSnapshot, maybeEstimatedFee)

    forall(gen) {
      case (update, currencyIncrementalSnapshot, maybeEstimateFee) =>
        for {
          dataQueue <- ViewableQueue.make[F, DataTransactions]
          l1Service = makeValidatingService(
            validateUpdateFn = valid,
            validateFeeFn = invalid,
            estimateFeeResult = maybeEstimateFee
          )
          keypair <- KeyPairGenerator.makeKeyPair

          signedUpdate <- signDataTransaction[DataUpdate](update, keypair, l1Service.serializeUpdate)

          dagAddress = keypair.getPublic.toAddress
          mocked = mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
            getCombinedFn = IO.pure(
              Some(
                (
                  Hashed(currencyIncrementalSnapshot, Hash.empty, ProofsHash(Hash.empty.value)),
                  CurrencySnapshotInfo(
                    SortedMap.empty,
                    SortedMap(dagAddress -> Amount(NonNegLong(10L))),
                    none,
                    none,
                    none,
                    none,
                    none,
                    none,
                    none
                  )
                )
              )
            )
          )

          updateHash <- EstimatedFee.getUpdateHash(update, l1Service.serializeUpdate)
          feeTransaction = FeeTransaction(
            source = dagAddress,
            destination = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"),
            amount = Amount(NonNegLong(1L)),
            dataUpdateRef = updateHash
          )
          signedFeeTransaction <- signDataTransaction[FeeTransaction](feeTransaction, keypair, FeeTransaction.serialize[IO])
          feeTransactionHash <- FeeTransaction.serialize[IO](feeTransaction).map(Hash.fromBytes)

          endpoint <- construct(dataQueue, l1Service, defaultGlobalSnapshotStorage, mocked)

          dataTransaction = DataTransactionRequest(
            signedUpdate,
            signedFeeTransaction.some
          )

          expectedResponse = JsonObject(
            "feeHash" -> Json.fromString(feeTransactionHash.value),
            "hash" -> Json.fromString(updateHash.value)
          )

          testResult <- expectHttpBodyAndStatus(
            endpoint,
            req.withEntity(dataTransaction)(DataTransactionRequest.entityEncoder)
          )(
            expectedResponse,
            Status.Ok
          )
        } yield testResult
    }
  }

  test("POST /data returns OK - data transactions - Bad request not enough balance") { implicit res =>
    implicit val (sp, _, _, _, js) = res

    val req: Request[IO] = POST(uri"/data")

    val estimatedFeeGen = for {
      fee <- amountGen
      address <- addressGen
    } yield EstimatedFee(fee, address)

    val gen = for {
      update <- signedOf(dummyUpdateGen)
      currencyIncrementalSnapshot <- currencyIncrementalSnapshotGen
      maybeEstimatedFee <- Gen.option(estimatedFeeGen)
    } yield (update, currencyIncrementalSnapshot, maybeEstimatedFee)

    forall(gen) {
      case (update, currencyIncrementalSnapshot, maybeEstimateFee) =>
        for {
          dataQueue <- ViewableQueue.make[F, DataTransactions]
          l1Service = makeValidatingService(
            validateUpdateFn = valid,
            validateFeeFn = invalid,
            estimateFeeResult = maybeEstimateFee
          )
          keypair <- KeyPairGenerator.makeKeyPair

          signedUpdate <- signDataTransaction[DataUpdate](update, keypair, l1Service.serializeUpdate)

          dagAddress = keypair.getPublic.toAddress
          mocked = mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
            getCombinedFn = IO.pure(
              Some(
                (
                  Hashed(currencyIncrementalSnapshot, Hash.empty, ProofsHash(Hash.empty.value)),
                  CurrencySnapshotInfo(
                    SortedMap.empty,
                    SortedMap(dagAddress -> Amount.empty),
                    none,
                    none,
                    none,
                    none,
                    none,
                    none,
                    none
                  )
                )
              )
            )
          )

          updateHash <- EstimatedFee.getUpdateHash(update, l1Service.serializeUpdate)
          feeTransaction = FeeTransaction(
            source = dagAddress,
            destination = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"),
            amount = Amount(NonNegLong(1L)),
            dataUpdateRef = updateHash
          )
          signedFeeTransaction <- signDataTransaction[FeeTransaction](feeTransaction, keypair, FeeTransaction.serialize[IO])
          endpoint <- construct(dataQueue, l1Service, defaultGlobalSnapshotStorage, mocked)

          dataTransaction = DataTransactionRequest(
            signedUpdate,
            signedFeeTransaction.some
          )

          expectedResponse = JsonObject(
            "code" -> Json.fromLong(3L),
            "message" -> Json.fromString("Invalid request body"),
            "retriable" -> Json.fromBoolean(false),
            "details" -> Json.fromJsonObject(
              JsonObject(
                "reason" -> Json.fromString("Invalid data update, reason: Chain(SourceWalletNotEnoughBalance)")
              )
            )
          )

          testResult <- expectHttpBodyAndStatus(
            endpoint,
            req.withEntity(dataTransaction)(DataTransactionRequest.entityEncoder)
          )(
            expectedResponse,
            Status.BadRequest
          )
        } yield testResult
    }
  }

  test("POST /data returns OK - data transactions - Source wallet not sign the FeeTransaction") { implicit res =>
    implicit val (sp, _, _, _, js) = res

    val req: Request[IO] = POST(uri"/data")

    val estimatedFeeGen = for {
      fee <- amountGen
      address <- addressGen
    } yield EstimatedFee(fee, address)

    val gen = for {
      update <- signedOf(dummyUpdateGen)
      currencyIncrementalSnapshot <- currencyIncrementalSnapshotGen
      maybeEstimatedFee <- Gen.option(estimatedFeeGen)
    } yield (update, currencyIncrementalSnapshot, maybeEstimatedFee)

    forall(gen) {
      case (update, currencyIncrementalSnapshot, maybeEstimateFee) =>
        for {
          dataQueue <- ViewableQueue.make[F, DataTransactions]
          l1Service = makeValidatingService(
            validateUpdateFn = valid,
            validateFeeFn = invalid,
            estimateFeeResult = maybeEstimateFee
          )
          keypair <- KeyPairGenerator.makeKeyPair
          keypair2 <- KeyPairGenerator.makeKeyPair

          signedUpdate <- signDataTransaction[DataUpdate](update, keypair, l1Service.serializeUpdate)

          dagAddress = keypair.getPublic.toAddress
          dagAddress2 = keypair2.getPublic.toAddress

          mocked = mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
            getCombinedFn = IO.pure(
              Some(
                (
                  Hashed(currencyIncrementalSnapshot, Hash.empty, ProofsHash(Hash.empty.value)),
                  CurrencySnapshotInfo(
                    SortedMap.empty,
                    SortedMap(dagAddress2 -> Amount(NonNegLong.MaxValue)),
                    none,
                    none,
                    none,
                    none,
                    none,
                    none,
                    none
                  )
                )
              )
            )
          )

          updateHash <- EstimatedFee.getUpdateHash(update, l1Service.serializeUpdate)
          feeTransaction = FeeTransaction(
            source = dagAddress2,
            destination = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"),
            amount = Amount(NonNegLong(1L)),
            dataUpdateRef = updateHash
          )
          signedFeeTransaction <- signDataTransaction[FeeTransaction](feeTransaction, keypair, FeeTransaction.serialize[IO])
          endpoint <- construct(dataQueue, l1Service, defaultGlobalSnapshotStorage, mocked)

          dataTransaction = DataTransactionRequest(
            signedUpdate,
            signedFeeTransaction.some
          )

          expectedResponse = JsonObject(
            "code" -> Json.fromLong(3L),
            "message" -> Json.fromString("Invalid request body"),
            "retriable" -> Json.fromBoolean(false),
            "details" -> Json.fromJsonObject(
              JsonObject(
                "reason" -> Json.fromString(
                  "Invalid data update, reason: Chain(SourceWalletNotSignTheTransaction)"
                )
              )
            )
          )

          testResult <- expectHttpBodyAndStatus(
            endpoint,
            req.withEntity(dataTransaction)(DataTransactionRequest.entityEncoder)
          )(
            expectedResponse,
            Status.BadRequest
          )
        } yield testResult
    }
  }

  test("POST /data returns OK - data transactions - Invalid signature") { implicit res =>
    implicit val (sp, _, _, _, js) = res

    val req: Request[IO] = POST(uri"/data")

    val estimatedFeeGen = for {
      fee <- amountGen
      address <- addressGen
    } yield EstimatedFee(fee, address)

    val gen = for {
      update <- signedOf(dummyUpdateGen)
      currencyIncrementalSnapshot <- currencyIncrementalSnapshotGen
      maybeEstimatedFee <- Gen.option(estimatedFeeGen)
    } yield (update, currencyIncrementalSnapshot, maybeEstimatedFee)

    forall(gen) {
      case (update, currencyIncrementalSnapshot, maybeEstimateFee) =>
        for {
          dataQueue <- ViewableQueue.make[F, DataTransactions]
          l1Service = makeValidatingService(
            validateUpdateFn = valid,
            validateFeeFn = invalid,
            estimateFeeResult = maybeEstimateFee
          )
          keypair <- KeyPairGenerator.makeKeyPair
          signedUpdate <- signDataTransaction[DataUpdate](update, keypair, l1Service.serializeUpdate)
          dagAddress = keypair.getPublic.toAddress

          mocked = mockLastSnapshotStorage[CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
            getCombinedFn = IO.pure(
              Some(
                (
                  Hashed(currencyIncrementalSnapshot, Hash.empty, ProofsHash(Hash.empty.value)),
                  CurrencySnapshotInfo(
                    SortedMap.empty,
                    SortedMap(dagAddress -> Amount(NonNegLong.MaxValue)),
                    none,
                    none,
                    none,
                    none,
                    none,
                    none,
                    none
                  )
                )
              )
            )
          )

          updateHash <- EstimatedFee.getUpdateHash(update, l1Service.serializeUpdate)
          feeTransaction = FeeTransaction(
            source = dagAddress,
            destination = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU"),
            amount = Amount(NonNegLong(1L)),
            dataUpdateRef = updateHash
          )
          feeTransaction2 = FeeTransaction(
            source = dagAddress,
            destination = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPXZebU"),
            amount = Amount(NonNegLong(1L)),
            dataUpdateRef = updateHash
          )
          signedFeeTransaction <- signDataTransaction[FeeTransaction](feeTransaction, keypair, FeeTransaction.serialize[IO])
          invalidSignedFeeTransaction = Signed(feeTransaction2, signedFeeTransaction.proofs)
          endpoint <- construct(dataQueue, l1Service, defaultGlobalSnapshotStorage, mocked)

          dataTransaction = DataTransactionRequest(
            signedUpdate,
            invalidSignedFeeTransaction.some
          )

          expectedResponse = JsonObject(
            "error" -> Json.fromString("Invalid signature in data transactions")
          )

          testResult <- expectHttpBodyAndStatus(
            endpoint,
            req.withEntity(dataTransaction)(DataTransactionRequest.entityEncoder)
          )(
            expectedResponse,
            Status.BadRequest
          )
        } yield testResult
    }
  }
}
