package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import java.security.KeyPair

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.FieldsAddedOrdinalsFixtures
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.statechannel.{FeeCalculator, FeeCalculatorConfig}
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.consensus.CurrencySnapshotEventValidationErrorStorage
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.TimeTrigger
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.CurrencySnapshotAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.modules.SharedValidators
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.currencyMessage._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.node.RewardFraction
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary, StateChannelValidationType}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt, PosLong}
import fs2.concurrent.SignallingRef
import io.circe.Json
import io.circe.syntax._
import weaver.MutableIOSuite

/** Real Currency producer, signature validator, context reconstruction, state-channel processor and Global acceptance manager. Unrelated
  * Global block/stake services use the existing empty fixture; no burn, Currency reconstruction, state proof or state-channel validation is
  * mocked.
  */
object BurnSnapshotAcceptanceSuite extends MutableIOSuite {
  type Res = (KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])
  implicit val currencyProof: CurrencyStateProofSelector = CurrencyStateProofSelector.instance
  implicit val globalProof: GlobalStateProofSelector = GlobalStateProofSelector(SnapshotOrdinal.MaxValue)

  def sharedResource: Resource[IO, Res] = for {
    k <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    s <- SecurityProvider.forAsync[IO]
  } yield (k, j, Hasher.forJson[IO], s)

  case class Fixture(
    key: KeyPair,
    owner: Address,
    genesis: Signed[CurrencySnapshot],
    parent: Signed[CurrencyIncrementalSnapshot],
    context: CurrencySnapshotContext,
    info: GlobalSnapshotInfo,
    recent: List[Hashed[GlobalIncrementalSnapshot]],
    fields: FieldsAddedOrdinals
  )
  case class Services(
    creator: CurrencySnapshotCreator[IO],
    validator: CurrencySnapshotValidator[IO],
    processor: GlobalSnapshotStateChannelEventsProcessor[IO],
    spendValidator: io.constellationnetwork.node.shared.domain.swap.SpendActionValidator[IO]
  )

  private val receiver = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWC")
  private val binaryParent = Hash("1" * 64)
  private def ordinal(n: Long): SnapshotOrdinal = SnapshotOrdinal.unsafeApply(n)
  private def burn(owner: Address, amounts: Long*): BurnAction = BurnAction(
    NonEmptyList.fromListUnsafe(
      amounts.toList.map(n => BurnTransaction(CurrencyId(owner), SwapAmount(PosLong.unsafeFrom(n)), owner))
    )
  )

  private def fixture(
    activation: Map[io.constellationnetwork.env.AppEnvironment, SnapshotOrdinal] = Map(Dev -> SnapshotOrdinal.MinValue),
    spend: Long = 0L,
    lagging: Boolean = false,
    parentHasGlobalView: Boolean = true
  )(implicit j: JsonSerializer[IO], h: Hasher[IO], s: SecurityProvider[IO]): IO[Fixture] = for {
    key <- KeyPairGenerator.makeKeyPair[IO]
    owner = key.getPublic.toAddress
    globalGenesis <- Signed
      .forAsyncHasher[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), key)
      .flatMap(_.toHashed)
    globalBase <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](globalGenesis)(implicitly, implicitly, h, j, globalProof)
    g19 <- Signed.forAsyncHasher(globalBase.copy(ordinal = ordinal(19)), key).flatMap(_.toHashed)
    full <- Signed.forAsyncHasher(
      CurrencySnapshot
        .mkGenesis(Map(owner -> Balance(100L)), None, if (parentHasGlobalView && (spend == 0L || lagging)) Some(g19) else None),
      key
    )
    hashedFull <- full.toHashed
    first <- CurrencySnapshot.mkFirstIncrementalSnapshot[IO](hashedFull).flatMap(Signed.forAsyncHasher(_, key))
    currencyInfo = full.info.toCurrencySnapshotInfo
    globalInfo = globalGenesis.info.toGlobalSnapshotInfo.copy(
      lastCurrencySnapshots = SortedMap(owner -> Right((first, currencyInfo))),
      lastStateChannelSnapshotHashes = SortedMap(owner -> binaryParent),
      metagraphSyncData = Some(
        SortedMap(
          owner -> snapshot.MetagraphSyncDataInfo(
            ordinal(19),
            EpochProgress.MinValue,
            if (spend > 0L) SortedSet(ordinal(20)) else SortedSet.empty
          )
        )
      )
    )
    proof <- globalInfo.stateProof[IO](ordinal(20))(implicitly, implicitly, h, j, globalProof)
    spends =
      if (spend == 0L) SortedMap.empty[Address, List[SpendAction]]
      else
        SortedMap(
          owner -> List(
            SpendAction(
              NonEmptyList.one(
                SpendTransaction(None, Some(CurrencyId(owner)), SwapAmount(PosLong.unsafeFrom(spend)), owner, receiver)
              )
            )
          )
        )
    g20 <- Signed
      .forAsyncHasher(
        globalBase.copy(ordinal = ordinal(20), lastSnapshotHash = g19.hash, stateProof = proof, spendActions = Some(spends)),
        key
      )
      .flatMap(_.toHashed)
  } yield
    Fixture(
      key,
      owner,
      full,
      first,
      CurrencySnapshotContext(owner, currencyInfo),
      globalInfo,
      List(g19, g20),
      FieldsAddedOrdinalsFixtures.current.copy(burnActionActivation = activation)
    )

  private def services(
    f: Fixture,
    requireFee: Boolean = false,
    other: Option[Fixture] = None
  )(implicit k: KryoSerializer[IO], j: JsonSerializer[IO], h: Hasher[IO], s: SecurityProvider[IO]): IO[Services] = {
    implicit val selector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(h)
    val feeConfigs: SortedMap[SnapshotOrdinal, FeeCalculatorConfig] =
      if (requireFee) SortedMap(SnapshotOrdinal.MinValue -> FeeCalculatorConfig.noFee.copy(baseFee = 1L)) else SortedMap.empty
    val validators = SharedValidators.make[IO](
      Dev,
      AddressesConfig(Set.empty),
      None,
      None,
      Some((List(f) ++ other.toList).map(x => x.owner -> x.parent.proofs.toNonEmptyList.map(_.id.toPeerId).toNes).toMap),
      feeConfigs,
      Long.MaxValue,
      Hasher.forKryo[IO],
      DelegatedStakingConfig(
        RewardFraction(5_000_000),
        RewardFraction(10_000_000),
        PosInt(140),
        PosInt(10),
        PosLong(500000000000L),
        Map(Dev -> EpochProgress(NonNegLong(7338977L)))
      ),
      PriceOracleConfig(None, NonNegLong(0L))
    )
    val sync = LastGlobalSnapshotsSyncConfig(NonNegLong(0L), PosInt(10))
    for {
      latest <- SignallingRef.of[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](Some(f.recent.last -> f.info))
      base <- SignallingRef.of[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](Some(f.recent.last -> f.info))
      recent <- SignallingRef.of[IO, SortedMap[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]](
        SortedMap.from(f.recent.map(g => g.ordinal -> g))
      )
      manager <- CurrencySnapshotAcceptanceManager.make[IO](
        f.fields,
        Dev,
        sync,
        BlockAcceptanceManager.make[IO](validators.currencyBlockValidator, Hasher.forKryo[IO]),
        TokenLockBlockAcceptanceManager.make[IO](validators.tokenLockBlockValidator),
        AllowSpendBlockAcceptanceManager.make[IO](validators.allowSpendBlockValidator),
        Amount.empty,
        validators.currencyMessageValidator,
        validators.feeTransactionValidator,
        validators.globalSnapshotSyncValidator,
        LastNGlobalSnapshotStorage.make[IO](sync, base, recent),
        LastSnapshotStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](latest)
      )
      errors <- CurrencySnapshotEventValidationErrorStorage.make[IO](PosInt(16))
      creator = CurrencySnapshotCreator
        .make[IO](SnapshotOrdinal.MinValue, manager, None, SnapshotSizeConfig(512L, 1000000L), CurrencyEventsCutter.make[IO](None), errors)
      validator = CurrencySnapshotValidator.make[IO](creator, validators.signedValidator, None, None, SnapshotOrdinal.MinValue)
      producer <- InMemoryMerklePatriciaProducer.make[IO]()
      mpt <- MptStore.make[IO, GlobalStateKey](producer, GlobalStateKey.toHex[IO])
      stateChannels <- GlobalSnapshotStateChannelAcceptanceManager.make[IO](None)
      processor = GlobalSnapshotStateChannelEventsProcessor.make[IO](
        validators.stateChannelValidator,
        stateChannels,
        CurrencySnapshotContextFunctions.make[IO](validator),
        FeeCalculator.make[IO](feeConfigs),
        mpt,
        f.fields,
        Dev
      )
    } yield Services(creator, validator, processor, validators.spendActionValidator)
  }

  private def create(f: Fixture, services: Services, actions: SortedSet[SharedArtifact], messages: List[Signed[CurrencyMessage]] = Nil)(
    implicit h: Hasher[IO]
  ): IO[CurrencySnapshotCreationResult[io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent]] =
    services.creator.createProposalArtifact(
      f.parent.ordinal,
      f.parent,
      f.context,
      h,
      TimeTrigger,
      messages
        .map(m =>
          io.constellationnetwork.node.shared.snapshot.currency
            .CurrencyMessageEvent(m): io.constellationnetwork.node.shared.snapshot.currency.CurrencySnapshotEvent
        )
        .toSet,
      None,
      f.parent.proofs.toNonEmptyList.toList.map(_.id.toPeerId).toSet,
      None,
      Some(() => actions),
      n => f.recent.find(_.ordinal == n).pure[IO],
      false,
      None
    )

  private def event(
    f: Fixture,
    snapshot: CurrencyIncrementalSnapshot,
    parentHash: Hash = binaryParent
  )(implicit j: JsonSerializer[IO], h: Hasher[IO], s: SecurityProvider[IO]): IO[StateChannelOutput] = for {
    signed <- Signed.forAsyncHasher(snapshot, f.key)
    bytes <- j.serialize(signed)
    binary <- Signed.forAsyncHasher(StateChannelSnapshotBinary(parentHash, bytes, SnapshotFee.MinValue), f.key)
  } yield StateChannelOutput(f.owner, binary)

  private def acceptGlobal(
    f: Fixture,
    services: Services,
    info: GlobalSnapshotInfo,
    events: List[StateChannelOutput],
    globalOrdinal: Long = 21L,
    validationType: StateChannelValidationType = StateChannelValidationType.Full
  )(implicit h: Hasher[IO], s: SecurityProvider[IO]): IO[GlobalSnapshotInfo] = for {
    manager <- Mocks.mkManager(Some(info), stateChannelProcessor = Some(services.processor), spendValidator = Some(services.spendValidator))
    result <- manager.accept(
      ordinal(globalOrdinal),
      EpochProgress.MinValue,
      Nil,
      Nil,
      Nil,
      events,
      Nil,
      Nil,
      Nil,
      Nil,
      Nil,
      info,
      SortedSet.empty,
      SortedSet.empty,
      Mocks.delegatedRewardsFunction[IO](info),
      validationType,
      n => f.recent.find(_.ordinal == n).pure[IO],
      AllowSpendBlockAcceptanceMode.live
    )
  } yield result._9

  List(18L -> false, 19L -> true, 20L -> true).foreach {
    case (parent, active) =>
      test(s"Currency/Global activation A-1/A/A+1 at parent $parent and exact state proof") { res =>
        implicit val (k, j, h, s) = res
        for {
          f0 <- fixture(Map(Dev -> ordinal(19)))
          signedParent <- Signed.forAsyncHasher(
            f0.parent.value.copy(globalSyncView = f0.parent.globalSyncView.map(_.copy(ordinal = ordinal(parent)))),
            f0.key
          )
          // The parent's reference must name a retained Global snapshot, not an invented ordinal.
          reference <- Signed.forAsyncHasher(f0.recent.head.signed.value.copy(ordinal = ordinal(parent)), f0.key).flatMap(_.toHashed)
          boundParent <- Signed.forAsyncHasher(
            signedParent.value.copy(globalSyncView = signedParent.globalSyncView.map(_.copy(hash = reference.hash))),
            f0.key
          )
          f = f0.copy(
            parent = boundParent,
            recent = List(reference, f0.recent.last),
            info = f0.info.copy(lastCurrencySnapshots = SortedMap(f0.owner -> Right((boundParent, f0.context.snapshotInfo))))
          )
          svc <- services(f)
          created <- create(f, svc, SortedSet(burn(f.owner, 100L)))
          output <- event(f, created.artifact)
          global <- acceptGlobal(f, svc, f.info, List(output))
          accepted = global.lastCurrencySnapshots(f.owner).toOption.get
          expectedProof <- accepted._2.stateProof[IO](accepted._1.ordinal)(implicitly, implicitly, h, j, currencyProof)
        } yield
          expect.same(if (active) Balance.empty else Balance(100L), created.context.snapshotInfo.balances(f.owner)) &&
            expect.same(created.context.snapshotInfo, accepted._2) && expect.same(expectedProof, accepted._1.stateProof) &&
            expect.same(f.info.balances, global.balances)
      }
  }

  test("missing activation does not break ordinary snapshots; disabled burns have no effect") { res =>
    implicit val (k, j, h, s) = res
    for {
      f <- fixture(Map.empty)
      svc <- services(f)
      empty <- create(f, svc, SortedSet.empty)
      rejected <- create(f, svc, SortedSet(burn(f.owner, 100L)))
      output <- event(f, rejected.artifact)
      global <- acceptGlobal(f, svc, f.info, List(output))
    } yield
      expect.same(empty, rejected) && expect.same(Balance(100L), global.lastCurrencySnapshots(f.owner).toOption.get._2.balances(f.owner))
  }

  List(0L, 60L, 100L).foreach { spend =>
    test(s"spend $spend then burn 100 rejects cumulatively without throwing or changing the spend") { res =>
      implicit val (k, j, h, s) = res
      for {
        f <- fixture(spend = spend)
        svc <- services(f)
        created <- create(f, svc, SortedSet(burn(f.owner, 100L)))
        output <- event(f, created.artifact)
        global <- acceptGlobal(f, svc, f.info, List(output))
        c = global.lastCurrencySnapshots(f.owner).toOption.get._2
      } yield
        expect.same(if (spend == 0L) Balance.empty else Balance(NonNegLong.unsafeFrom(100L - spend)), c.balances(f.owner)) &&
          expect.same(spend > 0L, c.balances.get(receiver).exists(_.value.value == spend)) &&
          expect.same(created.context.snapshotInfo, c)
    }
  }

  List("insufficient", "holder", "currency", "missing-debit").foreach { attack =>
    test(s"Global acceptance rejects a cryptographically signed $attack burn claim (including an unchanged-state claim)") { res =>
      implicit val (k, j, h, s) = res
      for {
        f <- fixture()
        svc <- services(f)
        clean <- create(f, svc, SortedSet.empty)
        tx = burn(f.owner, if (attack == "insufficient") 101L else 1L).burnTransactions.head
        bad = attack match {
          case "holder"   => tx.copy(source = receiver)
          case "currency" => tx.copy(currencyId = CurrencyId(receiver))
          case _          => tx
        }
        forged = clean.artifact.copy(artifacts =
          Some(clean.artifact.artifacts.getOrElse(SortedSet.empty[SharedArtifact]) + BurnAction(NonEmptyList.one(bad)))
        )
        output <- event(f, forged)
        result <- acceptGlobal(f, svc, f.info, List(output))
      } yield
        expect.same(f.info.lastCurrencySnapshots, result.lastCurrencySnapshots) &&
          expect.same(f.info.lastStateChannelSnapshotHashes, result.lastStateChannelSnapshotHashes) &&
          expect.same(f.info.balances, result.balances)
    }
  }

  // Replay matrix: missing/future activation x full/incremental parent x Full/Historical.
  // Extend the same production acceptance path to explicit MaxValue, A-1/A/A+1 and valid
  // newly decodable payloads. The opaque control follows upstream's unchanged fallback;
  // substitute only the binary hash, since opaque content has no Currency interpretation.
  List("missing", "max", "future", "A-1", "A", "A+1").foreach { gate =>
    List(false, true).foreach { firstIncremental =>
      List(StateChannelValidationType.Full, StateChannelValidationType.Historical).foreach { validationType =>
        List("malformed", "valid").foreach { payloadType =>
          test(s"F1 replay/hash compatibility: $gate, genesis=$firstIncremental, $validationType, $payloadType") { res =>
            implicit val (k, j, h, s) = res
            val activation = gate match {
              case "missing" => Map.empty[io.constellationnetwork.env.AppEnvironment, SnapshotOrdinal]
              case "max"     => Map[io.constellationnetwork.env.AppEnvironment, SnapshotOrdinal](Dev -> SnapshotOrdinal.MaxValue)
              case "future"  => Map[io.constellationnetwork.env.AppEnvironment, SnapshotOrdinal](Dev -> ordinal(1000))
              case "A-1"     => Map[io.constellationnetwork.env.AppEnvironment, SnapshotOrdinal](Dev -> ordinal(20))
              case "A"       => Map[io.constellationnetwork.env.AppEnvironment, SnapshotOrdinal](Dev -> ordinal(19))
              case _         => Map[io.constellationnetwork.env.AppEnvironment, SnapshotOrdinal](Dev -> ordinal(18))
            }
            val active = gate == "A" || gate == "A+1"
            for {
              f <- fixture(activation)
              svc <- services(f)
              clean <- create(f, svc, SortedSet.empty)
              // Incoming claims must not activate recognition; only the accepted parent may.
              forged = (if (firstIncremental) f.parent.value else clean.artifact).copy(
                artifacts = Some(SortedSet[SharedArtifact](burn(f.owner, 1L))),
                globalSyncView = f.parent.globalSyncView.map(_.copy(ordinal = ordinal(1001)))
              )
              signed <- Signed.forAsyncHasher(forged, f.key)
              payload =
                if (payloadType == "valid") signed.asJson
                else
                  Json.obj(
                    "value" -> Json.obj("artifacts" -> Json.arr(Json.obj("BurnAction" -> Json.obj("opaqueLegacyMetadata" -> Json.True))))
                  )
              bytes <- j.serialize(payload)
              decoded <- j.deserialize[Signed[CurrencyIncrementalSnapshot]](bytes)
              binary <- Signed.forAsyncHasher(StateChannelSnapshotBinary(binaryParent, bytes, SnapshotFee.MinValue), f.key)
              hashed <- binary.toHashed
              initial = if (firstIncremental) f.info.copy(lastCurrencySnapshots = SortedMap(f.owner -> Left(f.genesis))) else f.info
              opaqueBytes <- j.serialize(Json.obj("legacyOpaque" -> Json.True))
              opaque <- Signed.forAsyncHasher(StateChannelSnapshotBinary(binaryParent, opaqueBytes, SnapshotFee.MinValue), f.key)
              control <- acceptGlobal(
                f,
                svc,
                initial,
                if (active) Nil else List(StateChannelOutput(f.owner, opaque)),
                validationType = validationType
              )
              expected =
                if (active) control
                else control.copy(lastStateChannelSnapshotHashes = control.lastStateChannelSnapshotHashes.updated(f.owner, hashed.hash))
              result <- acceptGlobal(f, svc, initial, List(StateChannelOutput(f.owner, binary)), validationType = validationType)
              proof <- result.stateProof[IO](ordinal(21))(implicitly, implicitly, h, j, globalProof)
              expectedProof <- expected.stateProof[IO](ordinal(21))(implicitly, implicitly, h, j, globalProof)
              mptProof <- result
                .stateProof[IO](ordinal(21))(implicitly, implicitly, h, j, GlobalStateProofSelector(SnapshotOrdinal.MinValue))
              expectedMptProof <- expected
                .stateProof[IO](ordinal(21))(implicitly, implicitly, h, j, GlobalStateProofSelector(SnapshotOrdinal.MinValue))
              retained <- j.serialize(initial)
              recovered <- j.deserialize[GlobalSnapshotInfo](retained).flatMap(IO.fromEither)
              fresh <- services(f)
              replay <- acceptGlobal(f, fresh, recovered, List(StateChannelOutput(f.owner, binary)), validationType = validationType)
            } yield
              expect.same(payloadType == "valid", decoded.isRight) &&
                expect.same(initial.lastCurrencySnapshots, result.lastCurrencySnapshots) &&
                expect.same(initial.balances, result.balances) && expect.same(expected, result) &&
                expect.same(expectedProof, proof) && expect.same(expectedMptProof, mptProof) && expect.same(result, replay)
          }
        }
      }
    }
  }

  List(StateChannelValidationType.Full, StateChannelValidationType.Historical).foreach { validationType =>
    test(s"F1 disabled valid burn does not expose previously opaque owner/staking metadata ($validationType)") { res =>
      implicit val (k, j, h, s) = res
      for {
        f <- fixture(Map.empty)
        svc <- services(f)
        clean <- create(f, svc, SortedSet.empty)
        owner <- Signed.forAsyncHasher(CurrencyMessage(MessageType.Owner, receiver, f.owner, MessageOrdinal.MinValue), f.key)
        staking <- Signed.forAsyncHasher(CurrencyMessage(MessageType.Staking, receiver, f.owner, MessageOrdinal.MinValue), f.key)
        occupied = f.context.snapshotInfo.copy(lastMessages = Some(SortedMap(MessageType.Owner -> owner)))
        initial = f.info.copy(lastCurrencySnapshots = f.info.lastCurrencySnapshots.updated(receiver, Right(f.parent -> occupied)))
        forged = clean.artifact.copy(
          artifacts = Some(SortedSet[SharedArtifact](burn(f.owner, 1L))),
          messages = Some(SortedSet(owner, staking))
        )
        output <- event(f, forged)
        hashed <- output.snapshotBinary.toHashed
        result <- acceptGlobal(f, svc, initial, List(output), validationType = validationType)
      } yield
        expect.same(hashed.hash, result.lastStateChannelSnapshotHashes(f.owner)) &&
          expect.same(initial.lastCurrencySnapshots, result.lastCurrencySnapshots) && expect.same(initial.balances, result.balances)
    }
  }

  List(StateChannelValidationType.Full, StateChannelValidationType.Historical).foreach { validationType =>
    List(false, true).foreach { feeCollision =>
      test(s"F1 same-batch accepted parent crosses activation; fee collision=$feeCollision ($validationType)") { res =>
        implicit val (k, j, h, s) = res
        for {
          f <- fixture(Map(Dev -> ordinal(20)), parentHasGlobalView = false)
          svc <- services(f)
          pre <- create(f, svc, SortedSet.empty)
          preOutput <- event(f, pre.artifact)
          before <- acceptGlobal(f, svc, f.info, List(preOutput), validationType = validationType)
          parent = before.lastCurrencySnapshots(f.owner).toOption.get
          next = f.copy(parent = parent._1, context = CurrencySnapshotContext(f.owner, parent._2), info = before)
          nextSvc <- services(next)
          active <- create(next, nextSvc, SortedSet(burn(f.owner, 30L)))
          owner <- Signed.forAsyncHasher(CurrencyMessage(MessageType.Owner, receiver, f.owner, MessageOrdinal.MinValue), f.key)
          occupied = f.context.snapshotInfo.copy(lastMessages = Some(SortedMap(MessageType.Owner -> owner)))
          initial =
            if (feeCollision)
              f.info.copy(lastCurrencySnapshots = f.info.lastCurrencySnapshots.updated(receiver, Right(f.parent -> occupied)))
            else f.info
          artifact = if (feeCollision) active.artifact.copy(messages = Some(SortedSet(owner))) else active.artifact
          output <- event(next, artifact, before.lastStateChannelSnapshotHashes(f.owner))
          result <- svc.processor
            .process(ordinal(21), initial, List(preOutput, output), validationType, n => f.recent.find(_.ordinal == n).pure[IO])
          // A fee collision must be rejected at SC admission, not merely by later Currency
          // reconstruction. Terminal rejection explicitly returns the offending SC event.
          accepted = result.calculatedCurrencyState(f.owner).toOption.get
        } yield
          expect.same(ordinal(20), parent._1.globalSyncView.get.ordinal) &&
            expect.same(if (feeCollision) Balance(100L) else Balance(70L), accepted._2.balances(f.owner)) &&
            expect.same(feeCollision, result.returned.contains(output))
      }
    }
  }

  List(false, true).foreach { firstIncremental =>
    List(StateChannelValidationType.Full, StateChannelValidationType.Historical).foreach { validationType =>
      test(
        s"F1 disabled opaque fallback still requires parseable Currency state when fees are required: genesis=$firstIncremental, $validationType"
      ) { res =>
        implicit val (k, j, h, s) = res
        for {
          f <- fixture(Map.empty)
          svc <- services(f, requireFee = true)
          clean <- create(f, svc, SortedSet.empty)
          signed <- Signed.forAsyncHasher(clean.artifact.copy(artifacts = Some(SortedSet[SharedArtifact](burn(f.owner, 1L)))), f.key)
          bytes <- j.serialize(signed)
          binary <- Signed.forAsyncHasher(StateChannelSnapshotBinary(binaryParent, bytes, SnapshotFee(1000L)), f.key)
          initial = if (firstIncremental) f.info.copy(lastCurrencySnapshots = SortedMap(f.owner -> Left(f.genesis))) else f.info
          result <- svc.processor.process(
            ordinal(21),
            initial,
            List(StateChannelOutput(f.owner, binary)),
            validationType,
            n => f.recent.find(_.ordinal == n).pure[IO]
          )
        } yield
          expect(result.accepted.isEmpty) && expect(result.returned.contains(StateChannelOutput(f.owner, binary))) &&
            expect.same(initial.lastCurrencySnapshots, result.calculatedCurrencyState) && expect(result.balanceUpdate.isEmpty)
      }
    }
  }

  // Mixed-channel admission regression, including staking metadata and reversed input order.
  List(StateChannelValidationType.Full, StateChannelValidationType.Historical).foreach { mode =>
    List(MessageType.Owner, MessageType.Staking).foreach { claimType =>
      test(s"F1-R inactive opaque $claimType metadata cannot block another channel's crossing burn ($mode)") { res =>
        implicit val (k, j, h, s) = res
        for {
          first <- fixture(Map(Dev -> ordinal(20)), parentHasGlobalView = false)
          second <- fixture(Map(Dev -> ordinal(20)), parentHasGlobalView = false)
          sorted = List(first, second).sortBy(_.owner)
          opaqueChannel = sorted.head
          honest = sorted.last
          initial = honest.info.copy(
            lastCurrencySnapshots = honest.info.lastCurrencySnapshots ++ opaqueChannel.info.lastCurrencySnapshots,
            lastStateChannelSnapshotHashes = honest.info.lastStateChannelSnapshotHashes ++ opaqueChannel.info.lastStateChannelSnapshotHashes
          )
          f = honest.copy(info = initial)
          svc <- services(f, other = Some(opaqueChannel))
          pre <- create(f, svc, SortedSet.empty)
          preOutput <- event(f, pre.artifact)
          before <- acceptGlobal(f, svc, initial, List(preOutput), validationType = mode)
          parent = before.lastCurrencySnapshots(f.owner).toOption.get
          next = f.copy(parent = parent._1, context = CurrencySnapshotContext(f.owner, parent._2), info = before)
          nextSvc <- services(next, other = Some(opaqueChannel))
          realOwner <- Signed.forAsyncHasher(CurrencyMessage(MessageType.Owner, f.owner, f.owner, MessageOrdinal.MinValue), f.key)
          active <- create(next, nextSvc, SortedSet(burn(f.owner, 30L)), List(realOwner))
          output <- event(next, active.artifact, before.lastStateChannelSnapshotHashes(f.owner))
          ghostOwner <- Signed.forAsyncHasher(
            CurrencyMessage(claimType, f.owner, opaqueChannel.owner, MessageOrdinal.MinValue),
            opaqueChannel.key
          )
          opaquePayload = opaqueChannel.parent.value.copy(
            artifacts = Some(SortedSet[SharedArtifact](burn(opaqueChannel.owner, 1L))),
            messages = Some(SortedSet(ghostOwner))
          )
          opaqueOutput <- event(opaqueChannel, opaquePayload)
          neutralOutput <- event(opaqueChannel, opaquePayload.copy(messages = None))
          control <- svc.processor.process(ordinal(21), initial, List(preOutput, output), mode, n => f.recent.find(_.ordinal == n).pure[IO])
          neutral <- svc.processor
            .process(ordinal(21), initial, List(neutralOutput, preOutput, output), mode, n => f.recent.find(_.ordinal == n).pure[IO])
          actual <- svc.processor
            .process(ordinal(21), initial, List(opaqueOutput, preOutput, output), mode, n => f.recent.find(_.ordinal == n).pure[IO])
          reordered <- svc.processor
            .process(ordinal(21), initial, List(output, preOutput, opaqueOutput), mode, n => f.recent.find(_.ordinal == n).pure[IO])
          controlState = control.calculatedCurrencyState(f.owner).toOption.get
          neutralState = neutral.calculatedCurrencyState(f.owner).toOption.get
          actualState = actual.calculatedCurrencyState(f.owner).toOption.get
        } yield
          expect(active.artifact.messages.exists(_.contains(realOwner))) &&
            expect.same(Balance(70L), controlState._2.balances(f.owner)) &&
            expect.same(controlState, neutralState) &&
            expect(actual.accepted.get(opaqueChannel.owner).nonEmpty) &&
            expect.same(initial.lastCurrencySnapshots(opaqueChannel.owner), actual.calculatedCurrencyState(opaqueChannel.owner)) &&
            expect.same(controlState, actualState) && expect(!actual.returned.contains(output)) && expect.same(actual, reordered)
      }
    }
  }

  List(StateChannelValidationType.Full, StateChannelValidationType.Historical).foreach { mode =>
    List(false, true).foreach { rejectFirst =>
      test(s"F1-R concurrent crossings reserve only accepted fee claims in address order; reject first=$rejectFirst ($mode)") { res =>
        implicit val (k, j, h, s) = res
        for {
          one <- fixture(Map(Dev -> ordinal(20)), parentHasGlobalView = false)
          two <- fixture(Map(Dev -> ordinal(20)), parentHasGlobalView = false)
          sorted = List(one, two).sortBy(_.owner)
          first = sorted.head
          second = sorted.last
          initial = first.info.copy(
            lastCurrencySnapshots = first.info.lastCurrencySnapshots ++ second.info.lastCurrencySnapshots,
            lastStateChannelSnapshotHashes = first.info.lastStateChannelSnapshotHashes ++ second.info.lastStateChannelSnapshotHashes
          )
          a = first.copy(info = initial)
          b = second.copy(info = initial, recent = first.recent)
          aSvc <- services(a, other = Some(b))
          bSvc <- services(b, other = Some(a))
          aPre <- create(a, aSvc, SortedSet.empty)
          bPre <- create(b, bSvc, SortedSet.empty)
          aPreOutput <- event(a, aPre.artifact)
          bPreOutput <- event(b, bPre.artifact)
          before <- acceptGlobal(a, aSvc, initial, List(aPreOutput, bPreOutput), validationType = mode)
          aParent = before.lastCurrencySnapshots(a.owner).toOption.get
          bParent = before.lastCurrencySnapshots(b.owner).toOption.get
          aNext = a.copy(parent = aParent._1, context = CurrencySnapshotContext(a.owner, aParent._2), info = before)
          bNext = b.copy(parent = bParent._1, context = CurrencySnapshotContext(b.owner, bParent._2), info = before)
          aNextSvc <- services(aNext, other = Some(b))
          bNextSvc <- services(bNext, other = Some(a))
          // Both proposals have real owner consent for the same fee address and are valid alone.
          aOwner <- Signed.forAsyncHasher(CurrencyMessage(MessageType.Owner, a.owner, a.owner, MessageOrdinal.MinValue), a.key)
          bOwner <- Signed
            .forAsyncHasher(CurrencyMessage(MessageType.Owner, a.owner, b.owner, MessageOrdinal.MinValue), a.key)
            .flatMap(_.signAlsoWith[IO](b.key))
          aActive <- create(aNext, aNextSvc, SortedSet(burn(a.owner, 30L)), List(aOwner))
          bActive <- create(bNext, bNextSvc, SortedSet(burn(b.owner, 30L)), List(bOwner))
          aArtifact =
            if (rejectFirst) aActive.artifact.copy(artifacts = Some(SortedSet[SharedArtifact](burn(a.owner, 101L)))) else aActive.artifact
          aOutput <- event(aNext, aArtifact, before.lastStateChannelSnapshotHashes(a.owner))
          bOutput <- event(bNext, bActive.artifact, before.lastStateChannelSnapshotHashes(b.owner))
          alone <- aSvc.processor
            .process(ordinal(21), initial, List(bPreOutput, bOutput), mode, n => a.recent.find(_.ordinal == n).pure[IO])
          batch = List(aPreOutput, aOutput, bPreOutput, bOutput)
          actual <- aSvc.processor.process(ordinal(21), initial, batch, mode, n => a.recent.find(_.ordinal == n).pure[IO])
          reordered <- aSvc.processor.process(ordinal(21), initial, batch.reverse, mode, n => a.recent.find(_.ordinal == n).pure[IO])
        } yield
          expect(aActive.artifact.messages.exists(_.contains(aOwner))) && expect(bActive.artifact.messages.exists(_.contains(bOwner))) &&
            expect.same(Balance(70L), alone.calculatedCurrencyState(b.owner).toOption.get._2.balances(b.owner)) &&
            expect.same(
              if (rejectFirst) Balance(100L) else Balance(70L),
              actual.calculatedCurrencyState(a.owner).toOption.get._2.balances(a.owner)
            ) &&
            expect.same(
              if (rejectFirst) Balance(70L) else Balance(100L),
              actual.calculatedCurrencyState(b.owner).toOption.get._2.balances(b.owner)
            ) &&
            expect.same(!rejectFirst, actual.returned.contains(bOutput)) && expect.same(actual, reordered)
      }
    }
  }

  test("replay and rollback from a serialized pre-activation checkpoint cross the gate identically") { res =>
    implicit val (k, j, h, s) = res
    for {
      f <- fixture(Map(Dev -> ordinal(20)), parentHasGlobalView = false)
      svc <- services(f)
      pre <- create(f, svc, SortedSet(burn(f.owner, 30L)))
      preOutput <- event(f, pre.artifact)
      before <- acceptGlobal(f, svc, f.info, List(preOutput))
      beforeBytes <- j.serialize(before)
      restored <- j.deserialize[GlobalSnapshotInfo](beforeBytes).flatMap(IO.fromEither)
      parent = restored.lastCurrencySnapshots(f.owner).toOption.get
      next = f.copy(parent = parent._1, context = CurrencySnapshotContext(f.owner, parent._2), info = restored)
      nextSvc <- services(next)
      active <- create(next, nextSvc, SortedSet(burn(f.owner, 30L)))
      activeOutput <- event(next, active.artifact, restored.lastStateChannelSnapshotHashes(f.owner))
      after <- acceptGlobal(next, nextSvc, restored, List(activeOutput), 22L)
      restarted <- services(next)
      rolledForward <- acceptGlobal(next, restarted, restored, List(activeOutput), 22L)
      fresh <- services(f)
      replayBefore <- acceptGlobal(f, fresh, f.info, List(preOutput))
      replayAfter <- acceptGlobal(next, restarted, replayBefore, List(activeOutput), 22L)
    } yield
      expect.same(Balance(100L), parent._2.balances(f.owner)) &&
        expect.same(ordinal(20), parent._1.globalSyncView.get.ordinal) &&
        expect.same(Balance(70L), after.lastCurrencySnapshots(f.owner).toOption.get._2.balances(f.owner)) &&
        expect.same(after, rolledForward) && expect.same(after, replayAfter)
  }

  test("fresh reconstruction and reprocessing do not repeat a burn; a later equal burn remains lawful") { res =>
    implicit val (k, j, h, s) = res
    for {
      f <- fixture()
      svc <- services(f)
      created <- create(f, svc, SortedSet(burn(f.owner, 30L)))
      output <- event(f, created.artifact)
      first <- acceptGlobal(f, svc, f.info, List(output))
      fresh <- services(f)
      replay <- acceptGlobal(f, fresh, f.info, List(output))
      duplicate <- acceptGlobal(f, fresh, first, List(output))
      bytes <- j.serialize(first)
      restored <- j.deserialize[GlobalSnapshotInfo](bytes).flatMap(IO.fromEither)
      accepted = restored.lastCurrencySnapshots(f.owner).toOption.get
      next = f.copy(parent = accepted._1, context = CurrencySnapshotContext(f.owner, accepted._2), info = restored)
      nextSvc <- services(next)
      nextCreated <- create(next, nextSvc, SortedSet(burn(f.owner, 30L)))
      nextOutput <- event(next, nextCreated.artifact, restored.lastStateChannelSnapshotHashes(f.owner))
      second <- acceptGlobal(next, nextSvc, restored, List(nextOutput), 22L)
      lateRetry <- acceptGlobal(next, nextSvc, second, List(output), 23L)
    } yield
      expect.same(first, replay) && expect.same(first.lastCurrencySnapshots, duplicate.lastCurrencySnapshots) &&
        expect.same(Balance(70L), accepted._2.balances(f.owner)) &&
        expect.same(Balance(40L), second.lastCurrencySnapshots(f.owner).toOption.get._2.balances(f.owner)) &&
        expect.same(second.lastCurrencySnapshots, lateRetry.lastCurrencySnapshots)
  }

  List("dag", "delegated", "intentHash", "overflow").foreach { attack =>
    List(false, true).foreach { firstIncremental =>
      test(s"signed malformed $attack burn cannot become opaque data (first incremental=$firstIncremental)") { res =>
        implicit val (k, j, h, s) = res
        for {
          f <- fixture()
          svc <- services(f)
          clean <- create(f, svc, SortedSet.empty)
          tx = burn(f.owner, 1L).burnTransactions.head.asJson.mapObject { fields =>
            attack match {
              case "dag"        => fields.add("currencyId", Json.Null)
              case "delegated"  => fields.add("allowSpendRef", Json.fromString("unauthorized"))
              case "intentHash" => fields.add("intentHash", Json.fromString("unbound"))
              case _            => fields.add("amount", Json.fromBigInt(BigInt(Long.MaxValue) + 1))
            }
          }
          artifacts = Json.arr(Json.obj("BurnAction" -> Json.obj("burnTransactions" -> Json.arr(tx))))
          forged = (if (firstIncremental) f.parent.value else clean.artifact).asJson.mapObject(_.add("artifacts", artifacts))
          signed <- Signed.forAsyncHasher(forged, f.key)
          bytes <- j.serialize(signed)
          strict <- j.deserialize[Signed[CurrencyIncrementalSnapshot]](bytes)
          binary <- Signed.forAsyncHasher(StateChannelSnapshotBinary(binaryParent, bytes, SnapshotFee.MinValue), f.key)
          initial = if (firstIncremental) f.info.copy(lastCurrencySnapshots = SortedMap(f.owner -> Left(f.genesis))) else f.info
          result <- acceptGlobal(f, svc, initial, List(StateChannelOutput(f.owner, binary)))
        } yield
          expect(strict.isLeft) && expect.same(initial.lastCurrencySnapshots, result.lastCurrencySnapshots) &&
            expect.same(initial.lastStateChannelSnapshotHashes, result.lastStateChannelSnapshotHashes) &&
            expect.same(initial.balances, result.balances)
      }
    }
  }

  test("unrelated opaque state-channel payload keeps its existing no-fee behavior") { res =>
    implicit val (k, j, h, s) = res
    for {
      f <- fixture()
      svc <- services(f)
      bytes <- j.serialize(Json.obj("opaqueApplicationData" -> Json.fromString("unchanged")))
      binary <- Signed.forAsyncHasher(StateChannelSnapshotBinary(binaryParent, bytes, SnapshotFee.MinValue), f.key)
      hashed <- binary.toHashed
      result <- acceptGlobal(f, svc, f.info, List(StateChannelOutput(f.owner, binary)))
    } yield
      expect.same(f.info.lastCurrencySnapshots, result.lastCurrencySnapshots) &&
        expect.same(hashed.hash, result.lastStateChannelSnapshotHashes(f.owner))
  }

  List(0L, 60L, 100L).foreach { burnAmount =>
    test(s"Global validates a newly requested spend against the post-burn $burnAmount balance") { res =>
      implicit val (k, j, h, s) = res
      for {
        f <- fixture()
        svc <- services(f)
        spend = SpendAction(NonEmptyList.one(SpendTransaction(None, Some(CurrencyId(f.owner)), SwapAmount(100L), f.owner, receiver)))
        artifacts = SortedSet[SharedArtifact](spend) ++ Option.when(burnAmount > 0)(burn(f.owner, burnAmount))
        created <- create(f, svc, artifacts)
        output <- event(f, created.artifact)
        manager <- Mocks.mkManager(Some(f.info), stateChannelProcessor = Some(svc.processor), spendValidator = Some(svc.spendValidator))
        result <- manager.accept(
          ordinal(21),
          EpochProgress.MinValue,
          Nil,
          Nil,
          Nil,
          List(output),
          Nil,
          Nil,
          Nil,
          Nil,
          Nil,
          f.info,
          SortedSet.empty,
          SortedSet.empty,
          Mocks.delegatedRewardsFunction[IO](f.info),
          StateChannelValidationType.Full,
          n => f.recent.find(_.ordinal == n).pure[IO],
          AllowSpendBlockAcceptanceMode.live
        )
      } yield
        expect.same(burnAmount == 0L, result._11.get(f.owner).exists(_.contains(spend))) &&
          expect.same(
            Balance(NonNegLong.unsafeFrom(100L - burnAmount)),
            result._9.lastCurrencySnapshots(f.owner).toOption.get._2.balances(f.owner)
          )
    }
  }

  test("a lagging currency cannot burn funds already committed to an unapplied Global spend") { res =>
    implicit val (k, j, h, s) = res
    for {
      f <- fixture(spend = 100L, lagging = true)
      svc <- services(f)
      ordinary <- create(f, svc, SortedSet.empty)
      attempted <- create(f, svc, SortedSet(burn(f.owner, 100L)))
    } yield expect.same(ordinary, attempted)
  }

  test("a signed first-incremental projection cannot smuggle an unexecuted burn") { res =>
    implicit val (k, j, h, s) = res
    for {
      f <- fixture()
      svc <- services(f)
      initial = f.info.copy(lastCurrencySnapshots = SortedMap(f.owner -> Left(f.genesis)))
      forged = f.parent.value.copy(artifacts = Some(SortedSet[SharedArtifact](burn(f.owner, 100L))))
      output <- event(f, forged)
      result <- acceptGlobal(f, svc, initial, List(output))
    } yield
      expect.same(initial.lastCurrencySnapshots, result.lastCurrencySnapshots) &&
        expect.same(initial.lastStateChannelSnapshotHashes, result.lastStateChannelSnapshotHashes)
  }
}
