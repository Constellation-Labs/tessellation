package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency

import cats.data.NonEmptySet
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import io.constellationnetwork.currency.dataApplication.storage.CalculatedStateLocalFileSystemStorage
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.swap.{AllowSpend, CurrencyId}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.{Signed, signature}
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Files
import org.http4s.HttpRoutes
import weaver.MutableIOSuite

object DataApplicationSnapshotAcceptanceManagerSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      sp <- SecurityProvider.forAsync[IO]
      implicit0(json: JsonSerializer[IO]) <- Resource.eval(JsonSerializer.forAsync[IO])
      hasher = Hasher.forJson[IO]
    } yield (hasher, sp, json)

  private case object PreviousOnChainState extends DataOnChainState
  private case object CurrentOnChainState extends DataOnChainState
  private case object PreviousCalculatedState extends DataCalculatedState
  private case object CurrentCalculatedState extends DataCalculatedState

  private val proof: signature.SignatureProof = signature.SignatureProof(Id(Hex("")), signature.Signature(Hex("")))

  private def ord(value: Long): SnapshotOrdinal = SnapshotOrdinal(NonNegLong.unsafeFrom(value))

  private def signedArtifact(ordinal: SnapshotOrdinal, calculatedStateProof: Hash): Signed[CurrencyIncrementalSnapshot] =
    Signed(
      CurrencyIncrementalSnapshot(
        ordinal = ordinal,
        height = Height.MinValue,
        subHeight = SubHeight.MinValue,
        lastSnapshotHash = Hash.empty,
        blocks = SortedSet.empty,
        rewards = SortedSet.empty,
        tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
        stateProof = CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
        epochProgress = EpochProgress.MinValue,
        dataApplication = Some(DataApplicationPart(Array.emptyByteArray, Nil, calculatedStateProof, None)),
        messages = None,
        globalSnapshotSyncs = None,
        feeTransactions = None,
        artifacts = None,
        allowSpendBlocks = None,
        tokenLockBlocks = None,
        globalSyncView = None
      ),
      NonEmptySet.one(proof)
    )

  private def snapshotInfo: CurrencySnapshotInfo =
    CurrencySnapshotInfo(SortedMap.empty, SortedMap.empty, None, None, None, None, None, None, None)

  private final case class ServiceProbe(
    calculatedStateR: Ref[IO, (SnapshotOrdinal, DataCalculatedState)],
    getCalculatedStateCallsR: Ref[IO, Int],
    setCalculatedStateCallsR: Ref[IO, Int],
    combineCallsR: Ref[IO, Int],
    deserializeStateCallsR: Ref[IO, Int],
    hashCalculatedStateCallsR: Ref[IO, Int]
  )

  private object ServiceProbe {
    def make(ordinal: SnapshotOrdinal, state: DataCalculatedState): IO[ServiceProbe] =
      (
        Ref.of[IO, (SnapshotOrdinal, DataCalculatedState)]((ordinal, state)),
        Ref.of[IO, Int](0),
        Ref.of[IO, Int](0),
        Ref.of[IO, Int](0),
        Ref.of[IO, Int](0),
        Ref.of[IO, Int](0)
      ).mapN(ServiceProbe.apply)
  }

  private def service(
    probe: ServiceProbe,
    calculatedStateHash: DataCalculatedState => Hash
  ): BaseDataApplicationL0Service[IO] =
    new BaseDataApplicationL0Service[IO] {
      override def serializeState(state: DataOnChainState): IO[Array[Byte]] = IO.pure(state.toString.getBytes)

      override def deserializeState(bytes: Array[Byte]): IO[Either[Throwable, DataOnChainState]] =
        probe.deserializeStateCallsR.update(_ + 1).as(PreviousOnChainState.asRight[Throwable])

      override def serializeUpdate(update: DataUpdate): IO[Array[Byte]] =
        IO.raiseError(new AssertionError("serializeUpdate must not run in this suite"))

      override def deserializeUpdate(bytes: Array[Byte]): IO[Either[Throwable, DataUpdate]] =
        IO.raiseError(new AssertionError("deserializeUpdate must not run in this suite"))

      override def serializeBlock(block: Signed[DataApplicationBlock]): IO[Array[Byte]] =
        IO.raiseError(new AssertionError("serializeBlock must not run for an empty certified block list"))

      override def deserializeBlock(bytes: Array[Byte]): IO[Either[Throwable, Signed[DataApplicationBlock]]] =
        IO.raiseError(new AssertionError("deserializeBlock must not run for an empty certified block list"))

      override def serializeCalculatedState(state: DataCalculatedState): IO[Array[Byte]] =
        IO.pure(state.toString.getBytes)

      override def deserializeCalculatedState(bytes: Array[Byte]): IO[Either[Throwable, DataCalculatedState]] =
        IO.pure(CurrentCalculatedState.asRight[Throwable])

      override def dataEncoder: io.circe.Encoder[DataUpdate] =
        throw new AssertionError("dataEncoder must not run in this suite")

      override def dataDecoder: io.circe.Decoder[DataUpdate] =
        throw new AssertionError("dataDecoder must not run in this suite")

      override def signedDataEntityDecoder: org.http4s.EntityDecoder[IO, Signed[DataUpdate]] =
        throw new AssertionError("signedDataEntityDecoder must not run in this suite")

      override def calculatedStateEncoder: io.circe.Encoder[DataCalculatedState] =
        throw new AssertionError("calculatedStateEncoder must not run in this suite")

      override def validateData(state: DataState.Base, updates: cats.data.NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[IO]
      ): IO[DataApplicationValidationErrorOr[Unit]] =
        IO.raiseError(new AssertionError("validateData must not run for an empty certified block list"))

      override def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L0NodeContext[IO]
      ): IO[DataState.Base] =
        probe.combineCallsR.update(_ + 1).as(DataState(CurrentOnChainState, CurrentCalculatedState))

      override def getCalculatedState(implicit context: L0NodeContext[IO]): IO[(SnapshotOrdinal, DataCalculatedState)] =
        probe.getCalculatedStateCallsR.update(_ + 1) >> probe.calculatedStateR.get

      override def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(
        implicit context: L0NodeContext[IO]
      ): IO[Boolean] =
        probe.setCalculatedStateCallsR.update(_ + 1) >> probe.calculatedStateR.set((ordinal, state)).as(true)

      override def routes(implicit context: L0NodeContext[IO]): HttpRoutes[IO] = HttpRoutes.empty[IO]

      override def routesPrefix: ExternalUrlPrefix = "/data-application"

      override def genesis: DataState.Base = DataState(PreviousOnChainState, PreviousCalculatedState)

      override def calculatedStateDecoder: io.circe.Decoder[DataCalculatedState] =
        throw new AssertionError("calculatedStateDecoder must not run in this suite")

      override def signedDataEntityEncoder: org.http4s.EntityEncoder[IO, Signed[DataUpdate]] =
        throw new AssertionError("signedDataEntityEncoder must not run in this suite")

      override def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[IO]): IO[Hash] =
        probe.hashCalculatedStateCallsR.update(_ + 1).as(calculatedStateHash(state))

      override def getTokenUnlocks(state: DataState.Base)(
        implicit context: L0NodeContext[IO],
        async: cats.effect.Async[IO],
        hasher: Hasher[IO]
      ): IO[SortedSet[TokenUnlock]] = IO.pure(SortedSet.empty)

      override def onGlobalSnapshotPull(snapshot: Hashed[GlobalIncrementalSnapshot], context: GlobalSnapshotInfo): IO[Unit] = IO.unit

      override def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): IO[Unit] = IO.unit
    }

  private def context(
    sp: SecurityProvider[IO],
    maybeLastSnapshot: Option[Hashed[CurrencyIncrementalSnapshot]]
  ): L0NodeContext[IO] = new L0NodeContext[IO] {
    override def getLastSynchronizedGlobalSnapshot: IO[Option[GlobalIncrementalSnapshot]] = IO.pure(None)

    override def getLastSynchronizedGlobalSnapshotCombined: IO[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] = IO.pure(None)

    override def getLastSynchronizedAllowSpends
      : IO[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]] = IO.pure(None)

    override def getLastSynchronizedTokenLocks: IO[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]] = IO.pure(None)

    override def getLastCurrencySnapshot: IO[Option[Hashed[CurrencyIncrementalSnapshot]]] = IO.pure(maybeLastSnapshot)

    override def getCurrencySnapshot(ordinal: SnapshotOrdinal): IO[Option[Hashed[CurrencyIncrementalSnapshot]]] = IO.pure(None)

    override def getLastCurrencySnapshotCombined: IO[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
      IO.pure(maybeLastSnapshot.map(_ -> snapshotInfo))

    override def securityProvider: SecurityProvider[IO] = sp

    override def getCurrencyId: IO[CurrencyId] = IO.raiseError(new AssertionError("getCurrencyId must not run in this suite"))

    override def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]] = None
  }

  private def manager(
    service: BaseDataApplicationL0Service[IO],
    context: L0NodeContext[IO],
    storage: CalculatedStateLocalFileSystemStorage[IO]
  )(implicit hasher: Hasher[IO], json: JsonSerializer[IO], securityProvider: SecurityProvider[IO]) =
    DataApplicationSnapshotAcceptanceManager.make[IO](service, context, storage, SnapshotOrdinal.MinValue)

  test("exact-current replay verifies the certified hash and repairs storage without recalculating or setting service state") {
    case (hasher, securityProvider, json) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = securityProvider
      implicit val j: JsonSerializer[IO] = json

      val ordinal = ord(11L)
      val certifiedHash = Hash("certified")

      Files[IO].tempDirectory.use { tempDir =>
        for {
          probe <- ServiceProbe.make(ordinal, CurrentCalculatedState)
          storage <- CalculatedStateLocalFileSystemStorage.make[IO](tempDir)
          ctx = context(securityProvider, None)
          _ <- manager(service(probe, _ => certifiedHash), ctx, storage)
            .consumeSignedMajorityArtifact(None, signedArtifact(ordinal, certifiedHash), ord(100L))
          stored <- storage.read[DataCalculatedState](ordinal)(bytes =>
            IO.raiseUnless(bytes.sameElements(CurrentCalculatedState.toString.getBytes))(
              new AssertionError("unexpected persisted calculated-state bytes")
            ).as(CurrentCalculatedState)
          )
          getCalls <- probe.getCalculatedStateCallsR.get
          setCalls <- probe.setCalculatedStateCallsR.get
          combineCalls <- probe.combineCallsR.get
          deserializeCalls <- probe.deserializeStateCallsR.get
          hashCalls <- probe.hashCalculatedStateCallsR.get
        } yield expect(stored.contains(CurrentCalculatedState))
          .and(expect(getCalls == 1))
          .and(expect(setCalls == 0))
          .and(expect(combineCalls == 0))
          .and(expect(deserializeCalls == 0))
          .and(expect(hashCalls == 1))
      }
  }

  test("exact-current replay rejects a calculated-state hash conflict without writing or setting state") {
    case (hasher, securityProvider, json) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = securityProvider
      implicit val j: JsonSerializer[IO] = json

      val ordinal = ord(11L)
      val certifiedHash = Hash("certified")
      val localHash = Hash("local")

      Files[IO].tempDirectory.use { tempDir =>
        for {
          probe <- ServiceProbe.make(ordinal, CurrentCalculatedState)
          storage <- CalculatedStateLocalFileSystemStorage.make[IO](tempDir)
          ctx = context(securityProvider, None)
          result <- manager(service(probe, _ => localHash), ctx, storage)
            .consumeSignedMajorityArtifact(None, signedArtifact(ordinal, certifiedHash), ord(100L))
            .attempt
          exists <- storage.exists(ordinal)
          setCalls <- probe.setCalculatedStateCallsR.get
          combineCalls <- probe.combineCallsR.get
        } yield expect(
          result.left.exists {
            case DataApplicationSnapshotAcceptanceManager.CalculatedStateHashDoesNotMatchMajority(`localHash`, `certifiedHash`) => true
            case _                                                                                                             => false
          }
        ).and(expect(!exists)).and(expect(setCalls == 0)).and(expect(combineCalls == 0))
      }
  }

  test("replay fails closed when the service calculated state is ahead of the certified artifact") {
    case (hasher, securityProvider, json) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = securityProvider
      implicit val j: JsonSerializer[IO] = json

      val artifactOrdinal = ord(11L)
      val certifiedHash = Hash("certified")

      Files[IO].tempDirectory.use { tempDir =>
        for {
          probe <- ServiceProbe.make(ord(12L), CurrentCalculatedState)
          storage <- CalculatedStateLocalFileSystemStorage.make[IO](tempDir)
          ctx = context(securityProvider, None)
          result <- manager(service(probe, _ => certifiedHash), ctx, storage)
            .consumeSignedMajorityArtifact(None, signedArtifact(artifactOrdinal, certifiedHash), ord(100L))
            .attempt
          exists <- storage.exists(artifactOrdinal)
          setCalls <- probe.setCalculatedStateCallsR.get
          combineCalls <- probe.combineCallsR.get
          hashCalls <- probe.hashCalculatedStateCallsR.get
        } yield expect(result.left.exists(_.getMessage.contains("Calculated state is ahead of replayed artifact")))
          .and(expect(!exists))
          .and(expect(setCalls == 0))
          .and(expect(combineCalls == 0))
          .and(expect(hashCalls == 0))
      }
  }

  test("behind replay keeps the normal calculate, verify, set, and persist path") {
    case (hasher, securityProvider, json) =>
      implicit val h: Hasher[IO] = hasher
      implicit val sp: SecurityProvider[IO] = securityProvider
      implicit val j: JsonSerializer[IO] = json

      val previousOrdinal = ord(10L)
      val artifactOrdinal = ord(11L)
      val certifiedHash = Hash("certified")
      val previousDataApplication = DataApplicationPart(Array.emptyByteArray, Nil, Hash("previous"), None)

      Files[IO].tempDirectory.use { tempDir =>
        for {
          previous <- signedArtifact(previousOrdinal, Hash("previous")).toHashed[IO]
          probe <- ServiceProbe.make(previousOrdinal, PreviousCalculatedState)
          storage <- CalculatedStateLocalFileSystemStorage.make[IO](tempDir)
          ctx = context(securityProvider, previous.some)
          _ <- manager(service(probe, _ => certifiedHash), ctx, storage)
            .consumeSignedMajorityArtifact(previousDataApplication.some, signedArtifact(artifactOrdinal, certifiedHash), ord(100L))
          installed <- probe.calculatedStateR.get
          stored <- storage.read[DataCalculatedState](artifactOrdinal)(bytes =>
            IO.raiseUnless(bytes.sameElements(CurrentCalculatedState.toString.getBytes))(
              new AssertionError("unexpected persisted calculated-state bytes")
            ).as(CurrentCalculatedState)
          )
          getCalls <- probe.getCalculatedStateCallsR.get
          setCalls <- probe.setCalculatedStateCallsR.get
          combineCalls <- probe.combineCallsR.get
          deserializeCalls <- probe.deserializeStateCallsR.get
          hashCalls <- probe.hashCalculatedStateCallsR.get
        } yield expect(installed == (artifactOrdinal, CurrentCalculatedState))
          .and(expect(stored.contains(CurrentCalculatedState)))
          .and(expect(getCalls == 2))
          .and(expect(setCalls == 1))
          .and(expect(combineCalls == 1))
          .and(expect(deserializeCalls == 1))
          .and(expect(hashCalls == 2))
      }
  }
}
