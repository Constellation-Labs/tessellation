package io.constellationnetwork.dag.l0.http.routes

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kernel._
import io.constellationnetwork.node.shared.config.DelegatedRewardsEra
import io.constellationnetwork.node.shared.domain.cluster.services.Cluster
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator
import io.constellationnetwork.node.shared.domain.node.UpdateNodeParametersValidator.UpdateNodeParametersValidationErrorOr
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.node.shared.infrastructure.node.NodeStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.cluster.ClusterSessionToken
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed

import io.circe.Json
import io.circe.syntax._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits._
import weaver.MutableIOSuite

object NodeParametersRoutesSuite extends MutableIOSuite {
  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(json: JsonSerializer[IO]) <- Resource.eval(JsonSerializer.forSync[IO])
  } yield (json, Hasher.forJson[IO], sp)

  private val activation = SnapshotOrdinal.unsafeApply(100L)
  private val era = DelegatedRewardsEra(activation, SortedMap.empty)
  private type ValidationInput = (Signed[UpdateNodeParameters], GlobalSnapshotInfo, SnapshotOrdinal)

  test("raw signed GET retains 5% and its reference while the list shows effective 10% from activation") { res =>
    implicit val (json, hasher, sp) = res
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      peerId = PeerId.fromId(keyPair.getPublic.toId)
      signed <- Signed.forAsyncHasher[IO, UpdateNodeParameters](
        UpdateNodeParameters(
          keyPair.getPublic.toAddress,
          DelegatedStakeRewardParameters(RewardFraction(5000000)),
          NodeMetadataParameters("operator", "description"),
          UpdateNodeParametersReference.empty
        ),
        keyPair
      )
      reference <- UpdateNodeParametersReference.of[IO](signed)
      info = GlobalSnapshotInfo.empty.copy(updateNodeParameters =
        Some(SortedMap(peerId.toId -> (signed, SnapshotOrdinal.unsafeApply(90L))))
      )
      base <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue))
      checks <- List(99L, 100L, 101L).traverse { ordinal =>
        for {
          snapshot <- Signed
            .forAsyncHasher[IO, GlobalIncrementalSnapshot](base.copy(ordinal = SnapshotOrdinal.unsafeApply(ordinal)), keyPair)
          captured <- Ref.of[IO, Option[ValidationInput]](None)
          routes <- makeRoutes(snapshot, info, captured)
          raw <- routes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/node-params/${peerId.value.value}")))
          rawJson <- raw.as[Json]
          listed <- routes.orNotFound.run(Request[IO](Method.GET, Uri.unsafeFromString("/node-params")))
          listJson <- listed.as[Json]
        } yield
          expect
            .same(Status.Ok, raw.status)
            .and(expect.same(Some(signed.asJson), rawJson.hcursor.downField("latest").focus))
            .and(expect.same(Some(reference.asJson), rawJson.hcursor.downField("lastRef").focus))
            .and(expect.same(Status.Ok, listed.status))
            .and(
              expect.same(
                Right(if (ordinal < 100L) 5000000 else 10000000),
                listJson.hcursor.downArray.downField("delegatedStakeRewardParameters").get[Int]("rewardFraction")
              )
            )
      }
    } yield checks.reduce(_ and _)
  }

  test("POST supplies next candidate ordinal and the same head context without rewriting signed commission") { res =>
    implicit val (json, hasher, sp) = res
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      signed <- Signed.forAsyncHasher[IO, UpdateNodeParameters](
        UpdateNodeParameters(
          keyPair.getPublic.toAddress,
          DelegatedStakeRewardParameters(RewardFraction(0)),
          NodeMetadataParameters("operator", "description"),
          UpdateNodeParametersReference.empty
        ),
        keyPair
      )
      base <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue))
      snapshot <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](base.copy(ordinal = SnapshotOrdinal.unsafeApply(99L)), keyPair)
      info = GlobalSnapshotInfo.empty
      captured <- Ref.of[IO, Option[ValidationInput]](None)
      routes <- makeRoutes(snapshot, info, captured)
      response <- routes.orNotFound.run(Request[IO](Method.POST, Uri.unsafeFromString("/node-params")).withEntity(signed))
      actual <- captured.get
    } yield
      expect
        .same(Status.Ok, response.status)
        .and(expect.same(Some(activation), actual.map(_._3)))
        .and(expect.same(Some(info), actual.map(_._2)))
        .and(expect.same(Some(signed.asJson), actual.map(_._1.asJson)))
  }

  private def makeRoutes(
    snapshot: Signed[GlobalIncrementalSnapshot],
    info: GlobalSnapshotInfo,
    captured: Ref[IO, Option[ValidationInput]]
  )(implicit hasher: Hasher[IO], sp: SecurityProvider[IO]): IO[HttpRoutes[IO]] = {
    val storage = new SnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
      def prepend(s: Signed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo)(implicit h: Hasher[IO]): IO[Boolean] =
        IO.raiseError(new IllegalStateException("HTTP fixture does not prepend snapshots"))
      def head: IO[Option[(Signed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = IO.pure(Some((snapshot, info)))
      def headSnapshot: IO[Option[Signed[GlobalIncrementalSnapshot]]] = IO.pure(Some(snapshot))
      def get(ordinal: SnapshotOrdinal): IO[Option[Signed[GlobalIncrementalSnapshot]]] =
        IO.pure(Option.when(ordinal == snapshot.ordinal)(snapshot))
      def getHashed(ordinal: SnapshotOrdinal)(implicit hasher: Hasher[IO]): IO[Option[Hashed[GlobalIncrementalSnapshot]]] =
        get(ordinal).flatMap(_.traverse(_.toHashed[IO]))
      def get(hash: Hash): IO[Option[Signed[GlobalIncrementalSnapshot]]] =
        snapshot.toHashed[IO].map(s => Option.when(s.hash == hash)(snapshot))
      def getHash(ordinal: SnapshotOrdinal)(implicit h: Hasher[IO]): IO[Option[Hash]] = getHashed(ordinal)(h).map(_.map(_.hash))
    }
    val cluster = new Cluster[IO] {
      def info(implicit h: Hasher[IO]): IO[Set[PeerInfo]] = IO.pure(Set.empty)
      def getRegistrationRequest(implicit h: Hasher[IO]): IO[RegistrationRequest] = unexpected
      def signRequest(request: SignRequest)(implicit h: Hasher[IO]): IO[Signed[SignRequest]] = unexpected
      def leave(): IO[Unit] = unexpected
      def createSession: IO[ClusterSessionToken] = unexpected
      private def unexpected[A]: IO[A] = IO.raiseError(new IllegalStateException("Unused cluster operation in HTTP fixture"))
    }
    val validator = new UpdateNodeParametersValidator[IO] {
      def validate(
        signed: Signed[UpdateNodeParameters],
        context: GlobalSnapshotInfo,
        ordinal: SnapshotOrdinal
      ): IO[UpdateNodeParametersValidationErrorOr[Signed[UpdateNodeParameters]]] =
        captured.set(Some((signed, context, ordinal))).as(signed.validNec)
    }
    for {
      node <- NodeStorage.make[IO]
      _ <- node.setNodeState(NodeState.Ready)
    } yield
      NodeParametersRoutes[IO](
        _ => new Cell[IO, StackF, Unit, Either[CellError, Ω], Unit]((), _ => IO.pure(Right(Cell.NullTerminal)), identity),
        storage,
        node,
        cluster,
        validator,
        era
      ).publicRoutes
  }
}
