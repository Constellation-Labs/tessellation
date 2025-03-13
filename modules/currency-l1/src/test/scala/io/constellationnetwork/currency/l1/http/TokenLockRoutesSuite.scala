package io.constellationnetwork.currency.l1.http

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.{Queue, Random, Supervisor}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.tokenlock.ConsensusInput
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.types.TokenLocksConfig
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.tokenlock.TokenLockValidator.{TokenLockValidationError, TokenLockValidationErrorOr}
import io.constellationnetwork.node.shared.domain.tokenlock._
import io.constellationnetwork.node.shared.http.routes.TokenLockRoutes
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.L0Peer
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.chrisdavenport.mapref.MapRef
import io.circe.Json
import org.http4s.Method.{GET, POST}
import org.http4s._
import org.http4s.client.dsl.io._
import org.http4s.implicits.http4sLiteralsSyntax
import suite.HttpSuite

object TokenLockRoutesSuite extends HttpSuite {

  type Res = (SecurityProvider[IO], Hasher[IO], Supervisor[IO], Random[IO])
  val currencyId: CurrencyId = CurrencyId(Address("DAG0CyySf35ftDQDQBnd1bdQ9aPyUdacMghpnCuM"))

  def construct(
    maybeHashedTokenLock: Option[Hashed[TokenLock]] = none
  ): IO[HttpRoutes[IO]] =
    sharedResource.use { res =>
      implicit val (_, hasher, sv, _) = res
      for {
        consensusQueue <- Queue.unbounded[IO, Signed[ConsensusInput.PeerConsensusInput]]
        l0ClusterStorage <- mockL0ClusterStorage
        tokenLockStorage <- mockTokenLockStorage(maybeHashedTokenLock)
        tokenLockService <- mockTokenLockService

        tokenRoutesApi = TokenLockRoutes(
          consensusQueue,
          l0ClusterStorage,
          tokenLockService,
          tokenLockStorage
        )
      } yield tokenRoutesApi.publicRoutes
    }

  def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(json2bin: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h: Hasher[IO] = Hasher.forJson[IO]
    sv <- Supervisor[IO]
    r <- Random.scalaUtilRandom.asResource
  } yield (sp, h, sv, r)

  def mockL0ClusterStorage: IO[L0ClusterStorage[IO]] = IO.pure(
    new L0ClusterStorage[IO] {
      override def getPeers: IO[NonEmptySet[L0Peer]] = ???

      override def getPeer(id: peer.PeerId): IO[Option[L0Peer]] = ???

      override def getRandomPeer: IO[L0Peer] = ???

      override def addPeers(l0Peers: Set[L0Peer]): IO[Unit] = ???

      override def setPeers(l0Peers: NonEmptySet[L0Peer]): IO[Unit] = ???
    }
  )

  def mockTokenLockService: IO[TokenLockService[IO]] = IO.pure(
    new TokenLockService[IO] {
      override def offer(
        tokenLock: Hashed[TokenLock]
      )(implicit hasher: Hasher[IO]): IO[Either[NonEmptyList[ContextualTokenLockValidator.ContextualTokenLockValidationError], Hash]] =
        tokenLock.hash.asRight.pure[IO]
    }
  )

  def mockEmptyMapRef(
    maybeHashedTokenLock: Option[Hashed[TokenLock]]
  ): IO[MapRef[IO, Address, Option[SortedMap[TokenLockOrdinal, StoredTokenLock]]]] = {
    val emptyMap: Map[Address, SortedMap[TokenLockOrdinal, StoredTokenLock]] =
      maybeHashedTokenLock match {
        case Some(value) =>
          Map(
            value.source -> SortedMap(TokenLockOrdinal(1L) -> WaitingTokenLock(value))
          )
        case None => Map.empty
      }

    MapRef.ofSingleImmutableMap(emptyMap)
  }

  def mockTokenLockStorage(
    maybeHashedTokenLock: Option[Hashed[TokenLock]]
  ): IO[TokenLockStorage[IO]] = mockEmptyMapRef(maybeHashedTokenLock).map { emr =>
    new TokenLockStorage[IO](
      emr,
      TokenLockReference.empty,
      ContextualTokenLockValidator.make(none, TokenLocksConfig(NonNegLong(100L)), currencyId.some)
    )
  }

  def mockTokenLockValidator: IO[TokenLockValidator[IO]] = IO.pure(new TokenLockValidator[IO] {
    override def validate(signedTokenLock: Signed[TokenLock])(
      implicit hasher: Hasher[IO]
    ): IO[TokenLockValidationErrorOr[Signed[TokenLock]]] =
      IO.pure(signedTokenLock.validNec[TokenLockValidationError])
  })

  def buildSignedLockToken(keyPair: KeyPair)(implicit s: SecurityProvider[IO], h: Hasher[IO]): IO[Signed[TokenLock]] = {
    val src = keyPair.getPublic.toAddress
    val amount = TokenLockAmount(1L)
    val fee = TokenLockFee(0L)
    val parent = TokenLockReference.empty
    val lastValidEpochProgress = EpochProgress(500L)

    val tokenLock = TokenLock(
      src,
      amount,
      fee,
      parent,
      currencyId.some,
      lastValidEpochProgress
    )

    Signed.forAsyncHasher(tokenLock, keyPair)
  }

  test("POST /token-locks returns hash") {
    import org.http4s.circe.CirceEntityEncoder._
    sharedResource.use { res =>
      implicit val (sp, h, _, _) = res
      val req: Request[IO] = POST(uri"/token-locks")

      for {
        endpoint <- construct()
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        tokenLock <- buildSignedLockToken(keyPair)
        hashedTokenLock <- tokenLock.toHashed[F]
        expectedResponse = Json.obj(
          "hash" -> Json.fromString(hashedTokenLock.hash.value)
        )
        testResult <- expectHttpBodyAndStatus(endpoint, req.withEntity(tokenLock))(
          expectedResponse,
          Status.Ok
        )
      } yield testResult
    }
  }

  test("GET /token-locks/:txnHash returns waiting transaction") {
    sharedResource.use { res =>
      implicit val (sp, h, _, _) = res
      for {
        keyPair <- KeyPairGenerator.makeKeyPair[IO]
        tokenLock <- buildSignedLockToken(keyPair)
        hashedTokenLock <- tokenLock.toHashed[F]
        expectedResponse = Json.obj(
          "transaction" -> Json.obj(
            "source" -> Json.fromString(tokenLock.source.value.value),
            "amount" -> Json.fromLong(tokenLock.amount.value),
            "fee" -> Json.fromLong(tokenLock.fee.value),
            "parent" -> Json.obj(
              "ordinal" -> Json.fromLong(0L),
              "hash" -> Json.fromString(Hash.empty.value)
            ),
            "currencyId" -> Json.fromString(currencyId.value.value.value),
            "unlockEpoch" -> Json.fromLong(500L)
          ),
          "hash" -> Json.fromString(hashedTokenLock.hash.value),
          "status" -> Json.fromString("Waiting")
        )
        endpoint <- construct(hashedTokenLock.some)
        req: Request[IO] = GET(Uri.unsafeFromString(s"/token-locks/${hashedTokenLock.hash.value}"))
        testResult <- expectHttpBodyAndStatus(endpoint, req)(
          expectedResponse,
          Status.Ok
        )
      } yield testResult
    }
  }

  test("GET /token-locks/:txnHash returns NotFound") {
    for {
      endpoint <- construct()
      req: Request[IO] = GET(Uri.unsafeFromString(s"/token-locks/${Hash.empty.value}"))
      testResult <- expectHttpStatus(endpoint, req)(Status.NotFound)
    } yield testResult
  }

  test("GET /token-locks/last-reference/:address returns last reference") {
    for {
      endpoint <- construct()
      expectedResponse = Json.obj(
        "ordinal" -> Json.fromLong(0L),
        "hash" -> Json.fromString(Hash.empty.value)
      )
      req: Request[IO] = GET(Uri.unsafeFromString(s"/token-locks/last-reference/DAG0CyySf35ftDQDQBnd1bdQ9aPyUdacMghpnCuM"))
      testResult <- expectHttpBodyAndStatus(endpoint, req)(
        expectedResponse,
        Status.Ok
      )
    } yield testResult
  }

}
