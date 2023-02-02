package org.tessellation.dag.l1.domain.snapshot.programs

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect._
import cats.effect.std.Random
import cats.syntax.either._
import cats.syntax.option._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.l1.Main
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.block.BlockStorage._
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage.{LastTransactionReferenceState, Majority}
import org.tessellation.dag.transaction.TransactionGenerator
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.collection.MapRefUtils._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.block.DAGBlock
import org.tessellation.schema.epoch.EpochProgress
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction._
import org.tessellation.sdk.sdkKryoRegistrar
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.forAsyncKryo
import org.tessellation.security.{Hashed, KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.concurrent.SignallingRef
import io.chrisdavenport.mapref.MapRef
import org.scalacheck.Gen.Parameters
import org.scalacheck.rng.Seed
import weaver.SimpleIOSuite

object SnapshotProcessorSuite extends SimpleIOSuite with TransactionGenerator {

  type TestResources = (
    SnapshotProcessor[IO],
    SecurityProvider[IO],
    KryoSerializer[IO],
    KeyPair,
    KeyPair,
    Address,
    Address,
    PeerId,
    Ref[IO, Map[Address, Balance]],
    MapRef[IO, ProofsHash, Option[StoredBlock]],
    Ref[IO, Option[Hashed[GlobalSnapshot]]],
    MapRef[IO, Address, Option[LastTransactionReferenceState]]
  )

  def testResources: Resource[IO, TestResources] =
    SecurityProvider.forAsync[IO].flatMap { implicit sp =>
      KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ sdkKryoRegistrar).flatMap { implicit kp =>
        Random.scalaUtilRandom[IO].asResource.flatMap { implicit random =>
          for {
            balancesR <- Ref.of[IO, Map[Address, Balance]](Map.empty).asResource
            blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]().asResource
            lastSnapR <- SignallingRef.of[IO, Option[Hashed[GlobalSnapshot]]](None).asResource
            lastAccTxR <- MapRef.ofConcurrentHashMap[IO, Address, LastTransactionReferenceState]().asResource
            waitingTxsR <- MapRef.ofConcurrentHashMap[IO, Address, NonEmptySet[Hashed[DAGTransaction]]]().asResource
            snapshotProcessor = {
              val addressStorage = new AddressStorage[IO] {
                def getBalance(address: Address): IO[balance.Balance] =
                  balancesR.get.map(b => b(address))

                def updateBalances(addressBalances: Map[Address, balance.Balance]): IO[Unit] =
                  balancesR.set(addressBalances)

                def clean: IO[Unit] = balancesR.set(Map.empty)
              }

              val blockStorage = new BlockStorage[IO](blocksR)
              val lastGlobalSnapshotStorage = LastGlobalSnapshotStorage.make(lastSnapR)
              val transactionStorage = new TransactionStorage[IO](lastAccTxR, waitingTxsR)

              SnapshotProcessor
                .make[IO](addressStorage, blockStorage, lastGlobalSnapshotStorage, transactionStorage)
            }
            srcKey <- KeyPairGenerator.makeKeyPair[IO].asResource
            dstKey <- KeyPairGenerator.makeKeyPair[IO].asResource
            srcAddress = srcKey.getPublic.toAddress
            dstAddress = dstKey.getPublic.toAddress
            peerId = PeerId.fromId(srcKey.getPublic.toId)
          } yield
            (
              snapshotProcessor,
              sp,
              kp,
              srcKey,
              dstKey,
              srcAddress,
              dstAddress,
              peerId,
              balancesR,
              blocksR,
              lastSnapR,
              lastAccTxR
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

  def generateSnapshot(peerId: PeerId): GlobalSnapshot =
    GlobalSnapshot(
      snapshotOrdinal10,
      snapshotHeight6,
      snapshotSubHeight0,
      lastSnapshotHash,
      SortedSet.empty,
      SortedMap.empty,
      SortedSet.empty,
      EpochProgress.MinValue,
      NonEmptyList.one(peerId),
      GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, SortedMap.empty),
      SnapshotTips(SortedSet.empty, SortedSet.empty)
    )

  test("download should happen for the base no blocks case") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            srcKey,
            _,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(4L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(5L), ProofsHash("parent2"))

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 1)
          block = DAGBlock(NonEmptyList.one(parent2), NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed)))
          hashedBlock <- forAsyncKryo(block, srcKey).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs.head))
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId)
              .copy(
                blocks = SortedSet(BlockAsActiveTip(hashedBlock.signed, NonNegLong.MinValue)),
                info = GlobalSnapshotInfo(SortedMap.empty, snapshotTxRefs, snapshotBalances),
                tips = SnapshotTips(
                  SortedSet(DeprecatedTip(parent1, snapshotOrdinal8)),
                  SortedSet(ActiveTip(parent2, 2L, snapshotOrdinal9))
                )
              ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          balancesBefore <- balancesR.get
          blocksBefore <- blocksR.toMap
          lastGlobalSnapshotBefore <- lastSnapR.get
          lastAcceptedTxRBefore <- lastAccTxR.toMap

          processingResult <- snapshotProcessor.process(hashedSnapshot)

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
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
                  GlobalSnapshotReference(
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
                Some(hashedSnapshot),
                Map.empty,
                snapshotTxRefs.map { case (k, v) => k -> Majority(v) }
              )
            )
    }
  }

  test("download should happen for the case when there are waiting blocks in the storage") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(2L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(6L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(5L), ProofsHash("parent4"))

        val hashedDAGBlock = hashedDAGBlockForKeyPair(srcKey)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 7).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 1).map(_.toList)

          aboveRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          nonMajorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          majorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          majorityAboveRangeActiveTipBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          majorityInRangeDeprecatedTipBlock <- hashedDAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          majorityInRangeActiveTipBlock <- hashedDAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              blocks = SortedSet(BlockAsActiveTip(majorityInRangeBlock.signed, NonNegLong(1L))),
              info = GlobalSnapshotInfo(SortedMap.empty, snapshotTxRefs, snapshotBalances),
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

          // Inserting blocks in required state
          _ <- blocksR(aboveRangeBlock.proofsHash).set(WaitingBlock(aboveRangeBlock.signed).some)
          _ <- blocksR(nonMajorityInRangeBlock.proofsHash).set(WaitingBlock(nonMajorityInRangeBlock.signed).some)
          _ <- blocksR(majorityInRangeBlock.proofsHash).set(WaitingBlock(majorityInRangeBlock.signed).some)
          _ <- blocksR(majorityAboveRangeActiveTipBlock.proofsHash).set(WaitingBlock(majorityAboveRangeActiveTipBlock.signed).some)
          _ <- blocksR(majorityInRangeDeprecatedTipBlock.proofsHash).set(WaitingBlock(majorityInRangeDeprecatedTipBlock.signed).some)
          _ <- blocksR(majorityInRangeActiveTipBlock.proofsHash).set(WaitingBlock(majorityInRangeActiveTipBlock.signed).some)

          processingResult <- snapshotProcessor.process(hashedSnapshot)

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              DownloadPerformed(
                GlobalSnapshotReference(
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
              Some(hashedSnapshot),
              snapshotTxRefs.map { case (k, v) => k -> Majority(v) }
            )
          )
    }
  }

  test("download should happen for the case when there are postponed blocks in the storage") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(2L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(6L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(5L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(8L), ProofsHash("parent5"))

        val hashedDAGBlock = hashedDAGBlockForKeyPair(srcKey)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 9).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 1).map(_.toList)

          aboveRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent5),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(7).signed))
          )
          aboveRangeRelatedToTipBlock <- hashedDAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(7).signed))
          )
          nonMajorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          majorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          aboveRangeRelatedBlock <- hashedDAGBlock(
            NonEmptyList.one(BlockReference(majorityInRangeBlock.height, majorityInRangeBlock.proofsHash)),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(8).signed))
          )
          majorityAboveRangeActiveTipBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          majorityInRangeDeprecatedTipBlock <- hashedDAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          majorityInRangeActiveTipBlock <- hashedDAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              blocks = SortedSet(BlockAsActiveTip(majorityInRangeBlock.signed, NonNegLong(1L))),
              info = GlobalSnapshotInfo(SortedMap.empty, snapshotTxRefs, snapshotBalances),
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

          // Inserting blocks in required state
          _ <- blocksR(aboveRangeBlock.proofsHash).set(PostponedBlock(aboveRangeBlock.signed).some)
          _ <- blocksR(aboveRangeRelatedToTipBlock.proofsHash).set(PostponedBlock(aboveRangeRelatedToTipBlock.signed).some)
          _ <- blocksR(nonMajorityInRangeBlock.proofsHash).set(PostponedBlock(nonMajorityInRangeBlock.signed).some)
          _ <- blocksR(majorityInRangeBlock.proofsHash).set(PostponedBlock(majorityInRangeBlock.signed).some)
          _ <- blocksR(aboveRangeRelatedBlock.proofsHash).set(PostponedBlock(aboveRangeRelatedBlock.signed).some)
          _ <- blocksR(majorityAboveRangeActiveTipBlock.proofsHash).set(PostponedBlock(majorityAboveRangeActiveTipBlock.signed).some)
          _ <- blocksR(majorityInRangeDeprecatedTipBlock.proofsHash).set(PostponedBlock(majorityInRangeDeprecatedTipBlock.signed).some)
          _ <- blocksR(majorityInRangeActiveTipBlock.proofsHash).set(PostponedBlock(majorityInRangeActiveTipBlock.signed).some)

          processingResult <- snapshotProcessor.process(hashedSnapshot)

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              DownloadPerformed(
                GlobalSnapshotReference(
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
              Some(hashedSnapshot),
              snapshotTxRefs.map { case (k, v) => k -> Majority(v) }
            )
          )
    }
  }

  test("alignment at same height should happen when snapshot with new ordinal but known height is processed") {
    testResources.use {
      case (snapshotProcessor, sp, kp, srcKey, _, _, _, peerId, balancesR, blocksR, lastSnapR, lastAccTxR) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        for {
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal11,
              subHeight = snapshotSubHeight1,
              lastSnapshotHash = hashedLastSnapshot.hash
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set(hashedLastSnapshot.some)

          processingResult <- snapshotProcessor.process(hashedNextSnapshot)

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, balancesAfter, blocksAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              Aligned(
                GlobalSnapshotReference(
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
              Some(hashedNextSnapshot),
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
            kp,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(6L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(7L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(8L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(9L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(9L), ProofsHash("parent5"))

        val hashedDAGBlock = hashedDAGBlockForKeyPair(srcKey)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 5).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 2).map(_.toList)

          waitingInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          postponedInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(1).signed))
          )
          majorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed))
          )
          inRangeRelatedTxnBlock <- hashedDAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          aboveRangeAcceptedBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          aboveRangeMajorityBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          waitingAboveRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )
          postponedAboveRangeRelatedToTipBlock <- hashedDAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          postponedAboveRangeRelatedTxnBlock <- hashedDAGBlock(
            NonEmptyList.one(parent5),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )

          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent1, snapshotOrdinal9),
                  DeprecatedTip(parent2, snapshotOrdinal9)
                ),
                SortedSet(
                  ActiveTip(parent3, 1L, snapshotOrdinal8),
                  ActiveTip(parent4, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
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
                  ActiveTip(parent4, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set(hashedLastSnapshot.some)
          // Inserting tips
          _ <- blocksR(parent1.hash).set(MajorityBlock(parent1, 2L, Deprecated).some)
          _ <- blocksR(parent2.hash).set(MajorityBlock(parent2, 2L, Deprecated).some)
          _ <- blocksR(parent3.hash).set(MajorityBlock(parent3, 1L, Active).some)
          _ <- blocksR(parent4.hash).set(MajorityBlock(parent4, 1L, Active).some)
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
          _ <- blocksR(postponedAboveRangeRelatedTxnBlock.proofsHash).set(PostponedBlock(postponedAboveRangeRelatedTxnBlock.signed).some)

          processingResult <- snapshotProcessor.process(hashedNextSnapshot)

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              Aligned(
                GlobalSnapshotReference(
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
                postponedAboveRangeRelatedToTipBlock.proofsHash -> PostponedBlock(postponedAboveRangeRelatedToTipBlock.signed),
                postponedAboveRangeRelatedTxnBlock.proofsHash -> WaitingBlock(postponedAboveRangeRelatedTxnBlock.signed)
              ),
              Map.empty,
              Some(hashedNextSnapshot),
              Map.empty
            )
          )
    }
  }

  test("redownload should happen when node is misaligned with majority in processed snapshot") {
    testResources.use {
      case (
            snapshotProcessor,
            sp,
            kp,
            srcKey,
            dstKey,
            srcAddress,
            dstAddress,
            peerId,
            balancesR,
            blocksR,
            lastSnapR,
            lastAccTxR
          ) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(6L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(7L), ProofsHash("parent2"))
        val parent3 = BlockReference(Height(8L), ProofsHash("parent3"))
        val parent4 = BlockReference(Height(9L), ProofsHash("parent4"))
        val parent5 = BlockReference(Height(9L), ProofsHash("parent5"))

        val hashedDAGBlock = hashedDAGBlockForKeyPair(srcKey)

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 9).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 4).map(_.toList)

          waitingInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(2).signed))
          )
          postponedInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(3).signed))
          )
          waitingMajorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          postponedMajorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          acceptedMajorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed))
          )
          majorityUnknownBlock <- hashedDAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          acceptedNonMajorityInRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          aboveRangeAcceptedBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(1).signed))
          )
          aboveRangeAcceptedMajorityBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          aboveRangeUnknownMajorityBlock <- hashedDAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )
          waitingAboveRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          postponedAboveRangeBlock <- hashedDAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          postponedAboveRangeNotRelatedBlock <- hashedDAGBlock(
            NonEmptyList.one(parent5),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(8).signed))
          )

          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(4)))
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent1, snapshotOrdinal9),
                  DeprecatedTip(parent2, snapshotOrdinal9)
                ),
                SortedSet(
                  ActiveTip(parent3, 1L, snapshotOrdinal8),
                  ActiveTip(parent4, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
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
              info = GlobalSnapshotInfo(SortedMap.empty, snapshotTxRefs, snapshotBalances),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal11)
                ),
                SortedSet(
                  ActiveTip(parent4, 1L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set(hashedLastSnapshot.some)
          // Inserting tips
          _ <- blocksR(parent1.hash).set(MajorityBlock(parent1, 2L, Deprecated).some)
          _ <- blocksR(parent2.hash).set(MajorityBlock(parent2, 2L, Deprecated).some)
          _ <- blocksR(parent3.hash).set(MajorityBlock(parent3, 1L, Active).some)
          _ <- blocksR(parent4.hash).set(MajorityBlock(parent4, 1L, Active).some)
          // Inserting blocks in required state
          _ <- blocksR(waitingInRangeBlock.proofsHash).set(WaitingBlock(waitingInRangeBlock.signed).some)
          _ <- blocksR(postponedInRangeBlock.proofsHash).set(PostponedBlock(postponedInRangeBlock.signed).some)
          _ <- blocksR(waitingMajorityInRangeBlock.proofsHash).set(WaitingBlock(waitingMajorityInRangeBlock.signed).some)
          _ <- blocksR(postponedMajorityInRangeBlock.proofsHash).set(PostponedBlock(postponedMajorityInRangeBlock.signed).some)
          _ <- blocksR(acceptedMajorityInRangeBlock.proofsHash).set(AcceptedBlock(acceptedMajorityInRangeBlock).some)
          _ <- blocksR(acceptedNonMajorityInRangeBlock.proofsHash).set(AcceptedBlock(acceptedNonMajorityInRangeBlock).some)
          _ <- blocksR(aboveRangeAcceptedBlock.proofsHash).set(AcceptedBlock(aboveRangeAcceptedBlock).some)
          _ <- blocksR(aboveRangeAcceptedMajorityBlock.proofsHash).set(AcceptedBlock(aboveRangeAcceptedMajorityBlock).some)
          _ <- blocksR(waitingAboveRangeBlock.proofsHash).set(WaitingBlock(waitingAboveRangeBlock.signed).some)
          _ <- blocksR(postponedAboveRangeBlock.proofsHash).set(PostponedBlock(postponedAboveRangeBlock.signed).some)
          _ <- blocksR(postponedAboveRangeNotRelatedBlock.proofsHash).set(PostponedBlock(postponedAboveRangeNotRelatedBlock.signed).some)

          processingResult <- snapshotProcessor.process(hashedNextSnapshot)

          balancesAfter <- balancesR.get
          blocksAfter <- blocksR.toMap
          lastGlobalSnapshotAfter <- lastSnapR.get
          lastAcceptedTxRAfter <- lastAccTxR.toMap
        } yield
          expect.same(
            (processingResult, blocksAfter, balancesAfter, lastGlobalSnapshotAfter, lastAcceptedTxRAfter),
            (
              RedownloadPerformed(
                GlobalSnapshotReference(
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
                postponedAboveRangeNotRelatedBlock.proofsHash -> PostponedBlock(postponedAboveRangeNotRelatedBlock.signed)
              ),
              snapshotBalances,
              Some(hashedNextSnapshot),
              snapshotTxRefs.map { case (k, v) => k -> Majority(v) }
            )
          )
    }
  }

  test("snapshot should be ignored when a snapshot pushed for processing is not a next one") {
    testResources.use {
      case (snapshotProcessor, sp, kp, srcKey, _, _, _, peerId, _, _, lastSnapR, _) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        for {
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              ordinal = snapshotOrdinal12,
              lastSnapshotHash = hashedLastSnapshot.hash
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          _ <- lastSnapR.set(hashedLastSnapshot.some)
          processingResult <- snapshotProcessor
            .process(hashedNextSnapshot)
            .map(_.asRight[Throwable])
            .handleErrorWith(e => IO.pure(e.asLeft[SnapshotProcessingResult]))
          lastSnapshotAfter <- lastSnapR.get.map(_.get)
        } yield
          expect.same(
            (processingResult, lastSnapshotAfter),
            (
              Right(
                SnapshotIgnored(
                  GlobalSnapshotReference.fromHashedGlobalSnapshot(hashedNextSnapshot)
                )
              ),
              hashedLastSnapshot
            )
          )
    }
  }

  test("error should be thrown when the tips get misaligned") {
    testResources.use {
      case (snapshotProcessor, sp, kp, srcKey, _, _, _, peerId, _, blocksR, lastSnapR, _) =>
        implicit val securityProvider: SecurityProvider[IO] = sp
        implicit val kryoPool: KryoSerializer[IO] = kp

        val parent1 = BlockReference(Height(8L), ProofsHash("parent1"))
        val parent2 = BlockReference(Height(9L), ProofsHash("parent2"))

        for {
          hashedLastSnapshot <- forAsyncKryo(
            generateSnapshot(peerId),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          hashedNextSnapshot <- forAsyncKryo(
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
          _ <- lastSnapR.set(hashedLastSnapshot.some)
          // Inserting tips
          _ <- blocksR(parent2.hash).set(MajorityBlock(parent2, 1L, Active).some)

          processingResult <- snapshotProcessor
            .process(hashedNextSnapshot)
            .map(_.asRight[Throwable])
            .handleErrorWith(e => IO.pure(e.asLeft[SnapshotProcessingResult]))
          lastSnapshotAfter <- lastSnapR.get.map(_.get)
        } yield
          expect.same(
            (processingResult, lastSnapshotAfter),
            (
              Left(TipsGotMisaligned(Set(parent1.hash), Set.empty)),
              hashedLastSnapshot
            )
          )
    }
  }

  private def hashedDAGBlockForKeyPair(key: KeyPair)(implicit sc: SecurityProvider[IO], ks: KryoSerializer[IO]) =
    (parent: NonEmptyList[BlockReference], transactions: NonEmptySet[Signed[DAGTransaction]]) =>
      forAsyncKryo(DAGBlock(parent, transactions), key).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))

}
