package io.constellationnetwork.node.shared.infrastructure.dataApplication

import cats.data.NonEmptySet
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.dataApplication.dataApplication.{DataApplicationBlock, DataApplicationValidationErrorOr}
import io.constellationnetwork.currency.dataApplication.storage.{CalculatedStateLocalFileSystemStorage, TraverseLocalFileSystemTempStorage}
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.routes.internal.ExternalUrlPrefix
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap.{AllowSpend, CurrencyId}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.{Signed, signature}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.io.file.Files
import weaver.MutableIOSuite

case object TestOnChain extends DataOnChainState
case object TestCalculated extends DataCalculatedState
case class TestUpdate(value: Int) extends DataUpdate

object DataApplicationTraverseSuite extends MutableIOSuite {

  type Res = (Hasher[IO], SecurityProvider[IO], HasherSelector[IO], KryoSerializer[IO], JsonSerializer[IO])

  override def sharedResource: Resource[IO, Res] =
    for {
      sp <- SecurityProvider.forAsync[IO]
      implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
      implicit0(k: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](Map.empty)
      h = Hasher.forJson[IO]
      hs = HasherSelector.forSyncAlwaysCurrent[IO](h)
    } yield (h, sp, hs, k, j)

  val testAddress: Address = Address("DAG53ho9ssY8KYQdjxsWPYgNbDJ1YqM2RaPDZebU")
  val testSignatureProof: signature.SignatureProof = signature.SignatureProof(Id(Hex("")), signature.Signature(Hex("")))

  def ord(n: Long): SnapshotOrdinal = SnapshotOrdinal(NonNegLong.unsafeFrom(n))

  def signedBlockWithUpdate(value: Int): Signed[DataApplicationBlock] = {
    val update: Signed[DataUpdate] = Signed(TestUpdate(value), NonEmptySet.one(testSignatureProof))
    Signed(
      DataApplicationBlock(
        RoundId(java.util.UUID.randomUUID()),
        cats.data.NonEmptyList.one(cats.data.NonEmptyList.one(update)),
        cats.data.NonEmptyList.one(cats.data.NonEmptyList.one(Hash.empty))
      ),
      NonEmptySet.one(testSignatureProof)
    )
  }

  def signedBlockWithUpdateAndFee(value: Int, dataUpdateRef: Hash): Signed[DataApplicationBlock] = {
    val update: Signed[DataTransaction] = Signed(TestUpdate(value), NonEmptySet.one(testSignatureProof))
    val fee: Signed[DataTransaction] = Signed(
      FeeTransaction(testAddress, testAddress, Amount(NonNegLong.unsafeFrom(1L)), dataUpdateRef),
      NonEmptySet.one(testSignatureProof)
    )

    Signed(
      DataApplicationBlock(
        RoundId(java.util.UUID.randomUUID()),
        cats.data.NonEmptyList.one(cats.data.NonEmptyList.of(update, fee)),
        cats.data.NonEmptyList.one(cats.data.NonEmptyList.one(Hash.empty))
      ),
      NonEmptySet.one(testSignatureProof)
    )
  }

  def mkSnapshot(ordinal: Long, withDataApplication: Boolean): Signed[CurrencyIncrementalSnapshot] =
    Signed(
      CurrencyIncrementalSnapshot(
        ordinal = ord(ordinal),
        height = Height.MinValue,
        subHeight = SubHeight.MinValue,
        lastSnapshotHash = Hash.empty,
        blocks = SortedSet.empty,
        rewards = SortedSet.empty,
        tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
        stateProof = CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None),
        epochProgress = EpochProgress.MinValue,
        dataApplication =
          if (withDataApplication) Some(DataApplicationPart(Array.emptyByteArray, List(Array.emptyByteArray), Hash.empty, None)) else None,
        messages = None,
        globalSnapshotSyncs = None,
        feeTransactions = None,
        artifacts = None,
        allowSpendBlocks = None,
        tokenLockBlocks = None,
        globalSyncView = None
      ),
      NonEmptySet.one(testSignatureProof)
    )

  // A tip far away from the replayed ordinals: if replayScopedContext failed to override
  // getLastCurrencySnapshot, combine would observe this fixed ordinal for every call instead
  // of each ordinal's true predecessor - exactly the bug this suite guards against.
  val tipOrdinal: Long = 999L

  def fakeTipContext(implicit hasher: Hasher[IO]): L0NodeContext[IO] = new L0NodeContext[IO] {
    def getLastSynchronizedGlobalSnapshot: IO[Option[GlobalIncrementalSnapshot]] = IO.raiseError(new NotImplementedError)
    def getLastSynchronizedGlobalSnapshotCombined: IO[Option[(GlobalIncrementalSnapshot, GlobalSnapshotInfo)]] =
      IO.raiseError(new NotImplementedError)
    def getLastSynchronizedAllowSpends: IO[Option[SortedMap[Option[Address], SortedMap[Address, SortedSet[Signed[AllowSpend]]]]]] =
      IO.raiseError(new NotImplementedError)
    def getLastSynchronizedTokenLocks: IO[Option[SortedMap[Address, SortedSet[Signed[TokenLock]]]]] =
      IO.raiseError(new NotImplementedError)
    def getLastCurrencySnapshot: IO[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      mkSnapshot(tipOrdinal, withDataApplication = false).toHashed.map(_.some)
    def getCurrencySnapshot(ordinal: SnapshotOrdinal): IO[Option[Hashed[CurrencyIncrementalSnapshot]]] =
      IO.raiseError(new NotImplementedError)
    def getLastCurrencySnapshotCombined: IO[Option[(Hashed[CurrencyIncrementalSnapshot], CurrencySnapshotInfo)]] =
      IO.raiseError(new NotImplementedError)
    def securityProvider: SecurityProvider[IO] = throw new NotImplementedError
    def getCurrencyId: IO[CurrencyId] = IO.raiseError(new NotImplementedError)
    def getMetagraphL0Seedlist: Option[Set[SeedlistEntry]] = None
    def getSnapshotFeeTransactions: IO[Map[Hash, Signed[FeeTransaction]]] = IO.pure(Map.empty)
  }

  def fakeDataApplication(
    observed: Ref[IO, List[Option[SnapshotOrdinal]]],
    deserializedBlock: Signed[DataApplicationBlock] = signedBlockWithUpdate(0),
    observedFeeRefs: Option[Ref[IO, List[Set[Hash]]]] = None
  ): BaseDataApplicationL0Service[IO] =
    new BaseDataApplicationL0Service[IO] {
      override def serializeState(state: DataOnChainState): IO[Array[Byte]] = ???
      override def deserializeState(bytes: Array[Byte]): IO[Either[Throwable, DataOnChainState]] = ???
      override def serializeUpdate(update: DataUpdate): IO[Array[Byte]] = ???
      override def deserializeUpdate(bytes: Array[Byte]): IO[Either[Throwable, DataUpdate]] = ???
      override def serializeBlock(block: Signed[DataApplicationBlock]): IO[Array[Byte]] = ???
      override def deserializeBlock(bytes: Array[Byte]): IO[Either[Throwable, Signed[DataApplicationBlock]]] =
        IO.pure(Right(deserializedBlock))
      override def serializeCalculatedState(state: DataCalculatedState): IO[Array[Byte]] = IO.pure(Array.emptyByteArray)
      override def deserializeCalculatedState(bytes: Array[Byte]): IO[Either[Throwable, DataCalculatedState]] = ???
      override def dataEncoder: io.circe.Encoder[DataUpdate] = ???
      override def dataDecoder: io.circe.Decoder[DataUpdate] = ???
      override def signedDataEntityDecoder: org.http4s.EntityDecoder[IO, Signed[DataUpdate]] = ???
      override def calculatedStateEncoder: io.circe.Encoder[DataCalculatedState] = ???
      override def validateData(state: DataState.Base, updates: cats.data.NonEmptyList[Signed[DataUpdate]])(
        implicit context: L0NodeContext[IO]
      ): IO[DataApplicationValidationErrorOr[Unit]] = ???
      override def combine(state: DataState.Base, updates: List[Signed[DataUpdate]])(
        implicit context: L0NodeContext[IO]
      ): IO[DataState.Base] =
        (context.getLastCurrencySnapshot, context.getSnapshotFeeTransactions).tupled.flatMap {
          case (maybePredecessor, feeTransactions) =>
            observed.update(_ :+ maybePredecessor.map(_.ordinal)) >>
              observedFeeRefs.traverse_(_.update(_ :+ feeTransactions.keySet)).as(state)
        }
      override def getCalculatedState(implicit context: L0NodeContext[IO]): IO[(SnapshotOrdinal, DataCalculatedState)] = ???
      override def setCalculatedState(ordinal: SnapshotOrdinal, state: DataCalculatedState)(
        implicit context: L0NodeContext[IO]
      ): IO[Boolean] = ???
      override def routes(implicit context: L0NodeContext[IO]): org.http4s.HttpRoutes[IO] = ???
      override def routesPrefix: ExternalUrlPrefix = "/data-application"
      override def genesis: DataState.Base = ???
      override def calculatedStateDecoder: io.circe.Decoder[DataCalculatedState] = ???
      override def signedDataEntityEncoder: org.http4s.EntityEncoder[IO, Signed[DataUpdate]] = ???
      override def hashCalculatedState(state: DataCalculatedState)(implicit context: L0NodeContext[IO]): IO[Hash] = ???
      override def getTokenUnlocks(state: DataState.Base)(
        implicit context: L0NodeContext[IO],
        async: cats.effect.Async[IO],
        hasher: Hasher[IO]
      ): IO[SortedSet[io.constellationnetwork.schema.artifact.TokenUnlock]] = ???
      override def onGlobalSnapshotPull(
        snapshot: Hashed[GlobalIncrementalSnapshot],
        context: GlobalSnapshotInfo
      ): IO[Unit] = ???
      override def onSnapshotConsensusResult(snapshot: Hashed[CurrencyIncrementalSnapshot]): IO[Unit] = ???
    }

  test("applyCache threads each replayed ordinal's true predecessor into combine, not the tip") {
    case (hasher, sp, hs, kryo, json) =>
      implicit val h: Hasher[IO] = hasher
      implicit val s: SecurityProvider[IO] = sp
      implicit val hsi: HasherSelector[IO] = hs
      implicit val k: KryoSerializer[IO] = kryo
      implicit val jz: JsonSerializer[IO] = json
      implicit val ctx: L0NodeContext[IO] = fakeTipContext

      for {
        observed <- Ref.of[IO, List[Option[SnapshotOrdinal]]](Nil)
        tempDir <- Files[IO].tempDirectory.allocated.map(_._1)
        calculatedStateStorage <- CalculatedStateLocalFileSystemStorage.make[IO](tempDir)
        traverse = DataApplicationTraverse.make[IO](
          lastGlobalSnapshot = null,
          fetchSnapshot = _ => IO.pure(None),
          dataApplication = fakeDataApplication(observed),
          calculatedStateStorage = calculatedStateStorage,
          globalSnapshotsWithStateLocalFileSystemStorage = null,
          globalSnapshotsWithStateDeltasLocalFileSystemStorage = null,
          identifier = testAddress,
          globalSnapshotContextFunctions = null,
          globalL0Service = null
        )
        startingSnapshot = mkSnapshot(10L, withDataApplication = false)
        result <- TraverseLocalFileSystemTempStorage.forAsync[IO].use { storage =>
          storage.write(ord(11L), mkSnapshot(11L, withDataApplication = true)) >>
            storage.write(ord(12L), mkSnapshot(12L, withDataApplication = true)) >>
            traverse.applyCache(storage, DataState(TestOnChain, TestCalculated, SortedSet.empty), startingSnapshot)
        }
        seen <- observed.get
      } yield expect(seen == List(Some(ord(10L)), Some(ord(11L)))).and(expect(result._2 == ord(12L)))
  }

  test("applyCache rejects a non-contiguous cache (guards the predecessor-threading invariant)") {
    case (hasher, sp, hs, kryo, json) =>
      implicit val h: Hasher[IO] = hasher
      implicit val s: SecurityProvider[IO] = sp
      implicit val hsi: HasherSelector[IO] = hs
      implicit val k: KryoSerializer[IO] = kryo
      implicit val jz: JsonSerializer[IO] = json
      implicit val ctx: L0NodeContext[IO] = fakeTipContext

      for {
        observed <- Ref.of[IO, List[Option[SnapshotOrdinal]]](Nil)
        tempDir <- Files[IO].tempDirectory.allocated.map(_._1)
        calculatedStateStorage <- CalculatedStateLocalFileSystemStorage.make[IO](tempDir)
        traverse = DataApplicationTraverse.make[IO](
          lastGlobalSnapshot = null,
          fetchSnapshot = _ => IO.pure(None),
          dataApplication = fakeDataApplication(observed),
          calculatedStateStorage = calculatedStateStorage,
          globalSnapshotsWithStateLocalFileSystemStorage = null,
          globalSnapshotsWithStateDeltasLocalFileSystemStorage = null,
          identifier = testAddress,
          globalSnapshotContextFunctions = null,
          globalL0Service = null
        )
        startingSnapshot = mkSnapshot(10L, withDataApplication = false)
        outcome <- TraverseLocalFileSystemTempStorage.forAsync[IO].use { storage =>
          storage.write(ord(11L), mkSnapshot(11L, withDataApplication = true)) >>
            storage.write(ord(13L), mkSnapshot(13L, withDataApplication = true)) >>
            traverse.applyCache(storage, DataState(TestOnChain, TestCalculated, SortedSet.empty), startingSnapshot).attempt
        }
      } yield expect(outcome.left.exists(_.isInstanceOf[DataApplicationTraverse.NonContiguousReplayPredecessor]))
  }

  test("applyCache reconstructs the snapshot fee map from the stored accepted blocks") {
    case (hasher, sp, hs, kryo, json) =>
      implicit val h: Hasher[IO] = hasher
      implicit val s: SecurityProvider[IO] = sp
      implicit val hsi: HasherSelector[IO] = hs
      implicit val k: KryoSerializer[IO] = kryo
      implicit val jz: JsonSerializer[IO] = json
      implicit val ctx: L0NodeContext[IO] = fakeTipContext

      val feeRef = Hash.fromBytes(Array(1.toByte))
      val storedBlock = signedBlockWithUpdateAndFee(1, feeRef)

      for {
        observedOrdinals <- Ref.of[IO, List[Option[SnapshotOrdinal]]](Nil)
        observedFeeRefs <- Ref.of[IO, List[Set[Hash]]](Nil)
        tempDir <- Files[IO].tempDirectory.allocated.map(_._1)
        calculatedStateStorage <- CalculatedStateLocalFileSystemStorage.make[IO](tempDir)
        traverse = DataApplicationTraverse.make[IO](
          lastGlobalSnapshot = null,
          fetchSnapshot = _ => IO.pure(None),
          dataApplication = fakeDataApplication(observedOrdinals, storedBlock, observedFeeRefs.some),
          calculatedStateStorage = calculatedStateStorage,
          globalSnapshotsWithStateLocalFileSystemStorage = null,
          globalSnapshotsWithStateDeltasLocalFileSystemStorage = null,
          identifier = testAddress,
          globalSnapshotContextFunctions = null,
          globalL0Service = null
        )
        startingSnapshot = mkSnapshot(10L, withDataApplication = false)
        _ <- TraverseLocalFileSystemTempStorage.forAsync[IO].use { storage =>
          storage.write(ord(11L), mkSnapshot(11L, withDataApplication = true)) >>
            traverse.applyCache(storage, DataState(TestOnChain, TestCalculated, SortedSet.empty), startingSnapshot)
        }
        seen <- observedFeeRefs.get
      } yield expect.same(List(Set(feeRef)), seen)
  }
}
