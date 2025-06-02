package io.constellationnetwork.dag.l1.domain.block

import cats.data.NonEmptyList
import cats.effect.std.Random
import cats.effect.{IO, Resource}
import cats.syntax.either._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration.DurationInt

import io.constellationnetwork.block.generators._
import io.constellationnetwork.dag.l1.Main
import io.constellationnetwork.dag.l1.domain.address.storage.AddressStorage
import io.constellationnetwork.dag.l1.domain.block.BlockStorage._
import io.constellationnetwork.dag.l1.domain.transaction._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.collection.MapRefUtils._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security.hash.ProofsHash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, _}

import eu.timepit.refined.auto._
import fs2.Stream
import io.chrisdavenport.mapref.MapRef
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockServiceSuite extends MutableIOSuite with Checkers {

  type Res = (Hasher[IO], Hasher[IO])

  def sharedResource: Resource[IO, Res] = for {
    implicit0(ks: KryoSerializer[IO]) <- KryoSerializer.forAsync[IO](Main.kryoRegistrar ++ nodeSharedKryoRegistrar)
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    hj = Hasher.forJson[IO]
    hk = Hasher.forKryo[IO]
  } yield (hj, hk)

  def mkBlockService(
    blocksR: MapRef[IO, ProofsHash, Option[StoredBlock]],
    ts: MapRef[IO, Address, Option[SortedMap[TransactionOrdinal, StoredTransaction]]],
    notAcceptanceReason: Option[BlockNotAcceptedReason] = None,
    txHasher: Hasher[IO]
  ) = {

    val blockAcceptanceManager = new BlockAcceptanceManager[IO]() {

      override def acceptBlocksIteratively(
        blocks: List[Signed[Block]],
        context: BlockAcceptanceContext[IO],
        ordinal: SnapshotOrdinal,
        shouldValidateCollateral: Boolean = true
      )(implicit hasher: Hasher[F]): IO[BlockAcceptanceResult] =
        ???

      override def acceptBlock(
        block: Signed[Block],
        context: BlockAcceptanceContext[IO],
        ordinal: SnapshotOrdinal,
        shouldValidateCollateral: Boolean = true
      )(implicit hasher: Hasher[F]): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] =
        IO.pure(notAcceptanceReason).map {
          case None         => (BlockAcceptanceContextUpdate.empty, initUsageCount).asRight[BlockNotAcceptedReason]
          case Some(reason) => reason.asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
        }
    }

    val addressStorage = new AddressStorage[IO] {

      override def getState: IO[Map[Address, Balance]] = ???

      override def getBalance(address: Address): IO[balance.Balance] = ???

      override def updateBalances(addressBalances: Map[Address, Balance]): IO[Unit] = IO.unit

      override def clean: IO[Unit] = ???

    }

    Random.scalaUtilRandom.map { implicit r =>
      val blockStorage = new BlockStorage[IO](blocksR)
      val contextualValidator = ContextualTransactionValidator.make(
        TransactionLimitConfig(Balance.empty, 0.hours, TransactionFee.zero, 1.second),
        None
      )
      val transactionStorage =
        new TransactionStorage[IO](ts, TransactionReference.empty, contextualValidator)

      val lastGlobalSnapshotStorage = new LastSnapshotStorage[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo] {
        override def set(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] = ???

        override def setInitial(snapshot: Hashed[GlobalIncrementalSnapshot], state: GlobalSnapshotInfo): IO[Unit] = ???

        override def get: IO[Option[Hashed[GlobalIncrementalSnapshot]]] = ???

        override def getCombined: IO[Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???

        override def getCombinedStream: Stream[IO, Option[(Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]] = ???

        override def getOrdinal: IO[Option[SnapshotOrdinal]] = IO.pure(SnapshotOrdinal.MinValue.some)

        override def getHeight: IO[Option[height.Height]] = ???
      }
      BlockService
        .make[IO](
          blockAcceptanceManager,
          addressStorage,
          blockStorage,
          transactionStorage,
          lastGlobalSnapshotStorage,
          Amount.empty,
          txHasher
        )
    }
  }

  test("valid block should be accepted") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall(signedBlockGen) { block =>
        for {
          hashedBlock <- block.toHashed[IO]
          blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
          _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
          _ <- addParents(blocksR, block)
          lastAccTxR <- setupTxsStorage(Some(block))

          blockService <- mkBlockService(blocksR, lastAccTxR, txHasher = txHasher)

          _ <- blockService.accept(block)
          blocksRes <- blocksR.toMap
        } yield
          expect.same(
            block.parent.toList
              .map(parent => parent.hash -> MajorityBlock(parent, 2L, Active))
              .toMap + (hashedBlock.proofsHash -> AcceptedBlock(hashedBlock)),
            blocksRes
          )
      }
  }

  test("valid block should be accepted and not related block should stay postponed ") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall((block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedBlock <- block.toHashed[IO]
          hashedNotRelatedBlock <- notRelatedBlock.toHashed[IO]
          blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
          _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
          _ <- blocksR(hashedNotRelatedBlock.proofsHash).set(Some(PostponedBlock(hashedNotRelatedBlock.signed)))
          _ <- addParents(blocksR, block)
          lastAccTxR <- setupTxsStorage(Some(block))

          blockService <- mkBlockService(blocksR, lastAccTxR, txHasher = txHasher)

          _ <- blockService.accept(block)
          blocksRes <- blocksR.toMap
        } yield
          expect.same(
            Map(
              hashedBlock.proofsHash -> AcceptedBlock(hashedBlock),
              hashedNotRelatedBlock.proofsHash -> PostponedBlock(hashedNotRelatedBlock.signed)
            ) ++
              block.parent.toList
                .map(parent => parent.hash -> MajorityBlock(parent, 2L, Active)),
            blocksRes
          )
      )
  }

  test("valid block should be accepted and related block should go back to waiting") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall((block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedBlock <- block.toHashed[IO]
          hashedRelatedBlock <- notRelatedBlock
            .copy(value = notRelatedBlock.value.copy(parent = NonEmptyList.of(hashedBlock.ownReference)))
            .toHashed[IO]
          blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
          _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
          _ <- blocksR(hashedRelatedBlock.proofsHash).set(Some(PostponedBlock(hashedRelatedBlock.signed)))
          _ <- addParents(blocksR, block)
          lastAccTxR <- setupTxsStorage(Some(block))

          blockService <- mkBlockService(blocksR, lastAccTxR, txHasher = txHasher)

          _ <- blockService.accept(block)
          blocksRes <- blocksR.toMap
        } yield
          expect.same(
            Map(
              hashedBlock.proofsHash -> AcceptedBlock(hashedBlock),
              hashedRelatedBlock.proofsHash -> WaitingBlock(hashedRelatedBlock.signed)
            ) ++
              block.parent.toList
                .map(parent => parent.hash -> MajorityBlock(parent, 2L, Active)),
            blocksRes
          )
      )
  }

  test("invalid block should be postponed for acceptance") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall(signedBlockGen) { block =>
        for {
          hashedBlock <- block.toHashed[IO]
          blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
          _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
          _ <- addParents(blocksR, block)
          lastAccTxR <- setupTxsStorage(Some(block))

          blockService <- mkBlockService(blocksR, lastAccTxR, Some(ParentNotFound(block.parent.head)), txHasher = txHasher)

          error <- blockService.accept(block).attempt
          blocksRes <- blocksR.toMap
        } yield
          expect.same(
            (
              block.parent.toList
                .map(parent => parent.hash -> MajorityBlock(parent, 1L, Active))
                .toMap + (hashedBlock.proofsHash -> PostponedBlock(hashedBlock.signed)),
              Left(
                BlockService
                  .BlockAcceptanceError(BlockReference(hashedBlock.height, hashedBlock.proofsHash), ParentNotFound(block.parent.head))
              )
            ),
            (blocksRes, error)
          )
      }
  }

  test("invalid block should be postponed for acceptance and not related should stay postponed") {
    case (currentHasher, txHasher) =>
      implicit val hasher = currentHasher

      forall((block: Signed[Block], notRelatedBlock: Signed[Block]) =>
        for {
          hashedBlock <- block.toHashed[IO]
          hashedNotRelatedBlock <- notRelatedBlock.toHashed[IO]
          blocksR <- MapRef.ofConcurrentHashMap[IO, ProofsHash, StoredBlock]()
          _ <- blocksR(hashedBlock.proofsHash).set(Some(WaitingBlock(hashedBlock.signed)))
          _ <- blocksR(hashedNotRelatedBlock.proofsHash).set(Some(PostponedBlock(hashedNotRelatedBlock.signed)))
          _ <- addParents(blocksR, block)
          lastAccTxR <- setupTxsStorage(Some(block))

          blockService <- mkBlockService(blocksR, lastAccTxR, Some(ParentNotFound(block.parent.head)), txHasher = txHasher)

          error <- blockService.accept(block).attempt
          blocksRes <- blocksR.toMap
        } yield
          expect.same(
            (
              Map(
                hashedBlock.proofsHash -> PostponedBlock(hashedBlock.signed),
                hashedNotRelatedBlock.proofsHash -> PostponedBlock(hashedNotRelatedBlock.signed)
              ) ++
                block.parent.toList
                  .map(parent => parent.hash -> MajorityBlock(parent, 1L, Active))
                  .toMap,
              Left(
                BlockService
                  .BlockAcceptanceError(BlockReference(hashedBlock.height, hashedBlock.proofsHash), ParentNotFound(block.parent.head))
              )
            ),
            (blocksRes, error)
          )
      )
  }

  private def addParents(blocksR: MapRef[IO, ProofsHash, Option[StoredBlock]], block: Signed[Block]) =
    block.parent.toList.traverse(parent => blocksR(parent.hash).set(Some(MajorityBlock(parent, 1L, Active))))

  private def setupTxsStorage(maybeBlock: Option[Signed[Block]]) = for {
    transactionsR <- MapRef.ofConcurrentHashMap[IO, Address, SortedMap[TransactionOrdinal, StoredTransaction]]()
    _ <- maybeBlock.traverse(block =>
      block.transactions.toNonEmptyList.toList.traverse(txn =>
        transactionsR(txn.source).update { maybeStored =>
          val updated = maybeStored.getOrElse(SortedMap.empty[TransactionOrdinal, StoredTransaction])
          updated.updated(txn.parent.ordinal, MajorityTx(txn.parent, SnapshotOrdinal.MinValue)).some
        }
      )
    )
  } yield transactionsR

}
