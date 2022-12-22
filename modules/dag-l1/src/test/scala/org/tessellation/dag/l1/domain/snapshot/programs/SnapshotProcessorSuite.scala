package org.tessellation.dag.l1.domain.snapshot.programs

import java.security.KeyPair

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect._
import cats.effect.std.Random
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.dag.domain.block.{DAGBlock, DAGBlockAsActiveTip}
import org.tessellation.dag.l1.domain.address.storage.AddressStorage
import org.tessellation.dag.l1.domain.block.BlockStorage
import org.tessellation.dag.l1.domain.block.BlockStorage._
import org.tessellation.dag.l1.domain.snapshot.programs.SnapshotProcessor._
import org.tessellation.dag.l1.domain.snapshot.storage.LastGlobalSnapshotStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage
import org.tessellation.dag.l1.domain.transaction.TransactionStorage.{LastTransactionReferenceState, Majority}
import org.tessellation.dag.l1.{Main, TransactionGenerator}
import org.tessellation.dag.snapshot._
import org.tessellation.dag.snapshot.epoch.EpochProgress
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.collection.MapRefUtils._
import org.tessellation.keytool.KeyPairGenerator
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema._
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.security.hash.{Hash, ProofsHash}
import org.tessellation.schema.security.key.ops.PublicKeyOps
import org.tessellation.schema.security.signature.Signed.forAsyncKryo
import org.tessellation.schema.security.{Hashed, SecurityProvider}
import org.tessellation.schema.transaction._
import org.tessellation.sdk.sdkKryoRegistrar

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
            waitingTxsR <- MapRef.ofConcurrentHashMap[IO, Address, NonEmptySet[Hashed[Transaction]]]().asResource
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
                blocks = SortedSet(DAGBlockAsActiveTip(hashedBlock.signed, NonNegLong.MinValue)),
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

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 7).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 1).map(_.toList)

          aboveRangeBlock = DAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(6).signed))
          )
          nonMajorityInRangeBlock = DAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          majorityInRangeBlock = DAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )
          majorityAboveRangeActiveTipBlock = DAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          majorityInRangeDeprecatedTipBlock = DAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          majorityInRangeActiveTipBlock = DAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )

          blocks = List(
            aboveRangeBlock,
            nonMajorityInRangeBlock,
            majorityInRangeBlock,
            majorityAboveRangeActiveTipBlock,
            majorityInRangeDeprecatedTipBlock,
            majorityInRangeActiveTipBlock
          )

          hashedBlocks <- blocks.traverse(
            forAsyncKryo(_, srcKey).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
          )
          snapshotBalances = generateSnapshotBalances(Set(srcAddress))
          snapshotTxRefs = generateSnapshotLastAccTxRefs(Map(srcAddress -> correctTxs(5)))
          hashedSnapshot <- forAsyncKryo(
            generateSnapshot(peerId).copy(
              blocks = SortedSet(DAGBlockAsActiveTip(hashedBlocks(2).signed, NonNegLong(1L))),
              info = GlobalSnapshotInfo(SortedMap.empty, snapshotTxRefs, snapshotBalances),
              tips = SnapshotTips(
                SortedSet(
                  DeprecatedTip(parent3, snapshotOrdinal9),
                  DeprecatedTip(BlockReference(hashedBlocks(4).height, hashedBlocks(4).proofsHash), snapshotOrdinal9)
                ),
                SortedSet(
                  ActiveTip(parent1, 1L, snapshotOrdinal8),
                  ActiveTip(BlockReference(hashedBlocks(3).height, hashedBlocks(3).proofsHash), 1L, snapshotOrdinal9),
                  ActiveTip(BlockReference(hashedBlocks(5).height, hashedBlocks(5).proofsHash), 2L, snapshotOrdinal9)
                )
              )
            ),
            srcKey
          ).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))

          // Inserting blocks in required state
          _ <- blocksR(hashedBlocks.head.proofsHash).set(WaitingBlock(hashedBlocks.head.signed).some)
          _ <- blocksR(hashedBlocks(1).proofsHash).set(WaitingBlock(hashedBlocks(1).signed).some)
          _ <- blocksR(hashedBlocks(2).proofsHash).set(WaitingBlock(hashedBlocks(2).signed).some)
          _ <- blocksR(hashedBlocks(3).proofsHash).set(WaitingBlock(hashedBlocks(3).signed).some)
          _ <- blocksR(hashedBlocks(4).proofsHash).set(WaitingBlock(hashedBlocks(4).signed).some)
          _ <- blocksR(hashedBlocks(5).proofsHash).set(WaitingBlock(hashedBlocks(5).signed).some)

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
                Set(hashedBlocks(2).proofsHash),
                Set(hashedBlocks(1).proofsHash)
              ),
              Map(
                hashedBlocks.head.proofsHash -> WaitingBlock(hashedBlocks.head.signed),
                hashedBlocks(2).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(2).height, hashedBlocks(2).proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                hashedBlocks(3).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(3).height, hashedBlocks(3).proofsHash),
                  NonNegLong(1L),
                  Active
                ),
                hashedBlocks(4).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(4).height, hashedBlocks(4).proofsHash),
                  0L,
                  Deprecated
                ),
                hashedBlocks(5).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(5).height, hashedBlocks(5).proofsHash),
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

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 4).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 1).map(_.toList)

          waitingInRangeBlock = DAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          majorityInRangeBlock = DAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed))
          )
          aboveRangeAcceptedBlock = DAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          aboveRangeMajorityBlock = DAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          waitingAboveRangeBlock = DAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )

          blocks = List(
            waitingInRangeBlock, // 0
            majorityInRangeBlock, // 1
            aboveRangeAcceptedBlock, // 2
            aboveRangeMajorityBlock, // 3
            waitingAboveRangeBlock // 4
          )

          hashedBlocks <- blocks.traverse(
            forAsyncKryo(_, srcKey).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
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
              blocks = SortedSet(DAGBlockAsActiveTip(hashedBlocks(1).signed, 1L), DAGBlockAsActiveTip(hashedBlocks(3).signed, 2L)),
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
          _ <- blocksR(hashedBlocks.head.proofsHash).set(WaitingBlock(hashedBlocks.head.signed).some)
          _ <- blocksR(hashedBlocks(1).proofsHash).set(AcceptedBlock(hashedBlocks(1)).some)
          _ <- blocksR(hashedBlocks(2).proofsHash).set(AcceptedBlock(hashedBlocks(2)).some)
          _ <- blocksR(hashedBlocks(3).proofsHash).set(AcceptedBlock(hashedBlocks(3)).some)
          _ <- blocksR(hashedBlocks(4).proofsHash).set(WaitingBlock(hashedBlocks(4).signed).some)

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
                Set(hashedBlocks.head.proofsHash)
              ),
              Map(
                parent3.hash -> MajorityBlock(parent3, 1L, Deprecated),
                parent4.hash -> MajorityBlock(parent4, 1L, Active),
                hashedBlocks(1).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(1).height, hashedBlocks(1).proofsHash),
                  1L,
                  Active
                ),
                hashedBlocks(2).proofsHash -> AcceptedBlock(hashedBlocks(2)),
                hashedBlocks(3).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(3).height, hashedBlocks(3).proofsHash),
                  2L,
                  Active
                ),
                hashedBlocks(4).proofsHash -> WaitingBlock(hashedBlocks(4).signed)
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

        for {
          correctTxs <- generateTransactions(srcAddress, srcKey, dstAddress, 6).map(_.toList)
          wrongTxs <- generateTransactions(dstAddress, dstKey, srcAddress, 3).map(_.toList)

          waitingInRangeBlock = DAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(2).signed))
          )
          waitingMajorityInRangeBlock = DAGBlock(
            NonEmptyList.one(parent1),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(4).signed))
          )
          acceptedMajorityInRangeBlock = DAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs.head.signed))
          )
          majorityUnknownBlock = DAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(2).signed))
          )
          acceptedNonMajorityInRangeBlock = DAGBlock(
            NonEmptyList.one(parent2),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs.head.signed))
          )
          aboveRangeAcceptedBlock = DAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(wrongTxs(1).signed))
          )
          aboveRangeAcceptedMajorityBlock = DAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(1).signed))
          )
          aboveRangeUnknownMajorityBlock = DAGBlock(
            NonEmptyList.one(parent3),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(3).signed))
          )
          waitingAboveRangeBlock = DAGBlock(
            NonEmptyList.one(parent4),
            NonEmptySet.fromSetUnsafe(SortedSet(correctTxs(5).signed))
          )

          blocks = List(
            waitingInRangeBlock, // 0
            waitingMajorityInRangeBlock, // 1
            acceptedMajorityInRangeBlock, // 2
            majorityUnknownBlock, // 3
            acceptedNonMajorityInRangeBlock, // 4
            aboveRangeAcceptedBlock, // 5
            aboveRangeAcceptedMajorityBlock, // 6
            aboveRangeUnknownMajorityBlock, // 7
            waitingAboveRangeBlock // 8
          )

          hashedBlocks <- blocks.traverse(
            forAsyncKryo(_, srcKey).flatMap(_.toHashedWithSignatureCheck.map(_.toOption.get))
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
                DAGBlockAsActiveTip(hashedBlocks(1).signed, 1L),
                DAGBlockAsActiveTip(hashedBlocks(2).signed, 2L),
                DAGBlockAsActiveTip(hashedBlocks(3).signed, 1L),
                DAGBlockAsActiveTip(hashedBlocks(6).signed, 0L),
                DAGBlockAsActiveTip(hashedBlocks(7).signed, 0L)
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
          _ <- blocksR(hashedBlocks.head.proofsHash).set(WaitingBlock(hashedBlocks.head.signed).some)
          _ <- blocksR(hashedBlocks(1).proofsHash).set(WaitingBlock(hashedBlocks(1).signed).some)
          _ <- blocksR(hashedBlocks(2).proofsHash).set(AcceptedBlock(hashedBlocks(2)).some)
          _ <- blocksR(hashedBlocks(4).proofsHash).set(AcceptedBlock(hashedBlocks(4)).some)
          _ <- blocksR(hashedBlocks(5).proofsHash).set(AcceptedBlock(hashedBlocks(5)).some)
          _ <- blocksR(hashedBlocks(6).proofsHash).set(AcceptedBlock(hashedBlocks(6)).some)
          _ <- blocksR(hashedBlocks(8).proofsHash).set(WaitingBlock(hashedBlocks(8).signed).some)

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
                addedBlocks = Set(hashedBlocks(1).proofsHash, hashedBlocks(3).proofsHash, hashedBlocks(7).proofsHash),
                removedBlocks = Set(hashedBlocks(4).proofsHash),
                removedObsoleteBlocks = Set(hashedBlocks.head.proofsHash)
              ),
              Map(
                parent3.hash -> MajorityBlock(parent3, 2L, Deprecated),
                parent4.hash -> MajorityBlock(parent4, 1L, Active),
                hashedBlocks(1).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(1).height, hashedBlocks(1).proofsHash),
                  1L,
                  Active
                ),
                hashedBlocks(2).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(2).height, hashedBlocks(2).proofsHash),
                  2L,
                  Active
                ),
                hashedBlocks(3).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(3).height, hashedBlocks(3).proofsHash),
                  1L,
                  Active
                ),
                hashedBlocks(5).proofsHash -> WaitingBlock(hashedBlocks(5).signed),
                hashedBlocks(6).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(6).height, hashedBlocks(6).proofsHash),
                  0L,
                  Active
                ),
                hashedBlocks(7).proofsHash -> MajorityBlock(
                  BlockReference(hashedBlocks(7).height, hashedBlocks(7).proofsHash),
                  0L,
                  Active
                ),
                hashedBlocks(8).proofsHash -> WaitingBlock(hashedBlocks(8).signed)
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
}
