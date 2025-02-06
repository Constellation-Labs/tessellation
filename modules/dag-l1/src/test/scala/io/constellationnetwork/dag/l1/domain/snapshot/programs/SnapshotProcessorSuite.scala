package io.constellationnetwork.dag.l1.domain.snapshot.programs

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect._
import cats.effect.std.Random
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.dag.l1.Main
import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage._
import io.constellationnetwork.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import io.constellationnetwork.dag.l1.domain.transaction._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.collection.MapRefUtils._
import io.constellationnetwork.json.{JsonBrotliBinarySerializer, JsonSerializer}
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.statechannel.FeeCalculator
import io.constellationnetwork.node.shared.domain.swap.block.{AllowSpendBlockAcceptanceManager, AllowSpendBlockStorage}
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendStorage, ContextualAllowSpendValidator}
import io.constellationnetwork.node.shared.domain.tokenlock.block.TokenLockBlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.node.shared.infrastructure.snapshot._
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.{LastNGlobalSnapshotStorage, LastSnapshotStorage}
import io.constellationnetwork.node.shared.modules.SharedValidators
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.swap.AllowSpendReference
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.transaction.TransactionGenerator

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosInt}
import fs2.concurrent.SignallingRef
import io.chrisdavenport.mapref.MapRef
import org.scalacheck.Gen.Parameters
import org.scalacheck.rng.Seed
import weaver.SimpleIOSuite

object SnapshotProcessorSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = (
    SnapshotProcessor[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
    SecurityProvider[IO],
    Hasher[IO],
    KryoSerializer[IO],
    (KeyPair, KeyPair, KeyPair),
    KeyPair,
    KeyPair,
    Address,
    Address,
    PeerId,
    Ref[IO, Map[Address, Balance]],
    MapRef[IO, ProofsHash, Option[StoredBlock]],
    Ref[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]],
    TransactionStorage[IO],
    GlobalSnapshotContextFunctions[IO]
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar).flatMap { implicit kp =>
        Random.scalaUtilRandom[IO].asResource.flatMap { implicit random =>
          for {
            implicit0(jhs: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
            implicit0(h: Hasher[IO]) = Hasher.forJson[IO]
            balancesR <- Ref.of[IO, Map[Address, Balance]](Map.empty).asResource
            blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]().asResource
            lastSnapR <- SignallingRef.of[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](None).asResource
            lastNSnapR <- SignallingRef
              .of[IO, SortedMap[SnapshotOrdinal, (Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](SortedMap.empty)
              .asResource
            transactionsR <- MapRef
              .ofConcurrentHashMap[IO, Address, SortedMap[TransactionOrdinal, StoredTransaction]]()
              .asResource
            validators = SharedValidators
              .make[IO](AddressesConfig(Set()), None, None, Some(Map.empty), SortedMap.empty, Long.MaxValue, Hasher.forKryo[IO])
            contextualTransactionValidator = ContextualTransactionValidator
              .make(TransactionLimitConfig(Balance.empty, 0.hours, TransactionFee.zero, 1.second), None)
            transactionStorage = new TransactionStorage[IO](
              transactionsR,
              TransactionReference.empty,
              contextualTransactionValidator
            )
            allowSpendBlockStorage <- AllowSpendBlockStorage.make[IO].asResource
            allowSpendStorage <- AllowSpendStorage
              .make[IO](
                AllowSpendReference.empty,
                ContextualAllowSpendValidator.make(None, AllowSpendsConfig(MinMax(min = NonNegLong(1L), max = NonNegLong(100L))))
              )
              .asResource

            currencySnapshotAcceptanceManager = CurrencySnapshotAcceptanceManager.make(
              LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt(10)),
              BlockAcceptanceManager.make[IO](validators.currencyBlockValidator, Hasher.forKryo[IO]),
              TokenLockBlockAcceptanceManager.make[IO](validators.tokenLockBlockValidator),
              AllowSpendBlockAcceptanceManager.make[IO](validators.allowSpendBlockValidator),
              Amount(0L),
              validators.currencyMessageValidator,
              validators.feeTransactionValidator,
              validators.globalSnapshotSyncValidator
            )
            implicit0(hs: HasherSelector[IO]) = HasherSelector.forSyncAlwaysCurrent(h)
            currencyEventsCutter = CurrencyEventsCutter.make[IO](None)
            currencySnapshotCreator = CurrencySnapshotCreator
              .make[IO](currencySnapshotAcceptanceManager, None, SnapshotSizeConfig(Long.MaxValue, Long.MaxValue), currencyEventsCutter)
            currencySnapshotValidator = CurrencySnapshotValidator
              .make[IO](currencySnapshotCreator, validators.signedValidator, None, None)

            currencySnapshotContextFns = CurrencySnapshotContextFunctions.make(currencySnapshotValidator)
            globalSnapshotStateChannelManager <- GlobalSnapshotStateChannelAcceptanceManager.make[IO](None, NonNegLong(10L)).asResource
            jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync[IO].asResource
            feeCalculator = FeeCalculator.make(SortedMap.empty)
            globalSnapshotAcceptanceManager = GlobalSnapshotAcceptanceManager.make(
              BlockAcceptanceManager.make[IO](validators.blockValidator, Hasher.forKryo[IO]),
              AllowSpendBlockAcceptanceManager.make[IO](validators.allowSpendBlockValidator),
              GlobalSnapshotStateChannelEventsProcessor
                .make[IO](
                  validators.stateChannelValidator,
                  globalSnapshotStateChannelManager,
                  currencySnapshotContextFns,
                  jsonBrotliBinarySerializer,
                  feeCalculator
                ),
              Amount(0L)
            )
            globalSnapshotContextFns = GlobalSnapshotContextFunctions.make[IO](globalSnapshotAcceptanceManager)
            snapshotProcessor = {
              val addressStorage = new AddressStorage[IO] {
                def getState: IO[Map[Address, Balance]] =
                  balancesR.get

                def getBalance(address: Address): IO[balance.Balance] =
                  balancesR.get.map(b => b(address))

                def updateBalances(addressBalances: Map[Address, balance.Balance]): IO[Unit] =
                  balancesR.set(addressBalances)

                def clean: IO[Unit] = balancesR.set(Map.empty)
              }

              val blockStorage = new BlockStorage[IO](blocksR)
              val lastSnapshotStorage = LastSnapshotStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](lastSnapR)
              val lastNSnapshotStorage = LastNGlobalSnapshotStorage.make[IO](lastNSnapR)
              val lastGlobalSnapshotsSyncConfig = LastGlobalSnapshotsSyncConfig(NonNegLong(2L), PosInt.unsafeFrom(5))
              DAGSnapshotProcessor
                .make[IO](
                  lastGlobalSnapshotsSyncConfig,
                  addressStorage,
                  blockStorage,
                  lastSnapshotStorage,
                  lastNSnapshotStorage,
                  transactionStorage,
                  allowSpendStorage,
                  globalSnapshotContextFns,
                  Hasher.forKryo[IO]
                )
            }
            keys <- (
              KeyPairGenerator.makeKeyPair[IO],
              KeyPairGenerator.makeKeyPair[IO],
              KeyPairGenerator.makeKeyPair[IO]
            ).tupled.asResource
            srcKey = keys._1
            dstKey <- KeyPairGenerator.makeKeyPair[IO].asResource
            srcAddress = srcKey.getPublic.toAddress
            dstAddress = dstKey.getPublic.toAddress
            peerId = PeerId.fromId(srcKey.getPublic.toId)
          } yield
            (
              snapshotProcessor,
              sp,
              h,
              kp,
              keys,
              srcKey,
              dstKey,
              srcAddress,
              dstAddress,
              peerId,
              balancesR,
              blocksR,
              lastSnapR,
              transactionStorage,
              globalSnapshotContextFns
            )
        }
      }
    }

  val lastSnapshotHash: Hash = Hash.arbitrary.arbitrary.pureApply(Parameters.default, Seed(1234L))

  val snapshotOrdinal8: SnapshotOrdinal = SnapshotOrdinal(8L)
  val snapshotOrdinal9: SnapshotOrdinal = SnapshotOrdinal(9L)
  val snapshotOrdinal10: SnapshotOrdinal = SnapshotOrdinal(10L)
  val snapshotOrdinal11: SnapshotOrdinal = SnapshotOrdinal(11L)
  val snapshotOrdinal12: SnapshotOrdinal = SnapshotOrdinal(12L)
  val snapshotHeight6: Height = Height(6L)
  val snapshotHeight8: Height = Height(8L)
  val snapshotSubHeight0: SubHeight = SubHeight(0L)
  val snapshotSubHeight1: SubHeight = SubHeight(1L)

  def generateSnapshotBalances(addresses: Set[Address]): SortedMap[Address, Balance] =
    SortedMap.from(addresses.map(_ -> Balance(50L)))

  def generateSnapshotLastAccTxRefs(
    addressTxs: Map[Address, Hashed[Transaction]]
  ): SortedMap[Address, TransactionReference] =
    SortedMap.from(
      addressTxs.map {
        case (address, transaction) =>
          address -> TransactionReference(transaction.ordinal, transaction.hash)
      }
    )

  def generateSnapshot(peerId: PeerId): GlobalIncrementalSnapshot = {
    val emptySortedMapHash = Hash("61f6b8f9cd36ed8bf85c455875b6f4ab54d425ac7e177b0b3450a2b23950f319")

    GlobalIncrementalSnapshot(
      snapshotOrdinal10,
      snapshotHeight6,
      snapshotSubHeight0,
      lastSnapshotHash,
      SortedSet.empty,
      SortedMap.empty,
      SortedSet.empty,
      EpochProgress.MinValue,
      NonEmptyList.one(peerId),
      SnapshotTips(SortedSet.empty, SortedSet.empty),
      GlobalSnapshotStateProof(
        emptySortedMapHash,
        emptySortedMapHash,
        emptySortedMapHash,
        None,
        Some(emptySortedMapHash),
        Some(emptySortedMapHash),
        Some(emptySortedMapHash)
      ),
      Some(SortedSet.empty),
      Some(SortedMap.empty)
    )
  }

  def generateGlobalSnapshotInfo: GlobalSnapshotInfo = GlobalSnapshotInfo.empty

  test("download should happen for the base no blocks case") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            h,
            ks,
            _,
            srcKey,
            _,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            ts,
            _
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val hasher = h
        implicit val kryo = ks

        val parent1 = BlockReference(Height(4L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(5L), ProofsHash("parent2"))

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 1, txHasher = Hasher.forKryo[IO])
          block = Block(NonEmptyList.one(parent2), NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed)))
          hashedBlock <- forAsyncHasher(block, srcKey).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs.head))
          hashedSnapshot <- forAsyncHasher(
            generateSnapshot(peerId)
              .copy(
                blocks = SortedSet(BlockAsActiveTip(hashedBlock.signed, NonNegLong.MinValue)),
                tips = SnapshotTips(
                  SortedSet(DeprecatedTip(parent1, snapshotOrdinal8)),
                  SortedSet(ActiveTip(parent2, 2L, snapshotOrdinal9))
                )
              ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotInfo = GlobalSnapshotInfo(
            SortedMap.empty,
            snapshotTxRefs,
            snapshotBalances,
            SortedMap.empty,
            SortedMap.empty,
            Some(SortedMap.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty)
          )
          balancesBefore <- balancesR.get
          blocksBefore <- blocksR.toMap
          lastGlobalSnapshotBefore <- lastSnapR.get
          lastAcceptedTxRBefore <- snapshotTxRefs.map { case (addr, _) => addr }.toList
            .traverse(addr => ts.getLastProcessedTransaction(addr).map(addr -> _))
            .map(_.toMap)

          processingResult <- snapshotProcessor.process((hashedSnapshot, snapshotInfo).asLeft[Hashed[GlobalIncrementalSnapshot]])

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- ts.getState.flatMap { m =>
            m.toList.traverse { case (address, _) => ts.getLastProcessedTransaction(address).map(address -> _) }.map(_.toMap)
          }
        } yield
          expect
            .same(
              (
                processingResult,
                balancesBefore,
                balancesAfter,
                blocksBefore,
                blocksAfter,
                lastGlobalSnapshotBefore,
                lastGlobalSnapshotAfter,
                lastAcceptedTxRBefore,
                lastAcceptedTxRAfter
              ),
              (
                DownloadPerformed(
                  SnapshotReference(
                    snapshotHeight6,
                    snapshotSubHeight0,
                    snapshotOrdinal10,
                    lastSnapshotHash,
                    hashedSnapshot.hash,
                    hashedSnapshot.proofsHash
                  ),
                  Set(hashedBlock.proofsHash),
                  Set.empty
                ),
                Map.empty,
                snapshotBalances,
                Map.empty,
                Map(
                  hashedBlock.proofsHash -> MajorityBlock(
                    BlockReference(hashedBlock.height, hashedBlock.proofsHash),
                    NonNegLong.MinValue,
                    Active
                  ),
                  parent1.hash -> MajorityBlock(parent1, 0L, Deprecated),
                  parent2.hash -> MajorityBlock(parent2, 2L, Active)
                ),
                None,
                Some((hashedSnapshot, snapshotInfo)),
                snapshotTxRefs.map { case (k, _) => k -> MajorityTx(TransactionReference.empty, SnapshotOrdinal.MinValue) },
                snapshotTxRefs.map { case (k, v) => k -> MajorityTx(v, hashedSnapshot.ordinal) }
              )
            )
    }
  }

  test("download should happen for the case when there are waiting blocks in the storage") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            h,
            ks,
            keys,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            ts,
            _
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val hasher = h
        implicit val kryo = ks

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(2L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(6L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(5L), ProofsHash("parent4"))

        val hashedBlock = hashedBlockForKeyPair(keys)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 7, txHasher = Hasher.forKryo[IO]).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 1, txHasher = Hasher.forKryo[IO]).map(_.toList)

          aboveRangeBlock <- hashedBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          nonMajorityInRangeBlock <- hashedBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          majorityInRangeBlock <- hashedBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          majorityAboveRangeActiveTipBlock <- hashedBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          majorityInRangeDeprecatedTipBlock <- hashedBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          majorityInRangeActiveTipBlock <- hashedBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          hashedSnapshot <- forAsyncHasher(
            generateSnapshot(peerId).copy(
              blocks = SortedSet(BlockAsActiveTip(majorityInRangeBlock.signed, NonNegLong(1L))),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal9),
                  DeprecatedTip(
                    BlockReference(majorityInRangeDeprecatedTipBlock.height, majorityInRangeDeprecatedTipBlock.proofsHash),
                    snapshotOrdinal9
                  )
                ),
                SortedSet(
                  ActiveTip(parent1, 1L, snapshotOrdinal8),
                  ActiveTip(
                    BlockReference(majorityAboveRangeActiveTipBlock.height, majorityAboveRangeActiveTipBlock.proofsHash),
                    1L,
                    snapshotOrdinal9
                  ),
                  ActiveTip(
                    BlockReference(majorityInRangeActiveTipBlock.height, majorityInRangeActiveTipBlock.proofsHash),
                    2L,
                    snapshotOrdinal9
                  )
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotInfo = GlobalSnapshotInfo(
            SortedMap.empty,
            snapshotTxRefs,
            snapshotBalances,
            SortedMap.empty,
            SortedMap.empty,
            Some(SortedMap.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty)
          )

          // Inserting blocks in required state
          _ <- blocksR(aboveRangeBlock.proofsHash).set(WaitingBlock(aboveRangeBlock.signed).some)
          _ <- blocksR(nonMajorityInRangeBlock.proofsHash).set(WaitingBlock(nonMajorityInRangeBlock.signed).some)
          _ <- blocksR(majorityInRangeBlock.proofsHash).set(WaitingBlock(majorityInRangeBlock.signed).some)
          _ <- blocksR(majorityAboveRangeActiveTipBlock.proofsHash).set(
            WaitingBlock(majorityAboveRangeActiveTipBlock.signed).some
          )
          _ <- blocksR(majorityInRangeDeprecatedTipBlock.proofsHash).set(
            WaitingBlock(majorityInRangeDeprecatedTipBlock.signed).some
          )
          _ <- blocksR(majorityInRangeActiveTipBlock.proofsHash).set(
            WaitingBlock(majorityInRangeActiveTipBlock.signed).some
          )

          processingResult <- snapshotProcessor.process((hashedSnapshot, snapshotInfo).asLeft[Hashed[GlobalIncrementalSnapshot]])

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- ts.getState.flatMap { m =>
            m.toList.traverse { case (address, _) => ts.getLastProcessedTransaction(address).map(address -> _) }.map(_.toMap)
          }
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              DownloadPerformed(
                SnapshotReference(
                  snapshotHeight6,
                  snapshotSubHeight0,
                  snapshotOrdinal10,
                  lastSnapshotHash,
                  hashedSnapshot.hash,
                  hashedSnapshot.proofsHash
                ),
                Set(majorityInRangeBlock.proofsHash),
                Set(nonMajorityInRangeBlock.proofsHash)
              ),
              Map(
                aboveRangeBlock.proofsHash -> WaitingBlock(aboveRangeBlock.signed),
                majorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                majorityAboveRangeActiveTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityAboveRangeActiveTipBlock.height, majorityAboveRangeActiveTipBlock.proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                majorityInRangeDeprecatedTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeDeprecatedTipBlock.height, majorityInRangeDeprecatedTipBlock.proofsHash),
                  0L,
                  Deprecated
                ),
                majorityInRangeActiveTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeActiveTipBlock.height, majorityInRangeActiveTipBlock.proofsHash),
                  2L,
                  Active
                ),
                parent1.hash -> MajorityBlock(parent1, 1L, Active),
                parent3.hash -> MajorityBlock(parent3, 0L, Deprecated)
              ),
              snapshotBalances,
              Some((hashedSnapshot, snapshotInfo)),
              snapshotTxRefs.map { case (k, v) => k -> MajorityTx(v, hashedSnapshot.ordinal) }
            )
          )
    }
  }

  test("download should happen for the case when there are postponed blocks in the storage") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            h,
            ks,
            keys,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            ts,
            _
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val hasher = h
        implicit val kryo = ks

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(2L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(6L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(5L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(8L), ProofsHash("parent5"))

        val hashedBlock = hashedBlockForKeyPair(keys)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 9, txHasher = Hasher.forKryo[IO]).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 1, txHasher = Hasher.forKryo[IO]).map(_.toList)

          aboveRangeBlock <- hashedBlock(
            NonEmptyList.one(parent5),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(7).signed))
          )
          aboveRangeRelatedToTipBlock <- hashedBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(7).signed))
          )
          nonMajorityInRangeBlock <- hashedBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          majorityInRangeBlock <- hashedBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          aboveRangeRelatedBlock <- hashedBlock(
            NonEmptyList.one(BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash)),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(8).signed))
          )
          majorityAboveRangeActiveTipBlock <- hashedBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          majorityInRangeDeprecatedTipBlock <- hashedBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          majorityInRangeActiveTipBlock <- hashedBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          hashedSnapshot <- forAsyncHasher(
            generateSnapshot(peerId).copy(
              blocks = SortedSet(BlockAsActiveTip(majorityInRangeBlock.signed, NonNegLong(1L))),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal9),
                  DeprecatedTip(
                    BlockReference(majorityInRangeDeprecatedTipBlock.height, majorityInRangeDeprecatedTipBlock.proofsHash),
                    snapshotOrdinal9
                  )
                ),
                SortedSet(
                  ActiveTip(parent1, 1L, snapshotOrdinal8),
                  ActiveTip(
                    BlockReference(majorityAboveRangeActiveTipBlock.height, majorityAboveRangeActiveTipBlock.proofsHash),
                    1L,
                    snapshotOrdinal9
                  ),
                  ActiveTip(
                    BlockReference(majorityInRangeActiveTipBlock.height, majorityInRangeActiveTipBlock.proofsHash),
                    2L,
                    snapshotOrdinal9
                  )
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotInfo = GlobalSnapshotInfo(
            SortedMap.empty,
            snapshotTxRefs,
            snapshotBalances,
            SortedMap.empty,
            SortedMap.empty,
            Some(SortedMap.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty)
          )

          // Inserting blocks in required state
          _ <- blocksR(aboveRangeBlock.proofsHash).set(PostponedBlock(aboveRangeBlock.signed).some)
          _ <- blocksR(aboveRangeRelatedToTipBlock.proofsHash).set(
            PostponedBlock(aboveRangeRelatedToTipBlock.signed).some
          )
          _ <- blocksR(nonMajorityInRangeBlock.proofsHash).set(
            PostponedBlock(nonMajorityInRangeBlock.signed).some
          )
          _ <- blocksR(majorityInRangeBlock.proofsHash).set(PostponedBlock(majorityInRangeBlock.signed).some)
          _ <- blocksR(aboveRangeRelatedBlock.proofsHash).set(PostponedBlock(aboveRangeRelatedBlock.signed).some)
          _ <- blocksR(majorityAboveRangeActiveTipBlock.proofsHash).set(
            PostponedBlock(majorityAboveRangeActiveTipBlock.signed).some
          )
          _ <- blocksR(majorityInRangeDeprecatedTipBlock.proofsHash).set(
            PostponedBlock(majorityInRangeDeprecatedTipBlock.signed).some
          )
          _ <- blocksR(majorityInRangeActiveTipBlock.proofsHash).set(
            PostponedBlock(majorityInRangeActiveTipBlock.signed).some
          )

          processingResult <- snapshotProcessor.process((hashedSnapshot, snapshotInfo).asLeft[Hashed[GlobalIncrementalSnapshot]])

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- ts.getState.flatMap { m =>
            m.toList.traverse { case (address, _) => ts.getLastProcessedTransaction(address).map(address -> _) }.map(_.toMap)
          }
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              DownloadPerformed(
                SnapshotReference(
                  snapshotHeight6,
                  snapshotSubHeight0,
                  snapshotOrdinal10,
                  lastSnapshotHash,
                  hashedSnapshot.hash,
                  hashedSnapshot.proofsHash
                ),
                Set(majorityInRangeBlock.proofsHash),
                Set(nonMajorityInRangeBlock.proofsHash)
              ),
              Map(
                aboveRangeBlock.proofsHash -> PostponedBlock(aboveRangeBlock.signed),
                aboveRangeRelatedToTipBlock.proofsHash -> WaitingBlock(aboveRangeRelatedToTipBlock.signed),
                majorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                aboveRangeRelatedBlock.proofsHash -> WaitingBlock(aboveRangeRelatedBlock.signed),
                majorityAboveRangeActiveTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityAboveRangeActiveTipBlock.height, majorityAboveRangeActiveTipBlock.proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                majorityInRangeDeprecatedTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeDeprecatedTipBlock.height, majorityInRangeDeprecatedTipBlock.proofsHash),
                  0L,
                  Deprecated
                ),
                majorityInRangeActiveTipBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeActiveTipBlock.height, majorityInRangeActiveTipBlock.proofsHash),
                  2L,
                  Active
                ),
                parent1.hash -> MajorityBlock(parent1, 1L, Active),
                parent3.hash -> MajorityBlock(parent3, 0L, Deprecated)
              ),
              snapshotBalances,
              Some((hashedSnapshot, snapshotInfo)),
              snapshotTxRefs.map { case (k, v) => k -> MajorityTx(v, hashedSnapshot.ordinal) }
            )
          )
    }
  }

  test("alignment at same height should happen when snapshot with new ordinal but known height is processed") {
    testResources.use {
      case (snapshotProcessor, sp, h, _, _, srcKey, _, _, _, peerId, balancesR, blocksR, lastSnapR, ts, _) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val hasher = h

        for {
          hashedLastSnapshot <- forAsyncHasher(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncHasher(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal11,
              subHeight = snapshotSubHeight1,
              lastSnapshotHash = hashedLastSnapshot.hash
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set((hashedLastSnapshot, GlobalSnapshotInfo.empty).some)

          processingResult <- snapshotProcessor.process(
            hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
          )

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- ts.getState.flatMap { m =>
            m.toList.traverse { case (address, _) => ts.getLastProcessedTransaction(address).map(address -> _) }.map(_.toMap)
          }
        } yield
          expect.same(
            (processingResult, balancesAfter, blocksAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              Aligned(
                SnapshotReference(
                  snapshotHeight6,
                  snapshotSubHeight1,
                  snapshotOrdinal11,
                  hashedLastSnapshot.hash,
                  hashedNextSnapshot.hash,
                  hashedNextSnapshot.proofsHash
                ),
                Set.empty
              ),
              Map.empty,
              Map.empty,
              Some((hashedNextSnapshot, GlobalSnapshotInfo.empty)),
              Map.empty
            )
          )
    }
  }

  test("alignment at new height should happen when node is aligned with the majority in processed snapshot") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            h,
            ks,
            keys,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            ts,
            _
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryo = ks
        val currentHasher = h
        val txHasher = Hasher.forKryo[IO]

        val parent1 = BlockReference(Height(6L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(7L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(8L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(9L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(9L), ProofsHash("parent5"))

        val parent12 = BlockReference(Height(6L), ProofsHash("parent12"))
        val parent22 = BlockReference(Height(7L), ProofsHash("parent22"))
        val parent32 = BlockReference(Height(8L), ProofsHash("parent32"))
        val parent42 = BlockReference(Height(9L), ProofsHash("parent42"))
        val parent52 = BlockReference(Height(9L), ProofsHash("parent52"))

        val hashedBlock = hashedBlockForKeyPair(keys)(sp, currentHasher)

        val snapshotBalances = generateSnapshotBalances(Set(srcAddress))

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 5, txHasher = Hasher.forKryo[IO]).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 2, txHasher = Hasher.forKryo[IO]).map(_.toList)

          waitingInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          postponedInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(1).signed))
          )
          majorityInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed))
          )
          inRangeRelatedTxnBlock <- hashedBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          aboveRangeAcceptedBlock <- hashedBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          aboveRangeMajorityBlock <- hashedBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          waitingAboveRangeBlock <- hashedBlock(
            NonEmptyList.of(parent4, parent42),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )
          postponedAboveRangeRelatedToTipBlock <- hashedBlock(
            NonEmptyList.of(parent4, parent42),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          postponedAboveRangeRelatedTxnBlock <- hashedBlock(
            NonEmptyList.of(parent5, parent52),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )

          _ <- {
            implicit val hasher = txHasher

            majorityInRangeBlock.signed.value.transactions.toNonEmptyList.traverse(_.toHashed.flatMap(ts.accept)) >>
              aboveRangeMajorityBlock.signed.value.transactions.toNonEmptyList.traverse(_.toHashed.flatMap(ts.accept)) >>
              aboveRangeAcceptedBlock.signed.value.transactions.toNonEmptyList.traverse(_.toHashed.flatMap(ts.accept))
          }

          lastSnapshotInfo = GlobalSnapshotInfo(
            SortedMap.empty,
            SortedMap.empty,
            snapshotBalances,
            SortedMap.empty,
            SortedMap.empty,
            Some(SortedMap.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty)
          )
          lastSnapshotStateProof <- {
            implicit val hasher = currentHasher

            lastSnapshotInfo.stateProof(snapshotOrdinal10)
          }
          hashedLastSnapshot <- {
            implicit val hasher = currentHasher

            forAsyncHasher(
              generateSnapshot(peerId).copy(
                tips = SnapshotTips(
                  SortedSet(
                    DeprecatedTip(parent1, snapshotOrdinal9),
                    DeprecatedTip(parent2, snapshotOrdinal9),
                    DeprecatedTip(parent12, snapshotOrdinal9),
                    DeprecatedTip(parent22, snapshotOrdinal9)
                  ),
                  SortedSet(
                    ActiveTip(parent3, 1L, snapshotOrdinal8),
                    ActiveTip(parent4, 1L, snapshotOrdinal9),
                    ActiveTip(parent32, 1L, snapshotOrdinal8),
                    ActiveTip(parent42, 1L, snapshotOrdinal9),
                    ActiveTip(parent52, 1L, snapshotOrdinal9)
                  )
                ),
                stateProof = lastSnapshotStateProof
              ),
              srcKey
            ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          }
          newSnapshotInfo = {
            val balances = SortedMap(srcAddress -> Balance(48L), dstAddress -> Balance(2L))
            val lastTxRefs = SortedMap(srcAddress -> TransactionReference.of(correctTxs(1)), dstAddress -> TransactionReference.empty)

            lastSnapshotInfo.copy(
              lastTxRefs = lastTxRefs,
              balances = balances
            )
          }
          newSnapshotInfoStateProof <- {
            implicit val hasher = currentHasher

            newSnapshotInfo.stateProof(snapshotOrdinal11)
          }
          hashedNextSnapshot <- {
            implicit val hasher = currentHasher

            forAsyncHasher(
              generateSnapshot(peerId).copy(
                ordinal = snapshotOrdinal11,
                height = snapshotHeight8,
                lastSnapshotHash = hashedLastSnapshot.hash,
                blocks = SortedSet(BlockAsActiveTip(majorityInRangeBlock.signed, 1L), BlockAsActiveTip(aboveRangeMajorityBlock.signed, 2L)),
                tips = SnapshotTips(
                  SortedSet(
                    DeprecatedTip(parent3, snapshotOrdinal11)
                  ),
                  SortedSet(
                    ActiveTip(parent4, 1L, snapshotOrdinal9),
                    ActiveTip(parent32, 1L, snapshotOrdinal11),
                    ActiveTip(parent42, 1L, snapshotOrdinal9),
                    ActiveTip(parent52, 1L, snapshotOrdinal9)
                  )
                ),
                stateProof = newSnapshotInfoStateProof
              ),
              srcKey
            ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          }
          _ <- lastSnapR.set((hashedLastSnapshot, lastSnapshotInfo).some)
          // Inserting tips
          _ <- blocksR(parent1.hash).set(MajorityBlock(parent1, 2L, Deprecated).some)
          _ <- blocksR(parent2.hash).set(MajorityBlock(parent2, 2L, Deprecated).some)
          _ <- blocksR(parent3.hash).set(MajorityBlock(parent3, 1L, Active).some)
          _ <- blocksR(parent4.hash).set(MajorityBlock(parent4, 1L, Active).some)

          _ <- blocksR(parent12.hash).set(MajorityBlock(parent12, 2L, Deprecated).some)
          _ <- blocksR(parent22.hash).set(MajorityBlock(parent22, 2L, Deprecated).some)
          _ <- blocksR(parent32.hash).set(MajorityBlock(parent32, 1L, Active).some)
          _ <- blocksR(parent42.hash).set(MajorityBlock(parent42, 1L, Active).some)
          _ <- blocksR(parent52.hash).set(MajorityBlock(parent52, 1L, Active).some)
          // Inserting blocks in required state
          _ <- blocksR(waitingInRangeBlock.proofsHash).set(WaitingBlock(waitingInRangeBlock.signed).some)
          _ <- blocksR(postponedInRangeBlock.proofsHash).set(PostponedBlock(postponedInRangeBlock.signed).some)
          _ <- blocksR(majorityInRangeBlock.proofsHash).set(AcceptedBlock(majorityInRangeBlock).some)
          _ <- blocksR(inRangeRelatedTxnBlock.proofsHash).set(PostponedBlock(inRangeRelatedTxnBlock.signed).some)
          _ <- blocksR(aboveRangeAcceptedBlock.proofsHash).set(AcceptedBlock(aboveRangeAcceptedBlock).some)
          _ <- blocksR(aboveRangeMajorityBlock.proofsHash).set(AcceptedBlock(aboveRangeMajorityBlock).some)
          _ <- blocksR(waitingAboveRangeBlock.proofsHash).set(WaitingBlock(waitingAboveRangeBlock.signed).some)
          _ <- blocksR(postponedAboveRangeRelatedToTipBlock.proofsHash).set(
            PostponedBlock(postponedAboveRangeRelatedToTipBlock.signed).some
          )
          _ <- blocksR(postponedAboveRangeRelatedTxnBlock.proofsHash).set(
            PostponedBlock(postponedAboveRangeRelatedTxnBlock.signed).some
          )

          processingResult <- {
            implicit val hasher = currentHasher

            snapshotProcessor.process(
              hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]
            )
          }

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- ts.getState.flatMap { m =>
            m.toList.traverse { case (address, _) => ts.getLastProcessedTransaction(address).map(address -> _) }.map(_.toMap)
          }
          lastTx <- {
            implicit val hasher = txHasher

            aboveRangeAcceptedBlock.signed.value.transactions.head.toHashed
          }
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              Aligned(
                SnapshotReference(
                  snapshotHeight8,
                  snapshotSubHeight0,
                  snapshotOrdinal11,
                  hashedLastSnapshot.hash,
                  hashedNextSnapshot.hash,
                  hashedNextSnapshot.proofsHash
                ),
                Set(waitingInRangeBlock.proofsHash, postponedInRangeBlock.proofsHash, inRangeRelatedTxnBlock.proofsHash)
              ),
              Map(
                parent3.hash -> MajorityBlock(parent3, 1L, Deprecated),
                parent4.hash -> MajorityBlock(parent4, 1L, Active),
                parent32.hash -> MajorityBlock(parent32, 1L, Active),
                parent42.hash -> MajorityBlock(parent42, 1L, Active),
                parent52.hash -> MajorityBlock(parent52, 1L, Active),
                majorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash),
                  1L,
                  Active
                ),
                aboveRangeAcceptedBlock.proofsHash -> AcceptedBlock(aboveRangeAcceptedBlock),
                aboveRangeMajorityBlock.proofsHash -> MajorityBlock(
                  BlockReference(aboveRangeMajorityBlock.height, aboveRangeMajorityBlock.proofsHash),
                  2L,
                  Active
                ),
                waitingAboveRangeBlock.proofsHash -> WaitingBlock(waitingAboveRangeBlock.signed),
                // inRangeRelatedTxnBlock.proofsHash -> WaitingBlock(inRangeRelatedTxnBlock.signed),
                postponedAboveRangeRelatedToTipBlock.proofsHash -> PostponedBlock(
                  postponedAboveRangeRelatedToTipBlock.signed
                ),
                postponedAboveRangeRelatedTxnBlock.proofsHash -> WaitingBlock(
                  postponedAboveRangeRelatedTxnBlock.signed
                )
              ),
              Map.empty,
              Some((hashedNextSnapshot, newSnapshotInfo)),
              Map(srcAddress -> AcceptedTx(lastTx), dstAddress -> MajorityTx(TransactionReference.empty, snapshotOrdinal11))
            )
          )
    }
  }

  test("redownload should happen when node is misaligned with majority in processed snapshot") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            h,
            ks,
            keys,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            ts,
            _
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val hasher = h
        implicit val kryo = ks

        val parent1 = BlockReference(Height(6L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(7L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(8L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(9L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(9L), ProofsHash("parent5"))

        val parent12 = BlockReference(Height(6L), ProofsHash("parent12"))
        val parent22 = BlockReference(Height(7L), ProofsHash("parent22"))
        val parent32 = BlockReference(Height(8L), ProofsHash("parent32"))
        val parent42 = BlockReference(Height(9L), ProofsHash("parent42"))
        val parent52 = BlockReference(Height(9L), ProofsHash("parent52"))

        val hashedBlock = hashedBlockForKeyPair(keys)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 9, txHasher = Hasher.forKryo[IO]).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 4, txHasher = Hasher.forKryo[IO]).map(_.toList)

          waitingInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(2).signed))
          )
          postponedInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(3).signed))
          )
          waitingMajorityInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          postponedMajorityInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent1, parent12),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          acceptedMajorityInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed))
          )
          majorityUnknownBlock <- hashedBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          acceptedNonMajorityInRangeBlock <- hashedBlock(
            NonEmptyList.of(parent2, parent22),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          aboveRangeAcceptedBlock <- hashedBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(1).signed))
          )
          aboveRangeAcceptedMajorityBlock <- hashedBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          aboveRangeUnknownMajorityBlock <- hashedBlock(
            NonEmptyList.of(parent3, parent32),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )
          waitingAboveRangeBlock <- hashedBlock(
            NonEmptyList.of(parent4, parent42),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          postponedAboveRangeBlock <- hashedBlock(
            NonEmptyList.of(parent4, parent42),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          postponedAboveRangeNotRelatedBlock <- hashedBlock(
            NonEmptyList.of(parent5, parent52),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(8).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          lastSnapshotInfo = GlobalSnapshotInfo(
            SortedMap.empty,
            SortedMap.empty,
            snapshotBalances,
            SortedMap.empty,
            SortedMap.empty,
            Some(SortedMap.empty),
            Some(SortedMap.empty),
            Some(SortedMap.empty)
          )
          lastSnapshotInfoStateProof <- lastSnapshotInfo.stateProof(snapshotOrdinal10)
          hashedLastSnapshot <- forAsyncHasher(
            generateSnapshot(peerId).copy(
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent1, snapshotOrdinal9),
                  DeprecatedTip(parent2, snapshotOrdinal9),
                  DeprecatedTip(parent12, snapshotOrdinal9),
                  DeprecatedTip(parent22, snapshotOrdinal9)
                ),
                SortedSet(
                  ActiveTip(parent3, 1L, snapshotOrdinal8),
                  ActiveTip(parent4, 1L, snapshotOrdinal9),
                  ActiveTip(parent32, 1L, snapshotOrdinal8),
                  ActiveTip(parent42, 1L, snapshotOrdinal9),
                  ActiveTip(parent52, 1L, snapshotOrdinal9)
                )
              ),
              stateProof = lastSnapshotInfoStateProof
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          newSnapshotInfo = {
            val balances = SortedMap(srcAddress -> Balance(44L), dstAddress -> Balance(6L))
            val lastTxRefs = SortedMap(srcAddress -> TransactionReference.of(correctTxs(5)), dstAddress -> TransactionReference.empty)

            lastSnapshotInfo.copy(
              lastTxRefs = lastTxRefs,
              balances = balances
            )
          }
          newSnapshotInfoStateProof <- newSnapshotInfo.stateProof(snapshotOrdinal11)
          hashedNextSnapshot <- forAsyncHasher(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal11,
              height = snapshotHeight8,
              lastSnapshotHash = hashedLastSnapshot.hash,
              blocks = SortedSet(
                BlockAsActiveTip(waitingMajorityInRangeBlock.signed, 1L),
                BlockAsActiveTip(postponedMajorityInRangeBlock.signed, 1L),
                BlockAsActiveTip(acceptedMajorityInRangeBlock.signed, 2L),
                BlockAsActiveTip(majorityUnknownBlock.signed, 1L),
                BlockAsActiveTip(aboveRangeAcceptedMajorityBlock.signed, 0L),
                BlockAsActiveTip(aboveRangeUnknownMajorityBlock.signed, 0L)
              ),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal11)
                ),
                SortedSet(
                  ActiveTip(parent4, 1L, snapshotOrdinal9),
                  ActiveTip(parent32, 1L, snapshotOrdinal8),
                  ActiveTip(parent42, 1L, snapshotOrdinal9),
                  ActiveTip(parent52, 1L, snapshotOrdinal9)
                )
              ),
              stateProof = newSnapshotInfoStateProof
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set((hashedLastSnapshot, lastSnapshotInfo).some)
          // Inserting tips
          _ <- blocksR(parent1.hash).set(MajorityBlock(parent1, 2L, Deprecated).some)
          _ <- blocksR(parent2.hash).set(MajorityBlock(parent2, 2L, Deprecated).some)
          _ <- blocksR(parent3.hash).set(MajorityBlock(parent3, 1L, Active).some)
          _ <- blocksR(parent4.hash).set(MajorityBlock(parent4, 1L, Active).some)

          _ <- blocksR(parent12.hash).set(MajorityBlock(parent12, 2L, Deprecated).some)
          _ <- blocksR(parent22.hash).set(MajorityBlock(parent22, 2L, Deprecated).some)
          _ <- blocksR(parent32.hash).set(MajorityBlock(parent32, 2L, Active).some)
          _ <- blocksR(parent42.hash).set(MajorityBlock(parent42, 1L, Active).some)
          _ <- blocksR(parent52.hash).set(MajorityBlock(parent52, 1L, Active).some)
          // Inserting blocks in required state
          _ <- blocksR(waitingInRangeBlock.proofsHash).set(WaitingBlock(waitingInRangeBlock.signed).some)
          _ <- blocksR(postponedInRangeBlock.proofsHash).set(PostponedBlock(postponedInRangeBlock.signed).some)
          _ <- blocksR(waitingMajorityInRangeBlock.proofsHash).set(
            WaitingBlock(waitingMajorityInRangeBlock.signed).some
          )
          _ <- blocksR(postponedMajorityInRangeBlock.proofsHash).set(
            PostponedBlock(postponedMajorityInRangeBlock.signed).some
          )
          _ <- blocksR(acceptedMajorityInRangeBlock.proofsHash).set(
            AcceptedBlock(acceptedMajorityInRangeBlock).some
          )
          _ <- blocksR(acceptedNonMajorityInRangeBlock.proofsHash).set(
            AcceptedBlock(acceptedNonMajorityInRangeBlock).some
          )
          _ <- blocksR(aboveRangeAcceptedBlock.proofsHash).set(AcceptedBlock(aboveRangeAcceptedBlock).some)
          _ <- blocksR(aboveRangeAcceptedMajorityBlock.proofsHash).set(
            AcceptedBlock(aboveRangeAcceptedMajorityBlock).some
          )
          _ <- blocksR(waitingAboveRangeBlock.proofsHash).set(WaitingBlock(waitingAboveRangeBlock.signed).some)
          _ <- blocksR(postponedAboveRangeBlock.proofsHash).set(
            PostponedBlock(postponedAboveRangeBlock.signed).some
          )
          _ <- blocksR(postponedAboveRangeNotRelatedBlock.proofsHash).set(
            PostponedBlock(postponedAboveRangeNotRelatedBlock.signed).some
          )

          processingResult <- snapshotProcessor.process(hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- ts.getState.flatMap { m =>
            m.toList.traverse { case (address, _) => ts.getLastProcessedTransaction(address).map(address -> _) }.map(_.toMap)
          }
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              RedownloadPerformed(
                SnapshotReference(
                  snapshotHeight8,
                  snapshotSubHeight0,
                  snapshotOrdinal11,
                  hashedLastSnapshot.hash,
                  hashedNextSnapshot.hash,
                  hashedNextSnapshot.proofsHash
                ),
                addedBlocks = Set(
                  waitingMajorityInRangeBlock.proofsHash,
                  postponedMajorityInRangeBlock.proofsHash,
                  majorityUnknownBlock.proofsHash,
                  aboveRangeUnknownMajorityBlock.proofsHash
                ),
                removedBlocks = Set(acceptedNonMajorityInRangeBlock.proofsHash),
                removedObsoleteBlocks = Set(waitingInRangeBlock.proofsHash, postponedInRangeBlock.proofsHash)
              ),
              Map(
                parent3.hash -> MajorityBlock(parent3, 2L, Deprecated),
                parent4.hash -> MajorityBlock(parent4, 1L, Active),
                parent32.hash -> MajorityBlock(parent32, 3L, Active),
                parent42.hash -> MajorityBlock(parent42, 1L, Active),
                parent52.hash -> MajorityBlock(parent52, 1L, Active),
                waitingMajorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(waitingMajorityInRangeBlock.height, waitingMajorityInRangeBlock.proofsHash),
                  1L,
                  Active
                ),
                postponedMajorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(postponedMajorityInRangeBlock.height, postponedMajorityInRangeBlock.proofsHash),
                  1L,
                  Active
                ),
                acceptedMajorityInRangeBlock.proofsHash -> MajorityBlock(
                  BlockReference(acceptedMajorityInRangeBlock.height, acceptedMajorityInRangeBlock.proofsHash),
                  2L,
                  Active
                ),
                majorityUnknownBlock.proofsHash -> MajorityBlock(
                  BlockReference(majorityUnknownBlock.height, majorityUnknownBlock.proofsHash),
                  1L,
                  Active
                ),
                aboveRangeAcceptedBlock.proofsHash -> WaitingBlock(aboveRangeAcceptedBlock.signed),
                aboveRangeAcceptedMajorityBlock.proofsHash -> MajorityBlock(
                  BlockReference(aboveRangeAcceptedMajorityBlock.height, aboveRangeAcceptedMajorityBlock.proofsHash),
                  0L,
                  Active
                ),
                aboveRangeUnknownMajorityBlock.proofsHash -> MajorityBlock(
                  BlockReference(aboveRangeUnknownMajorityBlock.height, aboveRangeUnknownMajorityBlock.proofsHash),
                  0L,
                  Active
                ),
                waitingAboveRangeBlock.proofsHash -> WaitingBlock(waitingAboveRangeBlock.signed),
                postponedAboveRangeBlock.proofsHash -> WaitingBlock(postponedAboveRangeBlock.signed),
                postponedAboveRangeNotRelatedBlock.proofsHash -> PostponedBlock(
                  postponedAboveRangeNotRelatedBlock.signed
                )
              ),
              newSnapshotInfo.balances,
              Some((hashedNextSnapshot, newSnapshotInfo)),
              snapshotTxRefs.map { case (k, v) => k -> MajorityTx(v, hashedNextSnapshot.ordinal) } + (dstAddress -> MajorityTx(
                TransactionReference.empty,
                hashedNextSnapshot.ordinal
              ))
            )
          )
    }
  }

  test("snapshot should be ignored when a snapshot pushed for processing is not a next one") {
    testResources.use {
      case (snapshotProcessor, sp, h, _, _, srcKey, _, _, _, peerId, _, _, lastSnapR, _, _) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val hasher = h

        for {
          hashedLastSnapshot <- forAsyncHasher(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncHasher(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal12,
              lastSnapshotHash = hashedLastSnapshot.hash
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set((hashedLastSnapshot, GlobalSnapshotInfo.empty).some)
          processingResult <- snapshotProcessor
            .process(hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])
            .map(_.asRight[Throwable])
            .handleErrorWith(e => IO.pure(e.asLeft[SnapshotProcessingResult]))
          lastSnapshotAfter <- lastSnapR.get.map(_.get)
        } yield
          expect.same(
            (processingResult, lastSnapshotAfter),
            (
              Right(
                SnapshotIgnored(
                  SnapshotReference.fromHashedSnapshot(hashedNextSnapshot)
                )
              ),
              (hashedLastSnapshot, GlobalSnapshotInfo.empty)
            )
          )
    }
  }

  test("error should be thrown when the tips get misaligned") {
    testResources.use {
      case (snapshotProcessor, sp, h, _, _, srcKey, _, _, _, peerId, _, blocksR, lastSnapR, _, _) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val hasher = h

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(9L), ProofsHash("parent2"))

        for {
          hashedLastSnapshot <- forAsyncHasher(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncHasher(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal11,
              height = snapshotHeight8,
              lastSnapshotHash = hashedLastSnapshot.hash,
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent1, snapshotOrdinal11)
                ),
                SortedSet(
                  ActiveTip(parent2, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set((hashedLastSnapshot, GlobalSnapshotInfo.empty).some)
          // Inserting tips
          _ <- blocksR(parent2.hash).set(MajorityBlock(parent2, 1L, Active).some)

          processingResult <- snapshotProcessor
            .process(hashedNextSnapshot.asRight[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)])
            .map(_.asRight[Throwable])
            .handleErrorWith(e => IO.pure(e.asLeft[SnapshotProcessingResult]))
          lastSnapshotAfter <- lastSnapR.get.map(_.get)
        } yield
          expect.same(
            (processingResult, lastSnapshotAfter),
            (
              Left(TipsGotMisaligned(Set(parent1.hash), Set.empty)),
              (hashedLastSnapshot, GlobalSnapshotInfo.empty)
            )
          )
    }
  }

  private def hashedBlockForKeyPair(
    keys: (KeyPair, KeyPair, KeyPair)
  )(implicit sc: SecurityProvider[IO], h: Hasher[IO]) =
    (parent: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[Transaction]]) =>
      (
        forAsyncHasher(Block(parent, transactions), keys._1),
        forAsyncHasher(Block(parent, transactions), keys._2),
        forAsyncHasher(Block(parent, transactions), keys._3)
      ).tupled.flatMap {
        case (b1, b2, b3) =>
          b1.addProof(b2.proofs.head).addProof(b3.proofs.head).toHashedWithSignatureCheck.map(_.toOption.get)
      }
}
