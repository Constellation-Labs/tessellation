package io.constellationnetwork.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.Parallel
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{Async, IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.statechannel._
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.consensus.CurrencySnapshotEventValidationErrorStorage
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.modules.SharedValidators
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.node.RewardFraction
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.concurrent.SignallingRef
import weaver.MutableIOSuite
object GlobalSnapshotStateChannelEventsProcessorSuite extends MutableIOSuite {
  val TestValidationErrorStorageMaxSize: PosInt = PosInt(16)

  type Res = (KryoSerializer[IO], Hasher[IO], JsonSerializer[IO], SecurityProvider[IO], JsonBrotliBinarySerializer[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
    serializer <- JsonBrotliBinarySerializer.forSync[IO].asResource
  } yield (ks, h, j, sp, serializer)

  def mkProcessor(
    stateChannelAllowanceLists: Map[Address, NonEmptySet[PeerId]],
    failed: Option[(Address, StateChannelValidator.StateChannelValidationError)] = None
  )(implicit H: Hasher[IO], S: SecurityProvider[IO], J: JsonSerializer[IO], K: KryoSerializer[IO]) = {
    implicit val hs = HasherSelector.forSyncAlwaysCurrent(H)

    for {
      _ <- IO.unit
      validator = new StateChannelValidator[IO] {
        def validate(
          output: StateChannelOutput,
          globalOrdinal: SnapshotOrdinal,
          snapshotFeesInfo: SnapshotFeesInfo
        )(implicit hasher: Hasher[IO]) =
          IO.pure(failed.filter(f => f._1 == output.address).map(_._2.invalidNec).getOrElse(output.validNec))
        def validateHistorical(output: StateChannelOutput, globalOrdinal: SnapshotOrdinal, snapshotFeesInfo: SnapshotFeesInfo)(
          implicit hasher: Hasher[IO]
        ) =
          validate(output, globalOrdinal, snapshotFeesInfo)(hasher)
      }

      validators = SharedValidators
        .make[IO](
          AddressesConfig(Set()),
          None,
          None,
          Some(stateChannelAllowanceLists),
          SortedMap.empty,
          Long.MaxValue,
          Hasher.forKryo[IO],
          DelegatedStakingConfig(
            RewardFraction(5_000_000),
            RewardFraction(10_000_000),
            PosInt(140),
            PosInt(10),
            PosLong((5000 * 1e8).toLong),
            Map(Dev -> EpochProgress(NonNegLong(7338977L)))
          ),
          PriceOracleConfig(None, NonNegLong(0))
        )
      lastNSnapR <- SignallingRef
        .of[IO, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](SortedMap.empty)
      incLastNSnapR <- SignallingRef
        .of[IO, SortedMap[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]](SortedMap.empty)
      lastSnapR <- SignallingRef.of[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](None)

      lastGlobalSnapshotsSyncConfig =
        LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt.unsafeFrom(10), PosInt.unsafeFrom(5))
      lastNSnapshotStorage =
        LastNGlobalSnapshotStorage.make[IO](lastGlobalSnapshotsSyncConfig, lastNSnapR, incLastNSnapR)
      lastGlobalSnapshotStorage = LastSnapshotStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](lastSnapR)

      currencySnapshotAcceptanceManager <- CurrencySnapshotAcceptanceManager.make(
        FieldsAddedOrdinals(Map.empty, Map.empty, Map.empty, Map.empty),
        Dev,
        LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(20), PosInt(10)),
        BlockAcceptanceManager.make[IO](validators.currencyBlockValidator, Hasher.forKryo[IO]),
        TokenLockBlockAcceptanceManager.make[IO](validators.tokenLockBlockValidator),
        AllowSpendBlockAcceptanceManager.make[IO](validators.allowSpendBlockValidator),
        Amount(0L),
        validators.currencyMessageValidator,
        validators.feeTransactionValidator,
        validators.globalSnapshotSyncValidator,
        lastNSnapshotStorage,
        lastGlobalSnapshotStorage
      )
      currencyEventsCutter = CurrencyEventsCutter.make[IO](None)
      validationErrorStorage <- CurrencySnapshotEventValidationErrorStorage.make(TestValidationErrorStorageMaxSize)
      creator = CurrencySnapshotCreator
        .make[IO](
          SnapshotOrdinal.MinValue,
          currencySnapshotAcceptanceManager,
          None,
          SnapshotSizeConfig(Long.MaxValue, Long.MaxValue),
          currencyEventsCutter,
          validationErrorStorage
        )
      currencySnapshotValidator = CurrencySnapshotValidator
        .make[IO](SnapshotOrdinal.MinValue, creator, validators.signedValidator, None, None)
      currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(currencySnapshotValidator)
      manager = new GlobalSnapshotStateChannelAcceptanceManager[IO] {
        def accept(ordinal: SnapshotOrdinal, lastGlobalSnapshotInfo: GlobalSnapshotInfo, events: List[StateChannelOutput])(
          implicit hasher: Hasher[IO]
        ): IO[
          (
            SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
            Set[StateChannelOutput]
          )
        ] = IO.pure((events.groupByNel(_.address).map { case (k, v) => k -> v.map(_.snapshotBinary) }, Set.empty))
      }
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync
      feeCalculator = FeeCalculator.make(SortedMap.empty)
      processor = GlobalSnapshotStateChannelEventsProcessor
        .make[IO](validator, manager, currencySnapshotContextFns, jsonBrotliBinarySerializer, feeCalculator)
    } yield processor
  }

  test("return new sc event") { res =>
    implicit val (ks, h, j, sp, serializer) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output <- mkStateChannelOutput(keyPair, serializer = serializer)
      snapshotInfo = mkGlobalSnapshotInfo()
      snapshot <- mkGlobalIncrementalSnapshot[IO](snapshotInfo)
      service <- mkProcessor(Map(address -> output.snapshotBinary.proofs.map(_.id.toPeerId)))
      expected = StateChannelAcceptanceResult(
        SortedMap((address, NonEmptyList.one(output.snapshotBinary))),
        SortedMap.empty[Address, StateChannelAcceptanceResult.CurrencySnapshotWithState],
        Set.empty,
        Map.empty,
        SortedMap.empty[Address, List[StateChannelAcceptanceResult.CurrencySnapshotWithState]]
      )
      result <- service.process(
        SnapshotOrdinal(1L),
        snapshotInfo,
        output :: Nil,
        StateChannelValidationType.Full,
        _ => None.pure[IO]
      )
    } yield expect.eql(expected, result)

  }

  test("return sc events for different addresses") { res =>
    implicit val (ks, h, j, sp, serializer) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair1.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair1, serializer = serializer)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic().toAddress
      output2 <- mkStateChannelOutput(keyPair2, serializer = serializer)
      snapshotInfo = mkGlobalSnapshotInfo()
      snapshot <- mkGlobalIncrementalSnapshot[IO](snapshotInfo)
      service <- mkProcessor(
        Map(address1 -> output1.snapshotBinary.proofs.map(_.id.toPeerId), address2 -> output2.snapshotBinary.proofs.map(_.id.toPeerId))
      )
      expected = StateChannelAcceptanceResult(
        SortedMap((address1, NonEmptyList.of(output1.snapshotBinary)), (address2, NonEmptyList.of(output2.snapshotBinary))),
        SortedMap.empty[Address, StateChannelAcceptanceResult.CurrencySnapshotWithState],
        Set.empty,
        Map.empty,
        SortedMap.empty[Address, List[StateChannelAcceptanceResult.CurrencySnapshotWithState]]
      )
      result <- service.process(
        SnapshotOrdinal(1L),
        snapshotInfo,
        output1 :: output2 :: Nil,
        StateChannelValidationType.Full,
        _ => None.pure[IO]
      )
    } yield expect.eql(expected, result)

  }

  test("return only valid sc events") { res =>
    implicit val (ks, h, j, sp, serializer) = res

    for {
      keyPair1 <- KeyPairGenerator.makeKeyPair[IO]
      address1 = keyPair1.getPublic().toAddress
      output1 <- mkStateChannelOutput(keyPair1, serializer = serializer)
      keyPair2 <- KeyPairGenerator.makeKeyPair[IO]
      address2 = keyPair2.getPublic().toAddress
      output2 <- mkStateChannelOutput(keyPair2, serializer = serializer)
      snapshotInfo = mkGlobalSnapshotInfo()
      snapshot <- mkGlobalIncrementalSnapshot[IO](snapshotInfo)
      service <- mkProcessor(
        Map(address1 -> output1.snapshotBinary.proofs.map(_.id.toPeerId), address2 -> output2.snapshotBinary.proofs.map(_.id.toPeerId)),
        Some(address1 -> StateChannelValidator.NotSignedExclusivelyByStateChannelOwner)
      )
      expected = StateChannelAcceptanceResult(
        SortedMap((address2, NonEmptyList.of(output2.snapshotBinary))),
        SortedMap.empty[Address, StateChannelAcceptanceResult.CurrencySnapshotWithState],
        Set.empty,
        Map.empty,
        SortedMap.empty[Address, List[StateChannelAcceptanceResult.CurrencySnapshotWithState]]
      )
      result <- service.process(
        SnapshotOrdinal(1L),
        snapshotInfo,
        output1 :: output2 :: Nil,
        StateChannelValidationType.Full,
        _ => None.pure[IO]
      )
    } yield expect.eql(expected, result)

  }

  def mkStateChannelOutput(keyPair: KeyPair, hash: Option[Hash] = None, serializer: JsonBrotliBinarySerializer[IO])(
    implicit S: SecurityProvider[IO],
    H: Hasher[IO]
  ) = for {
    content <- Random.scalaUtilRandom[IO].flatMap(_.nextString(10))
    compressedBytes <- serializer.serialize(content)
    binary <- StateChannelSnapshotBinary(hash.getOrElse(Hash.empty), compressedBytes, SnapshotFee.MinValue).pure[IO]
    signedSC <- forAsyncHasher(binary, keyPair)
  } yield StateChannelOutput(keyPair.getPublic.toAddress, signedSC)

  def mkGlobalIncrementalSnapshot[F[_]: Parallel: Async: Hasher](
    globalSnapshotInfo: GlobalSnapshotInfo
  ): F[Hashed[GlobalIncrementalSnapshot]] =
    globalSnapshotInfo.stateProof[F](SnapshotOrdinal(NonNegLong(1L))).flatMap { sp =>
      Signed(
        GlobalIncrementalSnapshot(
          SnapshotOrdinal(NonNegLong(1L)),
          Height.MinValue,
          SubHeight.MinValue,
          Hash.empty,
          SortedSet.empty,
          SortedMap.empty,
          SortedSet.empty,
          None,
          EpochProgress.MinValue,
          NonEmptyList.of(PeerId(Hex(""))),
          SnapshotTips(SortedSet.empty, SortedSet.empty),
          stateProof = sp,
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
        NonEmptySet.fromSetUnsafe(SortedSet(SignatureProof(ID.Id(Hex("")), Signature(Hex("")))))
      ).toHashed[F]
    }

  def mkGlobalSnapshotInfo(lastStateChannelSnapshotHashes: SortedMap[Address, Hash] = SortedMap.empty) =
    GlobalSnapshotInfo(
      lastStateChannelSnapshotHashes,
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

}
