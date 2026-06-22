package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.{JsonBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.swap.block.AllowSpendBlockAcceptanceManager
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.currency.CurrencySnapshotAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global.SpendTransactionBalanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.modules.SharedValidators
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node.RewardFraction
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.{height, _}
import io.constellationnetwork.security._
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import fs2.concurrent.SignallingRef
import weaver.MutableIOSuite

/** End-to-end integration coverage for the native L0 token-burn primitive (BurnAction / BurnTransaction).
  *
  * The unit suites (BurnActionValidatorSuite / BurnTransactionBalanceSuite) drive the validator and the balance appliers in isolation. This
  * suite exercises the parts the unit tests cannot:
  *
  *   1. ACCEPTANCE — a self-burn BurnAction supplied as a shared artifact is run through the REAL
  *      `CurrencySnapshotAcceptanceManager.accept` machinery (the exact production path a currency snapshot follows) and is ACCEPTED,
  *      surviving into the result `sharedArtifacts`. 2. SUPPLY DECREASE — the resulting `CurrencySnapshotInfo.balances` (the on-chain
  *      ledger) shows the metagraph (currencyId) balance and the total supply strictly reduced by exactly the burned amount. 3.
  *      RE-EXECUTION IDENTITY — re-running the accepted burn transactions through the GLOBAL-side applier
  *      (`SpendTransactionBalanceManager`, the one the global L0 uses to re-create the snapshot) yields byte-identical balances to the
  *      currency-side result. This is the precise condition that prevents `SnapshotDifferentThanExpected` at global re-execution. 4.
  *      SERIALIZATION ROUND-TRIP — a BurnAction survives the codecs real snapshots use on the wire: (a) the circe/JSON codec (the path used
  *      for snapshot hashing and gossip, exercised both as a bare SharedArtifact and inside a full Signed[CurrencyIncrementalSnapshot] via
  *      JsonBinarySerializer). A break in the codec would silently corrupt burns in transit; the unit suites never serialize a BurnAction.
  *      NOTE: the legacy Kryo snapshot format (CurrencyIncrementalSnapshotV1) drops the `artifacts` field entirely and Kryo runs with
  *      setRegistrationRequired=true (BurnAction is intentionally not registered), so a bare-Kryo round-trip is NOT a production path — the
  *      JSON/binary path below is the faithful wire test.
  */
object BurnActionSnapshotIntegrationSuite extends MutableIOSuite {

  implicit val currencyStateProofSelector: CurrencyStateProofSelector = CurrencyStateProofSelector.instance

  type Res = (KryoSerializer[IO], Hasher[IO], JsonSerializer[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](sharedKryoRegistrar)
    sp <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
  } yield (ks, h, j, sp)

  // burnActionActivation = Map(Dev -> SnapshotOrdinal.MinValue) so burns are active from genesis; every other field is empty.
  private val fieldsAddedOrdinals: FieldsAddedOrdinals = FieldsAddedOrdinals(
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map.empty,
    Map(Dev -> SnapshotOrdinal.MinValue)
  )

  private def totalSupply(balances: SortedMap[Address, Balance]): Long =
    balances.values.map(_.value.value).sum

  /** Build the real CurrencySnapshotAcceptanceManager exactly as GlobalSnapshotTraverseSuite does, with global snapshot storage seeded by a
    * genesis global snapshot so the accept path can resolve epoch/ordinal metadata.
    */
  private def mkAcceptanceManager(
    foa: FieldsAddedOrdinals = fieldsAddedOrdinals
  )(
    implicit ks: KryoSerializer[IO],
    h: Hasher[IO],
    j: JsonSerializer[IO],
    sp: SecurityProvider[IO]
  ): IO[(CurrencySnapshotAcceptanceManager[IO], Hashed[GlobalIncrementalSnapshot])] = {
    val txHasher = Hasher.forKryo[IO]
    val addressesConfig = AddressesConfig(Set())

    val validators = SharedValidators.make[IO](
      Dev,
      addressesConfig,
      None,
      None,
      Some(Map.empty[Address, NonEmptySet[PeerId]]),
      SortedMap.empty,
      PosLong(Long.MaxValue),
      txHasher,
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

    val lastGlobalSnapshotsSyncConfig = LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(10))

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      genesis <- Signed
        .forAsyncHasher[IO, GlobalSnapshot](GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue), keyPair)
        .flatMap(_.toHashed)
      genesisInfo = genesis.info.toGlobalSnapshotInfo
      genesisIncremental <- GlobalIncrementalSnapshot
        .fromGlobalSnapshot[IO](genesis)
        .flatMap(Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](_, keyPair))
        .flatMap(_.toHashed)

      lastSnapR <- SignallingRef.of[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](None)
      lastNSnapR <- SignallingRef.of[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](None)
      incLastNSnapR <- SignallingRef
        .of[IO, SortedMap[SnapshotOrdinal, Hashed[GlobalIncrementalSnapshot]]](SortedMap.empty)

      lastGlobalSnapshotStorage = LastSnapshotStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](lastSnapR)
      _ <- lastGlobalSnapshotStorage.setInitial(genesisIncremental, genesisInfo)

      lastNSnapshotStorage = LastNGlobalSnapshotStorage.make[IO](lastGlobalSnapshotsSyncConfig, lastNSnapR, incLastNSnapR)

      acceptanceManager <- CurrencySnapshotAcceptanceManager.make[IO](
        foa,
        Dev,
        lastGlobalSnapshotsSyncConfig,
        BlockAcceptanceManager.make[IO](validators.currencyBlockValidator, txHasher),
        TokenLockBlockAcceptanceManager.make[IO](validators.tokenLockBlockValidator),
        AllowSpendBlockAcceptanceManager.make[IO](validators.allowSpendBlockValidator),
        Amount(0L),
        validators.currencyMessageValidator,
        validators.feeTransactionValidator,
        validators.globalSnapshotSyncValidator,
        lastNSnapshotStorage,
        lastGlobalSnapshotStorage
      )
    } yield (acceptanceManager, genesisIncremental)
  }

  private def emptyCurrencyInfo(balances: SortedMap[Address, Balance]): CurrencySnapshotInfo =
    CurrencySnapshotInfo(
      lastTxRefs = SortedMap.empty,
      balances = balances,
      lastMessages = None,
      lastFeeTxRefs = None,
      lastAllowSpendRefs = None,
      activeAllowSpends = None,
      globalSnapshotSyncView = None,
      lastTokenLockRefs = None,
      activeTokenLocks = None
    )

  test("self-burn BurnAction is ACCEPTED through CurrencySnapshotAcceptanceManager and strictly reduces totalSupply (no double-apply)") {
    res =>
      implicit val (ks, h, j, sp) = res

      val burnAmount = 30L
      val startingBalance = 100L

      for {
        managerAndGenesis <- mkAcceptanceManager()
        (acceptanceManager, genesisIncremental) = managerAndGenesis

        // The metagraph (== currencyId) holds a balance in the currency context; a self-burn destroys part of it.
        metagraphKeyPair <- KeyPairGenerator.makeKeyPair[IO]
        metagraphId = metagraphKeyPair.getPublic.toAddress

        startingBalances = SortedMap(metagraphId -> Balance(NonNegLong.unsafeFrom(startingBalance)))
        lastContext = CurrencySnapshotContext(metagraphId, emptyCurrencyInfo(startingBalances))

        selfBurnTx = BurnTransaction(none, CurrencyId(metagraphId).some, SwapAmount(PosLong.unsafeFrom(burnAmount)), metagraphId)
        burnAction = BurnAction(NonEmptyList.of(selfBurnTx))
        sharedArtifacts = SortedSet[SharedArtifact](burnAction)

        // A signature proof of the genesis signer satisfies the lastArtifactProofs / facilitators requirement.
        proofs = genesisIncremental.signed.proofs
        facilitators = proofs.toNonEmptyList.toList.map(p => PeerId.fromId(p.id)).toSet

        result <- acceptanceManager.accept(
          blocksForAcceptance = List.empty,
          tokenLockBlocksForAcceptance = List.empty,
          allowSpendBlocksForAcceptance = List.empty,
          messagesForAcceptance = List.empty,
          feeTransactionsForAcceptance = None,
          globalSnapshotSyncsForAcceptance = List.empty,
          sharedArtifactsForAcceptance = sharedArtifacts,
          lastSnapshotContext = lastContext,
          snapshotOrdinal = SnapshotOrdinal(NonNegLong(1L)),
          epochProgress = EpochProgress.MinValue,
          lastActiveTips = SortedSet.empty,
          lastDeprecatedTips = SortedSet.empty,
          calculateRewardsFn = _ => SortedSet.empty[transaction.RewardTransaction].pure[IO],
          facilitators = facilitators,
          getGlobalSnapshotByOrdinal = _ => genesisIncremental.some.pure[IO],
          lastGlobalSyncView = None,
          shouldPerformMetagraphSpecificValidations = false,
          lastArtifactProofs = proofs
        )

        acceptedBurns = result.sharedArtifacts.collect { case ba: BurnAction => ba }
        resultingBalances = result.info.balances
      } yield
        expect.all(
          // (1) ACCEPTANCE: the burn survived into the snapshot's accepted shared artifacts.
          acceptedBurns.contains(burnAction),
          // (2) SUPPLY DECREASE: the metagraph balance and total supply each fell by exactly the burned amount.
          resultingBalances.getOrElse(metagraphId, Balance.empty) === Balance(NonNegLong.unsafeFrom(startingBalance - burnAmount)),
          totalSupply(resultingBalances) === startingBalance - burnAmount,
          totalSupply(resultingBalances) < startingBalance
        )
  }

  test("accepted burn re-executes identically on the global-side applier (the no-SnapshotDifferentThanExpected condition)") { res =>
    implicit val (ks, h, j, sp) = res

    val burnAmount = 30L
    val startingBalance = 100L
    val globalManager = SpendTransactionBalanceManager.make[IO]()

    for {
      managerAndGenesis <- mkAcceptanceManager()
      (acceptanceManager, genesisIncremental) = managerAndGenesis

      metagraphKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      metagraphId = metagraphKeyPair.getPublic.toAddress

      startingBalances = SortedMap(metagraphId -> Balance(NonNegLong.unsafeFrom(startingBalance)))
      lastContext = CurrencySnapshotContext(metagraphId, emptyCurrencyInfo(startingBalances))

      selfBurnTx = BurnTransaction(none, CurrencyId(metagraphId).some, SwapAmount(PosLong.unsafeFrom(burnAmount)), metagraphId)
      burnAction = BurnAction(NonEmptyList.of(selfBurnTx))

      proofs = genesisIncremental.signed.proofs
      facilitators = proofs.toNonEmptyList.toList.map(p => PeerId.fromId(p.id)).toSet

      result <- acceptanceManager.accept(
        blocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        messagesForAcceptance = List.empty,
        feeTransactionsForAcceptance = None,
        globalSnapshotSyncsForAcceptance = List.empty,
        sharedArtifactsForAcceptance = SortedSet[SharedArtifact](burnAction),
        lastSnapshotContext = lastContext,
        snapshotOrdinal = SnapshotOrdinal(NonNegLong(1L)),
        epochProgress = EpochProgress.MinValue,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = _ => SortedSet.empty[transaction.RewardTransaction].pure[IO],
        facilitators = facilitators,
        getGlobalSnapshotByOrdinal = _ => genesisIncremental.some.pure[IO],
        lastGlobalSyncView = None,
        shouldPerformMetagraphSpecificValidations = false,
        lastArtifactProofs = proofs
      )

      // Re-execute the accepted burn through the GLOBAL-side applier from the same starting balances, as the global L0 does when
      // re-creating the currency snapshot. If this diverged from the currency-side result, validation would flag
      // SnapshotDifferentThanExpected.
      acceptedBurnTxs = result.sharedArtifacts.collect { case ba: BurnAction => ba }.toList.flatMap(_.burnTransactions.toList)
      noAllowSpends = SortedMap.empty[Address, List[Hashed[AllowSpend]]]
      globalResult = globalManager.updateGlobalBalancesByBurnTransactions(startingBalances, noAllowSpends, acceptedBurnTxs)
    } yield
      globalResult match {
        case Right((globalBalances, _)) =>
          expect.all(
            acceptedBurnTxs === List(selfBurnTx),
            // Re-execution identity: the global-side applier reproduces the currency snapshot's ledger exactly.
            globalBalances === result.info.balances,
            totalSupply(globalBalances) === startingBalance - burnAmount
          )
        case Left(error) => failure(s"Global re-execution failed: $error")
      }
  }

  test(
    "currency acceptance rejects a burnFrom colliding with a same-snapshot SpendAction (spends win; burn never enters accepted artifacts)"
  ) { res =>
    implicit val (ks, h, j, sp) = res

    val allowance = 100L
    val spendAmount = 60L
    val burnAmount = 40L
    val startingBalance = 1000L

    for {
      managerAndGenesis <- mkAcceptanceManager()
      (acceptanceManager, genesisIncremental) = managerAndGenesis

      metagraphKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      holderKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      metagraphId = metagraphKeyPair.getPublic.toAddress
      holder = holderKeyPair.getPublic.toAddress
      currencyId = CurrencyId(metagraphId)

      // Holder authorizes the metagraph (currencyId) for up to `allowance`; valid for BOTH a spend-to-currencyId AND a burnFrom
      // (burn checks are a subset of spend checks), so on its own this burnFrom would be accepted.
      allowSpend = AllowSpend(
        holder,
        metagraphId,
        currencyId.some,
        SwapAmount(PosLong.unsafeFrom(allowance)),
        AllowSpendFee(1L),
        AllowSpendReference.empty,
        EpochProgress(1000L),
        List(metagraphId)
      )
      signedAllowSpend <- Signed.forAsyncHasher(allowSpend, holderKeyPair)
      hashedAllowSpend <- signedAllowSpend.toHashed
      ref = hashedAllowSpend.hash

      startingBalances = SortedMap(metagraphId -> Balance(NonNegLong.unsafeFrom(startingBalance)))
      contextInfo = emptyCurrencyInfo(startingBalances).copy(
        activeAllowSpends = SortedMap(holder -> SortedSet(signedAllowSpend)).some
      )
      lastContext = CurrencySnapshotContext(metagraphId, contextInfo)

      // Same snapshot: a SpendAction and a BurnAction BOTH reference the same allow-spend.
      spendTx = SpendTransaction(ref.some, currencyId.some, SwapAmount(PosLong.unsafeFrom(spendAmount)), holder, metagraphId)
      spendAction = SpendAction(NonEmptyList.of(spendTx))
      burnTx = BurnTransaction(ref.some, currencyId.some, SwapAmount(PosLong.unsafeFrom(burnAmount)), holder)
      burnAction = BurnAction(NonEmptyList.of(burnTx))
      sharedArtifacts = SortedSet[SharedArtifact](spendAction, burnAction)

      proofs = genesisIncremental.signed.proofs
      facilitators = proofs.toNonEmptyList.toList.map(p => PeerId.fromId(p.id)).toSet

      result <- acceptanceManager.accept(
        blocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        messagesForAcceptance = List.empty,
        feeTransactionsForAcceptance = None,
        globalSnapshotSyncsForAcceptance = List.empty,
        sharedArtifactsForAcceptance = sharedArtifacts,
        lastSnapshotContext = lastContext,
        snapshotOrdinal = SnapshotOrdinal(NonNegLong(1L)),
        epochProgress = EpochProgress.MinValue,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = _ => SortedSet.empty[transaction.RewardTransaction].pure[IO],
        facilitators = facilitators,
        getGlobalSnapshotByOrdinal = _ => genesisIncremental.some.pure[IO],
        lastGlobalSyncView = None,
        shouldPerformMetagraphSpecificValidations = false,
        lastArtifactProofs = proofs
      )

      acceptedBurns = result.sharedArtifacts.collect { case ba: BurnAction => ba }
      passedThroughSpends = result.sharedArtifacts.collect { case sa: SpendAction => sa }
    } yield
      expect.all(
        // The colliding burn is rejected and never enters the snapshot's accepted artifacts (spends win).
        acceptedBurns.isEmpty,
        // The spend passes through unchanged for global application.
        passedThroughSpends.contains(spendAction),
        // No extra source refund: the rejected burn produced no balance effect for the holder...
        result.info.balances.getOrElse(holder, Balance.empty) === Balance.empty,
        // ...and the metagraph balance is untouched at the currency level.
        result.info.balances.getOrElse(metagraphId, Balance.empty) === Balance(NonNegLong.unsafeFrom(startingBalance))
      )
  }

  test("currency acceptance gate: below the activation ordinal a burn is ignored (no accept, no supply change)") { res =>
    implicit val (ks, h, j, sp) = res

    val burnAmount = 30L
    val startingBalance = 100L

    // Activation set ABOVE the metagraph's synced global view (the seeded genesis is ordinal 0), so burns are gated OFF. The
    // active-from-genesis (at/above) case is covered by the "self-burn ACCEPTED ..." test, so together they bracket the boundary.
    val gatedFoa = fieldsAddedOrdinals.copy(burnActionActivation = Map(Dev -> SnapshotOrdinal(NonNegLong(1000L))))

    for {
      managerAndGenesis <- mkAcceptanceManager(gatedFoa)
      (acceptanceManager, genesisIncremental) = managerAndGenesis

      metagraphKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      metagraphId = metagraphKeyPair.getPublic.toAddress

      startingBalances = SortedMap(metagraphId -> Balance(NonNegLong.unsafeFrom(startingBalance)))
      lastContext = CurrencySnapshotContext(metagraphId, emptyCurrencyInfo(startingBalances))

      selfBurnTx = BurnTransaction(none, CurrencyId(metagraphId).some, SwapAmount(PosLong.unsafeFrom(burnAmount)), metagraphId)
      burnAction = BurnAction(NonEmptyList.of(selfBurnTx))

      proofs = genesisIncremental.signed.proofs
      facilitators = proofs.toNonEmptyList.toList.map(p => PeerId.fromId(p.id)).toSet

      result <- acceptanceManager.accept(
        blocksForAcceptance = List.empty,
        tokenLockBlocksForAcceptance = List.empty,
        allowSpendBlocksForAcceptance = List.empty,
        messagesForAcceptance = List.empty,
        feeTransactionsForAcceptance = None,
        globalSnapshotSyncsForAcceptance = List.empty,
        sharedArtifactsForAcceptance = SortedSet[SharedArtifact](burnAction),
        lastSnapshotContext = lastContext,
        snapshotOrdinal = SnapshotOrdinal(NonNegLong(1L)),
        epochProgress = EpochProgress.MinValue,
        lastActiveTips = SortedSet.empty,
        lastDeprecatedTips = SortedSet.empty,
        calculateRewardsFn = _ => SortedSet.empty[transaction.RewardTransaction].pure[IO],
        facilitators = facilitators,
        getGlobalSnapshotByOrdinal = _ => genesisIncremental.some.pure[IO],
        lastGlobalSyncView = None,
        shouldPerformMetagraphSpecificValidations = false,
        lastArtifactProofs = proofs
      )

      acceptedBurns = result.sharedArtifacts.collect { case ba: BurnAction => ba }
    } yield
      expect.all(
        acceptedBurns.isEmpty,
        result.info.balances.getOrElse(metagraphId, Balance.empty) === Balance(NonNegLong.unsafeFrom(startingBalance)),
        totalSupply(result.info.balances) === startingBalance
      )
  }

  test("BurnTransaction decodes intentHash = None when the field is absent in JSON (backward compatibility)") { res =>
    implicit val (ks, h, j, sp) = res

    for {
      kp <- KeyPairGenerator.makeKeyPair[IO]
      source = kp.getPublic.toAddress
      bt = BurnTransaction(none, none, SwapAmount(5L), source) // intentHash defaults to None
      json = io.circe.Encoder[BurnTransaction].apply(bt)
      withoutIntentHash = json.mapObject(_.remove("intentHash"))
      decoded = io.circe.Decoder[BurnTransaction].decodeJson(withoutIntentHash)
    } yield
      expect.all(
        // The JSON being decoded genuinely has no intentHash field ...
        withoutIntentHash.asObject.exists(o => !o.contains("intentHash")),
        // ... and an absent field decodes as None, so pre-intentHash burns remain valid.
        decoded.isRight,
        decoded.toOption.flatMap(_.intentHash).isEmpty
      )
  }

  test("BurnAction round-trips through the JSON (circe) codec used for snapshot hashing and gossip") { res =>
    implicit val (ks, h, j, sp) = res

    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      source = kp1.getPublic.toAddress
      currencyId = CurrencyId(kp2.getPublic.toAddress)

      // Cover both burn shapes: a burnFrom (with allowSpendRef) and a self-burn (no ref), wrapped as the SharedArtifact subtype the
      // snapshot field actually holds, so we exercise the sealed-trait encoder/decoder discriminator too.
      burnFromTx = BurnTransaction(io.constellationnetwork.security.hash.Hash.empty.some, currencyId.some, SwapAmount(7L), source)
      // self-burn carrying an opaque application-defined intentHash (no allowSpendRef to bind to) — must survive the codec.
      selfBurnTx = BurnTransaction(
        none,
        currencyId.some,
        SwapAmount(3L),
        currencyId.value,
        io.constellationnetwork.security.hash.Hash.empty.some
      )
      burnAction: SharedArtifact = BurnAction(NonEmptyList.of(burnFromTx, selfBurnTx))

      jsonBytes <- JsonSerializer[IO].serialize[SharedArtifact](burnAction)
      jsonDecoded <- JsonSerializer[IO].deserialize[SharedArtifact](jsonBytes)
    } yield expect.same(Right(burnAction), jsonDecoded)
  }

  test("a full Signed[CurrencyIncrementalSnapshot] carrying a BurnAction survives the JsonBinarySerializer wire codec") { res =>
    implicit val (ks, h, j, sp) = res

    // The current CurrencyIncrementalSnapshot is serialized for gossip via JsonBinarySerializer (circe over a binary envelope) — the
    // legacy Kryo path (CurrencyIncrementalSnapshotV1) drops the `artifacts` field entirely, so a bare-Kryo round-trip of a BurnAction
    // is not a production path (and cannot even register under setRegistrationRequired=true). This test exercises the real wire path:
    // a signed snapshot whose `artifacts` set holds a BurnAction must round-trip byte-for-byte.
    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      source = kp1.getPublic.toAddress
      currencyId = CurrencyId(kp2.getPublic.toAddress)

      burnFromTx = BurnTransaction(io.constellationnetwork.security.hash.Hash.empty.some, currencyId.some, SwapAmount(7L), source)
      // self-burn carrying an opaque application-defined intentHash (no allowSpendRef to bind to) — must survive the codec.
      selfBurnTx = BurnTransaction(
        none,
        currencyId.some,
        SwapAmount(3L),
        currencyId.value,
        io.constellationnetwork.security.hash.Hash.empty.some
      )
      burnAction: SharedArtifact = BurnAction(NonEmptyList.of(burnFromTx, selfBurnTx))

      info = emptyCurrencyInfo(SortedMap.empty)
      stateProof <- info.stateProof[IO](SnapshotOrdinal(NonNegLong(1L)))
      snapshot = CurrencyIncrementalSnapshot(
        ordinal = SnapshotOrdinal(NonNegLong(1L)),
        height = height.Height(NonNegLong(1L)),
        subHeight = height.SubHeight(NonNegLong(1L)),
        lastSnapshotHash = io.constellationnetwork.security.hash.Hash.empty,
        blocks = SortedSet.empty,
        rewards = SortedSet.empty,
        tips = SnapshotTips(SortedSet.empty, SortedSet.empty),
        stateProof = stateProof,
        epochProgress = EpochProgress.MinValue,
        dataApplication = None,
        messages = None,
        globalSnapshotSyncs = None,
        feeTransactions = None,
        artifacts = SortedSet(burnAction).some,
        allowSpendBlocks = None,
        tokenLockBlocks = None,
        globalSyncView = None
      )
      signedSnapshot <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](snapshot, kp1)

      serialized = JsonBinarySerializer.serialize(signedSnapshot)
      deserialized = JsonBinarySerializer.deserialize[Signed[CurrencyIncrementalSnapshot]](serialized)

      roundTrippedArtifacts = deserialized.toOption.flatMap(_.value.artifacts).getOrElse(SortedSet.empty[SharedArtifact])
    } yield
      expect
        .same(Right(signedSnapshot), deserialized)
        .and(expect(roundTrippedArtifacts.contains(burnAction)))
  }
}
