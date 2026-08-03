package io.constellationnetwork.node.shared.domain.delegatedStake

import java.security.KeyPair

import cats.data.{NonEmptyChain, NonEmptySet}
import cats.effect.IO
import cats.effect.kernel.Resource

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidatorSuite.mkGlobalContext
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.node._
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.key.ops.PublicKeyOps
import io.constellationnetwork.security.signature.Signed.forAsyncHasher
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, KeyPairGenerator, SecurityProvider}

import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object UpdateDelegatedStakeAcceptanceManagerSuite extends MutableIOSuite {

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO], KeyPair, Address)

  def sharedResource: Resource[IO, Res] = for {
    implicit0(sp: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forSync[IO].asResource
    h = Hasher.forJson[IO]
    kp <- KeyPairGenerator.makeKeyPair[IO].asResource
    sourceAddress <- kp.getPublic.toId.toAddress.asResource
  } yield (j, h, sp, kp, sourceAddress)

  test("should reject stakes with the same parent") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](
        UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
        SnapshotOrdinal.MinValue
      )
    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      ((ref1, ref2), ctx) <- mkValidGlobalContext(kp, kp1, kp)
      parent1 <- Signed.forAsyncHasher(testCreateDelegatedStake(kp2, sourceAddress, 100L), kp)
      lastRef1 <- DelegatedStakeReference.of(parent1)
      context = ctx.copy(activeDelegatedStakes =
        Some(
          SortedMap(
            sourceAddress ->
              SortedSet(
                DelegatedStakeRecord(parent1, SnapshotOrdinal.MinValue, Amount(NonNegLong(0L)))
              )
          )
        )
      )
      valid1 <- Signed.forAsyncHasher(testCreateDelegatedStake(kp1, sourceAddress, 100L, tokenLockReference = ref1, parent = lastRef1), kp)
      invalid <- Signed.forAsyncHasher(testCreateDelegatedStake(kp, sourceAddress, 200L, tokenLockReference = ref2, parent = lastRef1), kp)
      res <- acceptanceManager.accept(
        creates = List(valid1, invalid),
        withdrawals = List.empty,
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress.MinValue,
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(2)
      )
    } yield
      expect.all(
        res == UpdateDelegatedStakeAcceptanceResult(
          acceptedCreates = SortedMap(sourceAddress -> List((valid1, SnapshotOrdinal.unsafeApply(2)))),
          notAcceptedCreates = List((invalid, NonEmptyChain.of(DuplicatedParent(invalid.parent)))),
          acceptedWithdrawals = SortedMap.empty,
          notAcceptedWithdrawals = List.empty
        )
      )
  }

  test("should reject withdrawals with the same parent") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](
        UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
        SnapshotOrdinal.MinValue
      )
    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      (_, ctx) <- mkValidGlobalContext(kp, kp1, kp)
      parent1 <- Signed.forAsyncHasher(testCreateDelegatedStake(kp2, sourceAddress, 100L), kp)
      lastRef1 <- DelegatedStakeReference.of(parent1)
      context = ctx.copy(activeDelegatedStakes =
        Some(
          SortedMap(
            sourceAddress ->
              SortedSet(
                DelegatedStakeRecord(parent1, SnapshotOrdinal.MinValue, Amount(NonNegLong(0L)))
              )
          )
        )
      )
      valid1 <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, lastRef1.hash), kp)
      invalid <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, lastRef1.hash), kp)
      res <- acceptanceManager.accept(
        creates = List.empty,
        withdrawals = List(valid1, invalid),
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress.apply(NonNegLong(1)),
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(2)
      )
    } yield
      expect.all(
        res == UpdateDelegatedStakeAcceptanceResult(
          acceptedCreates = SortedMap.empty,
          notAcceptedCreates = List.empty,
          acceptedWithdrawals = SortedMap(sourceAddress -> List((valid1, EpochProgress.apply(NonNegLong(1))))),
          notAcceptedWithdrawals = List((invalid, NonEmptyChain.of(DuplicatedStake(invalid.stakeRef))))
        )
      )
  }

  test("should accept withdrawals with different parents") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](
        UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
        SnapshotOrdinal.MinValue
      )
    for {
      kp1 <- KeyPairGenerator.makeKeyPair[IO]
      kp2 <- KeyPairGenerator.makeKeyPair[IO]
      ((ref1, ref2), ctx) <- mkValidGlobalContext(kp, kp1, kp)
      parent1 <- Signed.forAsyncHasher(testCreateDelegatedStake(kp2, sourceAddress, 100L, ref1), kp)
      parent2 <- Signed.forAsyncHasher(testCreateDelegatedStake(kp1, sourceAddress, 100L, ref2), kp)
      lastRef1 <- DelegatedStakeReference.of(parent1)
      lastRef2 <- DelegatedStakeReference.of(parent2)
      context = ctx.copy(activeDelegatedStakes =
        Some(
          SortedMap(
            sourceAddress ->
              SortedSet(
                DelegatedStakeRecord(parent1, SnapshotOrdinal.MinValue, Amount(NonNegLong(0L))),
                DelegatedStakeRecord(parent2, SnapshotOrdinal.MinValue, Amount(NonNegLong(0L)))
              )
          )
        )
      )
      valid1 <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, lastRef1.hash), kp)
      valid2 <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, lastRef2.hash), kp)
      res <- acceptanceManager.accept(
        creates = List.empty,
        withdrawals = List(valid1, valid2),
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress.apply(NonNegLong(1)),
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(2)
      )
    } yield
      expect.all(
        res == UpdateDelegatedStakeAcceptanceResult(
          acceptedCreates = SortedMap.empty,
          notAcceptedCreates = List.empty,
          acceptedWithdrawals =
            SortedMap(sourceAddress -> List((valid2, EpochProgress.apply(NonNegLong(1))), (valid1, EpochProgress.apply(NonNegLong(1))))),
          notAcceptedWithdrawals = List.empty
        )
      )
  }

  test("should preserve create-withdraw behavior before activation and reject the withdrawal at activation") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val activationOrdinal = SnapshotOrdinal.unsafeApply(3L)
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](
        UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
        activationOrdinal
      )

    for {
      existingNodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      ((tokenLockRef, _), ctx) <- mkValidGlobalContext(kp, existingNodeKeyPair, kp)
      existingStake <- Signed.forAsyncHasher(
        testCreateDelegatedStake(existingNodeKeyPair, sourceAddress, 100L, tokenLockReference = tokenLockRef),
        kp
      )
      existingStakeRef <- DelegatedStakeReference.of(existingStake)
      context = ctx.copy(activeDelegatedStakes =
        Some(
          SortedMap(
            sourceAddress -> SortedSet(
              DelegatedStakeRecord(existingStake, SnapshotOrdinal.unsafeApply(1L), Amount.empty)
            )
          )
        )
      )
      replacementStake <- Signed.forAsyncHasher(
        testCreateDelegatedStake(
          kp,
          sourceAddress,
          100L,
          tokenLockReference = tokenLockRef,
          parent = existingStakeRef
        ),
        kp
      )
      withdrawal <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, existingStakeRef.hash), kp)
      legacyResult <- acceptanceManager.accept(
        creates = List(replacementStake),
        withdrawals = List(withdrawal),
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress(NonNegLong(1L)),
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(2L)
      )
      hardenedResult <- acceptanceManager.accept(
        creates = List(replacementStake),
        withdrawals = List(withdrawal),
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress(NonNegLong(1L)),
        currentSnapshotOrdinal = activationOrdinal
      )
    } yield
      expect.all(
        legacyResult == UpdateDelegatedStakeAcceptanceResult(
          acceptedCreates = SortedMap(sourceAddress -> List((replacementStake, SnapshotOrdinal.unsafeApply(2L)))),
          notAcceptedCreates = List.empty,
          acceptedWithdrawals = SortedMap(sourceAddress -> List((withdrawal, EpochProgress(NonNegLong(1L))))),
          notAcceptedWithdrawals = List.empty
        ),
        hardenedResult == UpdateDelegatedStakeAcceptanceResult(
          acceptedCreates = SortedMap(sourceAddress -> List((replacementStake, activationOrdinal))),
          notAcceptedCreates = List.empty,
          acceptedWithdrawals = SortedMap.empty,
          notAcceptedWithdrawals = List(
            (
              withdrawal,
              NonEmptyChain.of(AlreadyWithdrawn(existingStakeRef.hash))
            )
          )
        )
      )
  }

  test("should preserve pending-lock behavior before activation and reject a second withdrawal at activation") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val activationOrdinal = SnapshotOrdinal.unsafeApply(3L)
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](
        UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
        activationOrdinal
      )

    for {
      existingNodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      ((tokenLockRef, _), ctx) <- mkValidGlobalContext(kp, existingNodeKeyPair, kp)
      withdrawnStake <- Signed.forAsyncHasher(
        testCreateDelegatedStake(existingNodeKeyPair, sourceAddress, 100L, tokenLockReference = tokenLockRef),
        kp
      )
      withdrawnStakeRef <- DelegatedStakeReference.of(withdrawnStake)
      activeStake <- Signed.forAsyncHasher(
        testCreateDelegatedStake(
          kp,
          sourceAddress,
          100L,
          tokenLockReference = tokenLockRef,
          parent = withdrawnStakeRef
        ),
        kp
      )
      activeStakeRef <- DelegatedStakeReference.of(activeStake)
      context = ctx.copy(
        activeDelegatedStakes = Some(
          SortedMap(
            sourceAddress -> SortedSet(
              DelegatedStakeRecord(activeStake, SnapshotOrdinal.unsafeApply(2L), Amount.empty)
            )
          )
        ),
        delegatedStakesWithdrawals = Some(
          SortedMap(
            sourceAddress -> SortedSet(
              PendingDelegatedStakeWithdrawal(
                withdrawnStake,
                Amount.empty,
                SnapshotOrdinal.unsafeApply(1L),
                EpochProgress(NonNegLong(1L))
              )
            )
          )
        )
      )
      withdrawal <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, activeStakeRef.hash), kp)
      legacyResult <- acceptanceManager.accept(
        creates = List.empty,
        withdrawals = List(withdrawal),
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress(NonNegLong(2L)),
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(2L)
      )
      hardenedResult <- acceptanceManager.accept(
        creates = List.empty,
        withdrawals = List(withdrawal),
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress(NonNegLong(2L)),
        currentSnapshotOrdinal = activationOrdinal
      )
    } yield
      expect.all(
        legacyResult == UpdateDelegatedStakeAcceptanceResult(
          acceptedCreates = SortedMap.empty,
          notAcceptedCreates = List.empty,
          acceptedWithdrawals = SortedMap(sourceAddress -> List((withdrawal, EpochProgress(NonNegLong(2L))))),
          notAcceptedWithdrawals = List.empty
        ),
        hardenedResult == UpdateDelegatedStakeAcceptanceResult(
          acceptedCreates = SortedMap.empty,
          notAcceptedCreates = List.empty,
          acceptedWithdrawals = SortedMap.empty,
          notAcceptedWithdrawals = List((withdrawal, NonEmptyChain.of(AlreadyWithdrawn(tokenLockRef))))
        )
      )
  }

  test("should accept at most one withdrawal per token lock in a snapshot") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](
        UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
        SnapshotOrdinal.MinValue
      )

    for {
      alternateNodeKeyPair <- KeyPairGenerator.makeKeyPair[IO]
      ((tokenLockRef, _), ctx) <- mkValidGlobalContext(kp, alternateNodeKeyPair, kp)
      firstStake <- Signed.forAsyncHasher(
        testCreateDelegatedStake(alternateNodeKeyPair, sourceAddress, 100L, tokenLockReference = tokenLockRef),
        kp
      )
      firstStakeRef <- DelegatedStakeReference.of(firstStake)
      secondStake <- Signed.forAsyncHasher(
        testCreateDelegatedStake(
          kp,
          sourceAddress,
          100L,
          tokenLockReference = tokenLockRef,
          parent = firstStakeRef
        ),
        kp
      )
      secondStakeRef <- DelegatedStakeReference.of(secondStake)
      context = ctx.copy(activeDelegatedStakes =
        Some(
          SortedMap(
            sourceAddress -> SortedSet(
              DelegatedStakeRecord(firstStake, SnapshotOrdinal.unsafeApply(1L), Amount.empty),
              DelegatedStakeRecord(secondStake, SnapshotOrdinal.unsafeApply(2L), Amount.empty)
            )
          )
        )
      )
      firstWithdrawal <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, firstStakeRef.hash), kp)
      secondWithdrawal <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, secondStakeRef.hash), kp)
      result <- acceptanceManager.accept(
        creates = List.empty,
        withdrawals = List(firstWithdrawal, secondWithdrawal),
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress(NonNegLong(2L)),
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(3L)
      )
    } yield
      expect(
        result == UpdateDelegatedStakeAcceptanceResult(
          acceptedCreates = SortedMap.empty,
          notAcceptedCreates = List.empty,
          acceptedWithdrawals = SortedMap(
            sourceAddress -> List((firstWithdrawal, EpochProgress(NonNegLong(2L))))
          ),
          notAcceptedWithdrawals = List((secondWithdrawal, NonEmptyChain.of(DuplicatedTokenLock(tokenLockRef))))
        )
      )
  }

  def testCreateDelegatedStake(
    keyPair: KeyPair,
    sourceAddress: Address,
    amount: Long,
    tokenLockReference: Hash = Hash.empty,
    parent: DelegatedStakeReference = DelegatedStakeReference.empty
  ): UpdateDelegatedStake.Create = UpdateDelegatedStake.Create(
    source = sourceAddress,
    nodeId = PeerId.fromPublic(keyPair.getPublic),
    amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount)),
    tokenLockRef = tokenLockReference,
    parent = parent
  )

  def testWithdrawDelegatedStake(
    sourceAddress: Address,
    stakeRef: Hash = DelegatedStakeReference.empty.hash
  ): UpdateDelegatedStake.Withdraw = UpdateDelegatedStake.Withdraw(
    source = sourceAddress,
    stakeRef = stakeRef
  )

  def testTokenLock(
    keyPair: KeyPair,
    amount: Long,
    tokenLockUnlockEpoch: Option[EpochProgress] = None,
    parent: TokenLockReference = TokenLockReference.empty
  )(
    implicit sp: SecurityProvider[IO],
    h: Hasher[IO]
  ) = {
    val testTokenLock = TokenLock(
      source = keyPair.getPublic.toAddress,
      amount = TokenLockAmount(PosLong.unsafeFrom(amount)),
      fee = TokenLockFee(NonNegLong(0L)),
      parent = parent,
      currencyId = None,
      unlockEpoch = tokenLockUnlockEpoch
    )
    for {
      signed <- forAsyncHasher(testTokenLock, keyPair)
      ref <- TokenLockReference.of(signed)
    } yield (ref, SortedMap(keyPair.getPublic.toAddress -> SortedSet(signed)))
  }

  def mkValidGlobalContext(
    keyPair1: KeyPair,
    keyPair2: KeyPair,
    tokenLockKeyPair: KeyPair,
    tokenLockUnlockEpoch: Option[EpochProgress] = None
  )(
    implicit sp: SecurityProvider[IO],
    h: Hasher[IO]
  ) =
    for {
      (ref1, tokenLocks1) <- testTokenLock(tokenLockKeyPair, 100L, tokenLockUnlockEpoch)
      (ref2, tokenLocks2) <- testTokenLock(tokenLockKeyPair, 200L, tokenLockUnlockEpoch, ref1)
      address1 = keyPair1.getPublic.toAddress
      nodeId1 = keyPair1.getPublic.toId
      address2 = keyPair2.getPublic.toAddress
      nodeId2 = keyPair2.getPublic.toId
      nodeParams = SortedMap(
        nodeId1 -> (
          Signed(
            UpdateNodeParameters(
              address1,
              delegatedStakeRewardParameters = DelegatedStakeRewardParameters(
                RewardFraction.unsafeFrom(80000000) // 80% to delegator
              ),
              NodeMetadataParameters("", ""),
              UpdateNodeParametersReference(UpdateNodeParametersOrdinal(NonNegLong.unsafeFrom(0)), Hash.empty)
            ),
            NonEmptySet.one[SignatureProof](SignatureProof(nodeId1, Signature(Hex(Hash.empty.value))))
          ),
          SnapshotOrdinal.unsafeApply(1L)
        ),
        nodeId2 -> (
          Signed(
            UpdateNodeParameters(
              address2,
              delegatedStakeRewardParameters = DelegatedStakeRewardParameters(
                RewardFraction.unsafeFrom(80000000) // 80% to delegator
              ),
              NodeMetadataParameters("", ""),
              UpdateNodeParametersReference(UpdateNodeParametersOrdinal(NonNegLong.unsafeFrom(0)), Hash.empty)
            ),
            NonEmptySet.one[SignatureProof](SignatureProof(nodeId2, Signature(Hex(Hash.empty.value))))
          ),
          SnapshotOrdinal.unsafeApply(1L)
        )
      )
      tokenLocks = tokenLocks1(address1) ++ tokenLocks2(address1)
    } yield ((ref1.hash, ref2.hash), mkGlobalContext(tokenLocks = SortedMap(address1 -> tokenLocks), nodeParams = nodeParams))

}
