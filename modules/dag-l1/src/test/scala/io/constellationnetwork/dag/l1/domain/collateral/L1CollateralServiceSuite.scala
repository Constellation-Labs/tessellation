package io.constellationnetwork.dag.l1.domain.collateral

import java.util.UUID

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.std.Random
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l1.domain.block.{BlockService, BlockStorage}
import io.constellationnetwork.dag.l1.domain.swap.block.AllowSpendBlockService
import io.constellationnetwork.dag.l1.domain.tokenlock.block.TokenLockBlockService
import io.constellationnetwork.dag.l1.domain.transaction.{ContextualTransactionValidator, TransactionLimitConfig, TransactionStorage}
import io.constellationnetwork.dag.l1.infrastructure.address.storage.AddressStorage
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.config.DelegatedRewardsEra
import io.constellationnetwork.node.shared.config.types.{AllowSpendsConfig, MinMax, TokenLocksConfig}
import io.constellationnetwork.node.shared.domain.block.processing._
import io.constellationnetwork.node.shared.domain.swap.block._
import io.constellationnetwork.node.shared.domain.swap.{AllowSpendStorage, ContextualAllowSpendValidator}
import io.constellationnetwork.node.shared.domain.tokenlock.block._
import io.constellationnetwork.node.shared.domain.tokenlock.{ContextualTokenLockValidator, TokenLockStorage}
import io.constellationnetwork.node.shared.infrastructure.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.round.RoundId
import io.constellationnetwork.schema.swap._
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security.hash.ProofsHash
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import fs2.concurrent.SignallingRef
import weaver.MutableIOSuite

object L1CollateralServiceSuite extends MutableIOSuite {
  type Res = (Hasher[IO], SecurityProvider[IO], Random[IO])

  def sharedResource: Resource[IO, Res] = for {
    sp <- SecurityProvider.forAsync[IO]
    implicit0(json: JsonSerializer[IO]) <- Resource.eval(JsonSerializer.forSync[IO])
    random <- Resource.eval(Random.scalaUtilRandom[IO])
  } yield (Hasher.forJson[IO], sp, random)

  private val legacy = Amount(250_000_00000000L)
  private val minimum = DelegatedRewardsEra.MinimumNodeCollateral
  private val era = DelegatedRewardsEra(EpochProgress(100L), SortedMap.empty)
  private case object ContextObserved extends RuntimeException("Acceptance context captured") with NoStackTrace
  private case class Observation(kind: String, required: Amount, enoughCollateral: Boolean)

  test("all L1 admissions switch operator collateral at the finalized global epoch, independently of ordinal") { res =>
    implicit val (hasher, sp, random) = res
    List(
      (99L, legacy.value.value, legacy, true),
      (100L, legacy.value.value, minimum, false),
      (101L, minimum.value.value, minimum, true)
    ).traverse {
      case (headEpoch, balance, expectedRequired, expectedEnough) =>
        observeServices(headEpoch, balance, era).map { observations =>
          expect.same(
            List("block", "allow-spend", "token-lock").map(Observation(_, expectedRequired, expectedEnough)),
            observations
          )
        }
    }.map(_.reduce(_ and _))
  }

  private def observeServices(headEpoch: Long, operatorBalance: Long, policy: DelegatedRewardsEra)(
    implicit hasher: Hasher[IO],
    sp: SecurityProvider[IO],
    random: Random[IO]
  ): IO[List[Observation]] =
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      address = keyPair.getPublic.toAddress
      balance = Balance(NonNegLong.unsafeFrom(operatorBalance))
      addresses <- AddressStorage.make[IO](Map(address -> balance))
      observed <- Ref.of[IO, List[Observation]](Nil)
      genesis = GlobalSnapshot.mkGenesis(Map(address -> balance), EpochProgress.MinValue)
      base <- GlobalIncrementalSnapshot.fromGlobalSnapshot[IO](genesis)
      head <- Signed
        .forAsyncHasher[IO, GlobalIncrementalSnapshot](
          base.copy(ordinal = SnapshotOrdinal.unsafeApply(headEpoch + 1000L), epochProgress = EpochProgress(NonNegLong.unsafeFrom(headEpoch))),
          keyPair
        )
        .flatMap(_.toHashed[IO])
      headRef <- SignallingRef.of[IO, Option[(io.constellationnetwork.security.Hashed[GlobalIncrementalSnapshot], GlobalSnapshotInfo)]](
        Some(head -> GlobalSnapshotInfo.empty.copy(balances = SortedMap(address -> balance)))
      )
      snapshots = LastSnapshotStorage.make[IO, GlobalIncrementalSnapshot, GlobalSnapshotInfo](headRef)
      blocks <- BlockStorage.make[IO]
      transactions <- TransactionStorage.make[IO](
        TransactionReference.empty,
        ContextualTransactionValidator.make(TransactionLimitConfig(Balance.empty, 0.seconds, TransactionFee.zero, 1.second), None)
      )
      allowSpendBlocks <- AllowSpendBlockStorage.make[IO]
      allowSpends <- AllowSpendStorage
        .make[IO](AllowSpendReference.empty, ContextualAllowSpendValidator.make(None, None, AllowSpendsConfig(MinMax(0L, 100L))))
      tokenLockBlocks <- TokenLockBlockStorage.make[IO]
      tokenLocks <- TokenLockStorage.make[IO](TokenLockReference.empty, ContextualTokenLockValidator.make(None, TokenLocksConfig(0L), None))
      transaction <- Signed.forAsyncHasher[IO, Transaction](
        Transaction(address, address, TransactionAmount(1L), TransactionFee.zero, TransactionReference.empty, TransactionSalt(0L)),
        keyPair
      )
      block <- Signed.forAsyncHasher[IO, Block](
        Block(NonEmptyList.one(BlockReference(Height.MinValue, ProofsHash("parent"))), NonEmptySet.one(transaction)),
        keyPair
      )
      allowSpend <- Signed.forAsyncHasher[IO, AllowSpend](
        AllowSpend(address, address, None, SwapAmount(1L), AllowSpendFee(0L), AllowSpendReference.empty, EpochProgress(100L), Nil),
        keyPair
      )
      allowSpendBlock <- Signed
        .forAsyncHasher[IO, AllowSpendBlock](AllowSpendBlock(RoundId(new UUID(0L, 1L)), NonEmptySet.one(allowSpend)), keyPair)
      tokenLock <- Signed.forAsyncHasher[IO, TokenLock](
        TokenLock(address, TokenLockAmount(1L), TokenLockFee(0L), TokenLockReference.empty, None, None),
        keyPair
      )
      tokenLockBlock <- Signed
        .forAsyncHasher[IO, TokenLockBlock](TokenLockBlock(RoundId(new UUID(0L, 2L)), NonEmptySet.one(tokenLock)), keyPair)
      blockManager = new BlockAcceptanceManager[IO] {
        def acceptBlock(
          block: Signed[Block],
          context: BlockAcceptanceContext[IO],
          ordinal: SnapshotOrdinal,
          shouldValidateCollateral: Boolean
        )(implicit h: Hasher[IO]): IO[Either[BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)]] =
          context.getBalance(address).flatMap(record(observed, "block", context.getCollateral, _))
        def acceptBlocksIteratively(
          blocks: List[Signed[Block]],
          context: BlockAcceptanceContext[IO],
          ordinal: SnapshotOrdinal,
          shouldValidateCollateral: Boolean
        )(implicit h: Hasher[IO]): IO[BlockAcceptanceResult] =
          unexpected
      }
      allowSpendManager = new AllowSpendBlockAcceptanceManager[IO] {
        def acceptBlock(
          block: Signed[AllowSpendBlock],
          context: AllowSpendBlockAcceptanceContext[IO],
          ordinal: SnapshotOrdinal,
          shouldValidateCollateral: Boolean,
          epoch: Option[EpochProgress],
          creditDestination: Boolean
        )(implicit h: Hasher[IO]): IO[Either[AllowSpendBlockNotAcceptedReason, AllowSpendBlockAcceptanceContextUpdate]] =
          context.getBalance(address).flatMap(record(observed, "allow-spend", context.getCollateral, _))
        def acceptBlocksIteratively(
          blocks: List[Signed[AllowSpendBlock]],
          context: AllowSpendBlockAcceptanceContext[IO],
          ordinal: SnapshotOrdinal,
          shouldValidateCollateral: Boolean,
          epoch: Option[EpochProgress],
          creditDestination: Boolean
        )(implicit h: Hasher[IO]): IO[AllowSpendBlockAcceptanceResult] = unexpected
      }
      tokenLockManager = new TokenLockBlockAcceptanceManager[IO] {
        def acceptBlock(
          block: Signed[TokenLockBlock],
          context: TokenLockBlockAcceptanceContext[IO],
          ordinal: SnapshotOrdinal,
          shouldValidateCollateral: Boolean,
          epoch: Option[EpochProgress]
        )(implicit h: Hasher[IO]): IO[Either[TokenLockBlockNotAcceptedReason, TokenLockBlockAcceptanceContextUpdate]] =
          context.getBalance(address).flatMap(record(observed, "token-lock", context.getCollateral, _))
        def acceptBlocksIteratively(
          blocks: List[Signed[TokenLockBlock]],
          context: TokenLockBlockAcceptanceContext[IO],
          ordinal: SnapshotOrdinal,
          shouldValidateCollateral: Boolean,
          epoch: Option[EpochProgress]
        )(implicit h: Hasher[IO]): IO[TokenLockBlockAcceptanceResult] = unexpected
      }
      blockService = BlockService.make[IO](blockManager, addresses, blocks, transactions, snapshots, legacy, hasher, policy)
      allowSpendService = AllowSpendBlockService.make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        allowSpendManager,
        addresses,
        allowSpendBlocks,
        allowSpends,
        legacy,
        snapshots,
        policy
      )
      tokenLockService = TokenLockBlockService.make[IO, GlobalSnapshotStateProof, GlobalIncrementalSnapshot, GlobalSnapshotInfo](
        tokenLockManager,
        addresses,
        tokenLockBlocks,
        tokenLocks,
        legacy,
        snapshots,
        policy
      )
      _ <- expectCapture(blockService.accept(block))
      _ <- expectCapture(allowSpendService.accept(allowSpendBlock, SnapshotOrdinal.unsafeApply(headEpoch + 1000L)))
      // The production token-lock caller supplies MinValue; collateral must come from the actual global head.
      _ <- expectCapture(tokenLockService.accept(tokenLockBlock, SnapshotOrdinal.MinValue))
      result <- observed.get
    } yield result

  private def record[A](observed: Ref[IO, List[Observation]], kind: String, required: Amount, balance: Option[Balance]): IO[A] =
    observed.update(_ :+ Observation(kind, required, balance.exists(_.satisfiesCollateral(required)))) >> IO.raiseError(ContextObserved)

  private def expectCapture(effect: IO[Unit]): IO[Unit] =
    effect.attempt.flatMap {
      case Left(ContextObserved) => IO.unit
      case Left(error)           => IO.raiseError(error)
      case Right(_)              => IO.raiseError(new IllegalStateException("Acceptance unexpectedly bypassed the recording interpreter"))
    }

  private def unexpected[A]: IO[A] = IO.raiseError(new IllegalStateException("Unexpected bulk block acceptance"))
}
