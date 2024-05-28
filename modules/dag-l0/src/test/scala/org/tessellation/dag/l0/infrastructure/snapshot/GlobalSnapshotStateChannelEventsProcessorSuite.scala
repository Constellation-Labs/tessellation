package org.tessellation.dag.l0.infrastructure.snapshot

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.applicative._
import cats.syntax.list._
import cats.syntax.validated._

import scala.collection.immutable.SortedMap

import org.tessellation.currency.schema.currency._
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.json.{JsonBrotliBinarySerializer, JsonSerializer}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared.config.types.SnapshotSizeConfig
import org.tessellation.node.shared.domain.statechannel.StateChannelValidator
import org.tessellation.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import org.tessellation.node.shared.infrastructure.snapshot._
import org.tessellation.node.shared.modules.SharedValidators
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.{Amount, Balance}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.security._
import org.tessellation.security.hash.Hash
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncHasher
import org.tessellation.shared.sharedKryoRegistrar
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite
object GlobalSnapshotStateChannelEventsProcessorSuite extends MutableIOSuite {

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
        def validate(output: StateChannelOutput, globalOrdinal: SnapshotOrdinal, staked: Balance)(implicit hasher: Hasher[IO]) =
          IO.pure(failed.filter(f => f._1 == output.address).map(_._2.invalidNec).getOrElse(output.validNec))
        def validateHistorical(output: StateChannelOutput, globalOrdinal: SnapshotOrdinal, staked: Balance)(implicit hasher: Hasher[IO]) =
          validate(output, globalOrdinal, staked)(hasher)
      }
      validators = SharedValidators
        .make[IO](None, None, Some(stateChannelAllowanceLists), SortedMap.empty, Long.MaxValue, Hasher.forKryo[IO])
      currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
        BlockAcceptanceManager.make[IO](validators.currencyBlockValidator, Hasher.forKryo[IO]),
        Amount(0L),
        validators.currencyMessageValidator
      )
      currencyEventsCutter = CurrencyEventsCutter.make[IO](None)
      creator = CurrencySnapshotCreator
        .make[IO](currencySnapshotAcceptanceManager, None, SnapshotSizeConfig(Long.MaxValue, Long.MaxValue), currencyEventsCutter)
      currencySnapshotValidator = CurrencySnapshotValidator.make[IO](creator, validators.signedValidator, None, None)
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
      processor = GlobalSnapshotStateChannelEventsProcessor
        .make[IO](validator, manager, currencySnapshotContextFns, jsonBrotliBinarySerializer)
    } yield processor
  }

  test("return new sc event") { res =>
    implicit val (ks, h, j, sp, serializer) = res

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic().toAddress
      output <- mkStateChannelOutput(keyPair, serializer = serializer)
      snapshotInfo = mkGlobalSnapshotInfo()
      service <- mkProcessor(Map(address -> output.snapshotBinary.proofs.map(_.id.toPeerId)))
      expected = (
        SortedMap((address, NonEmptyList.one(output.snapshotBinary))),
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(SnapshotOrdinal(1L), snapshotInfo, output :: Nil, StateChannelValidationType.Full)
    } yield expect.same(expected, result)

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
      service <- mkProcessor(
        Map(address1 -> output1.snapshotBinary.proofs.map(_.id.toPeerId), address2 -> output2.snapshotBinary.proofs.map(_.id.toPeerId))
      )
      expected = (
        SortedMap((address1, NonEmptyList.of(output1.snapshotBinary)), (address2, NonEmptyList.of(output2.snapshotBinary))),
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(SnapshotOrdinal(1L), snapshotInfo, output1 :: output2 :: Nil, StateChannelValidationType.Full)
    } yield expect.same(expected, result)

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
      service <- mkProcessor(
        Map(address1 -> output1.snapshotBinary.proofs.map(_.id.toPeerId), address2 -> output2.snapshotBinary.proofs.map(_.id.toPeerId)),
        Some(address1 -> StateChannelValidator.NotSignedExclusivelyByStateChannelOwner)
      )
      expected = (
        SortedMap((address2, NonEmptyList.of(output2.snapshotBinary))),
        SortedMap.empty[Address, (Option[Signed[CurrencyIncrementalSnapshot]], CurrencySnapshotInfo)],
        Set.empty
      )
      result <- service.process(SnapshotOrdinal(1L), snapshotInfo, output1 :: output2 :: Nil, StateChannelValidationType.Full)
    } yield expect.same(expected, result)

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

  def mkGlobalSnapshotInfo(lastStateChannelSnapshotHashes: SortedMap[Address, Hash] = SortedMap.empty) =
    GlobalSnapshotInfo(lastStateChannelSnapshotHashes, SortedMap.empty, SortedMap.empty, SortedMap.empty, SortedMap.empty)

}
