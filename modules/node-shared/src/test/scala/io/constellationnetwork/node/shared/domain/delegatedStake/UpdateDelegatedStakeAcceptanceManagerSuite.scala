package io.constellationnetwork.node.shared.domain.delegatedStake

import java.security.KeyPair

import cats.data.{NonEmptyChain, NonEmptySet}
import cats.effect.IO
import cats.effect.kernel.Resource
import cats.syntax.all._

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
    implicit0(j: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    h = Hasher.forJson[IO]
    kp <- KeyPairGenerator.makeKeyPair[IO].asResource
    sourceAddress <- kp.getPublic.toId.toAddress.asResource
  } yield (j, h, sp, kp, sourceAddress)

  test("should reject stakes with the same parent") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None))
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
                DelegatedStakeRecord(parent1, SnapshotOrdinal.MinValue, Amount(NonNegLong(0L)), None, None)
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
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(2),
        acceptedTokenLocks = List.empty
      )
    } yield {
      // After defensive sorting, the first-wins duplicate resolution may accept either one.
      // We verify that exactly one is accepted and the other is rejected with DuplicatedParent.
      val allAcceptedCreates = res.acceptedCreates.values.flatten.map(_._1).toList
      val allRejectedCreates = res.notAcceptedCreates.map(_._1)
      expect.all(
        allAcceptedCreates.size == 1,
        allRejectedCreates.size == 1,
        Set(valid1, invalid).contains(allAcceptedCreates.head),
        Set(valid1, invalid).contains(allRejectedCreates.head),
        allAcceptedCreates.head != allRejectedCreates.head,
        res.notAcceptedCreates.head._2 == NonEmptyChain.of(DuplicatedParent(lastRef1)),
        res.acceptedWithdrawals.isEmpty,
        res.notAcceptedWithdrawals.isEmpty
      )
    }
  }

  test("should reject withdrawals with the same parent") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None))
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
                DelegatedStakeRecord(parent1, SnapshotOrdinal.MinValue, Amount(NonNegLong(0L)), None, None)
              )
          )
        )
      )
      withdraw1 <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, lastRef1.hash), kp)
      withdraw2 <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, lastRef1.hash), kp)
      res <- acceptanceManager.accept(
        creates = List.empty,
        withdrawals = List(withdraw1, withdraw2),
        lastSnapshotContext = context,
        currentGlobalEpochProgress = EpochProgress.apply(NonNegLong(1)),
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(2),
        acceptedTokenLocks = List.empty
      )
    } yield {
      // After defensive sorting, first-wins duplicate resolution may accept either one.
      val allAcceptedWithdrawals = res.acceptedWithdrawals.values.flatten.map(_._1).toList
      val allRejectedWithdrawals = res.notAcceptedWithdrawals.map(_._1)
      expect.all(
        res.acceptedCreates.isEmpty,
        res.notAcceptedCreates.isEmpty,
        allAcceptedWithdrawals.size == 1,
        allRejectedWithdrawals.size == 1,
        Set(withdraw1, withdraw2).contains(allAcceptedWithdrawals.head),
        Set(withdraw1, withdraw2).contains(allRejectedWithdrawals.head),
        allAcceptedWithdrawals.head != allRejectedWithdrawals.head,
        res.notAcceptedWithdrawals.head._2 == NonEmptyChain.of(DuplicatedStake(withdraw1.stakeRef))
      )
    }
  }

  test("should accept withdrawals with different parents") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager =
      UpdateDelegatedStakeAcceptanceManager.make[IO](UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None))
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
                DelegatedStakeRecord(parent1, SnapshotOrdinal.MinValue, Amount(NonNegLong(0L)), None, None),
                DelegatedStakeRecord(parent2, SnapshotOrdinal.MinValue, Amount(NonNegLong(0L)), None, None)
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
        currentSnapshotOrdinal = SnapshotOrdinal.unsafeApply(2),
        acceptedTokenLocks = List.empty
      )
    } yield {
      val allAcceptedWithdrawals = res.acceptedWithdrawals.values.flatten.map(_._1).toSet
      expect.all(
        res.acceptedCreates.isEmpty,
        res.notAcceptedCreates.isEmpty,
        allAcceptedWithdrawals.size == 2,
        allAcceptedWithdrawals.contains(valid1),
        allAcceptedWithdrawals.contains(valid2),
        res.notAcceptedWithdrawals.isEmpty
      )
    }
  }

  test("preserves create-withdraw legacy behavior before activation and makes create win at activation using the effective lock ref") {
    res =>
      implicit val (_, h, sp, kp, sourceAddress) = res
      val activation = SnapshotOrdinal.unsafeApply(10L)
      val acceptanceManager = UpdateDelegatedStakeAcceptanceManager.make[IO](
        UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
        activation
      )

      for {
        newNodeKp <- KeyPairGenerator.makeKeyPair[IO]
        oldNodeKp <- KeyPairGenerator.makeKeyPair[IO]
        ((originalLockRef, replacementLockRef), baseContext) <- mkValidGlobalContext(kp, newNodeKp, kp)
        existing <- Signed.forAsyncHasher(testCreateDelegatedStake(oldNodeKp, sourceAddress, 100L, originalLockRef), kp)
        existingRef <- DelegatedStakeReference.of(existing)
        existingRecord = DelegatedStakeRecord(
          existing,
          SnapshotOrdinal.MinValue,
          Amount.empty,
          replacementLockRef.some,
          DelegatedStakeAmount(NonNegLong.unsafeFrom(200L)).some
        )
        context = baseContext.copy(activeDelegatedStakes = SortedMap(sourceAddress -> SortedSet(existingRecord)).some)
        replacementCreate <- Signed.forAsyncHasher(
          testCreateDelegatedStake(newNodeKp, sourceAddress, 200L, replacementLockRef, existingRef),
          kp
        )
        withdrawal <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, existingRef.hash), kp)
        beforeActivation <- acceptanceManager.accept(
          List(replacementCreate),
          List(withdrawal),
          context,
          EpochProgress.MinValue,
          SnapshotOrdinal.unsafeApply(9L),
          List.empty
        )
        atActivation <- acceptanceManager.accept(
          List(replacementCreate),
          List(withdrawal),
          context,
          EpochProgress.MinValue,
          activation,
          List.empty
        )
      } yield
        expect.all(
          beforeActivation.acceptedCreates.values.flatten.size == 1,
          beforeActivation.acceptedWithdrawals.values.flatten.size == 1,
          beforeActivation.notAcceptedWithdrawals.isEmpty,
          atActivation.acceptedCreates.values.flatten.size == 1,
          atActivation.acceptedWithdrawals.isEmpty,
          atActivation.notAcceptedWithdrawals.map(_._2.head) == List(DuplicatedTokenLock(replacementLockRef))
        )
  }

  test("accepts at most one withdrawal for different stake refs sharing an effective token lock") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager = UpdateDelegatedStakeAcceptanceManager.make[IO](
      UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
      SnapshotOrdinal.MinValue
    )

    for {
      node1 <- KeyPairGenerator.makeKeyPair[IO]
      node2 <- KeyPairGenerator.makeKeyPair[IO]
      ((lockRef1, lockRef2), baseContext) <- mkValidGlobalContext(kp, node1, kp)
      stake1 <- Signed.forAsyncHasher(testCreateDelegatedStake(node1, sourceAddress, 100L, lockRef1), kp)
      stake2 <- Signed.forAsyncHasher(testCreateDelegatedStake(node2, sourceAddress, 200L, lockRef2), kp)
      stakeRef1 <- DelegatedStakeReference.of(stake1)
      stakeRef2 <- DelegatedStakeReference.of(stake2)
      context = baseContext.copy(activeDelegatedStakes =
        SortedMap(
          sourceAddress -> SortedSet(
            DelegatedStakeRecord(
              stake1,
              SnapshotOrdinal.MinValue,
              Amount.empty,
              lockRef2.some,
              DelegatedStakeAmount(NonNegLong.unsafeFrom(200L)).some
            ),
            DelegatedStakeRecord(
              stake2,
              SnapshotOrdinal.unsafeApply(1L),
              Amount.empty,
              lockRef2.some,
              DelegatedStakeAmount(NonNegLong.unsafeFrom(200L)).some
            )
          )
        ).some
      )
      withdrawal1 <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, stakeRef1.hash), kp)
      withdrawal2 <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, stakeRef2.hash), kp)
      result <- acceptanceManager.accept(
        List.empty,
        List(withdrawal1, withdrawal2),
        context,
        EpochProgress.MinValue,
        SnapshotOrdinal.MinValue,
        List.empty
      )
      reversedResult <- acceptanceManager.accept(
        List.empty,
        List(withdrawal2, withdrawal1),
        context,
        EpochProgress.MinValue,
        SnapshotOrdinal.MinValue,
        List.empty
      )
    } yield
      expect.all(
        result.acceptedWithdrawals.values.flatten.size == 1,
        result.notAcceptedWithdrawals.size == 1,
        result.notAcceptedWithdrawals.head._2.head == DuplicatedTokenLock(lockRef2),
        result.acceptedWithdrawals == reversedResult.acceptedWithdrawals,
        result.notAcceptedWithdrawals == reversedResult.notAcceptedWithdrawals
      )
  }

  test("rejects a withdrawal whose effective token lock is already pending") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptanceManager = UpdateDelegatedStakeAcceptanceManager.make[IO](
      UpdateDelegatedStakeValidator.make[IO](SignedValidator.make[IO], None),
      SnapshotOrdinal.MinValue
    )

    for {
      node1 <- KeyPairGenerator.makeKeyPair[IO]
      node2 <- KeyPairGenerator.makeKeyPair[IO]
      ((lockRef1, lockRef2), baseContext) <- mkValidGlobalContext(kp, node1, kp)
      activeStake <- Signed.forAsyncHasher(testCreateDelegatedStake(node1, sourceAddress, 100L, lockRef1), kp)
      pendingStake <- Signed.forAsyncHasher(testCreateDelegatedStake(node2, sourceAddress, 200L, lockRef2), kp)
      activeStakeRef <- DelegatedStakeReference.of(activeStake)
      activeRecord = DelegatedStakeRecord(activeStake, SnapshotOrdinal.MinValue, Amount.empty, lockRef2.some, None)
      pendingRecord = PendingDelegatedStakeWithdrawal(
        pendingStake,
        Amount(NonNegLong.unsafeFrom(50L)),
        SnapshotOrdinal.MinValue,
        EpochProgress.MinValue,
        lockRef2.some,
        None
      )
      context = baseContext.copy(
        activeDelegatedStakes = SortedMap(sourceAddress -> SortedSet(activeRecord)).some,
        delegatedStakesWithdrawals = SortedMap(sourceAddress -> SortedSet(pendingRecord)).some
      )
      withdrawal <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, activeStakeRef.hash), kp)
      result <- acceptanceManager.accept(
        List.empty,
        List(withdrawal),
        context,
        EpochProgress.MinValue,
        SnapshotOrdinal.MinValue,
        List.empty
      )
    } yield
      expect.all(
        result.acceptedWithdrawals.isEmpty,
        result.notAcceptedWithdrawals.map(_._2.head) == List(AlreadyWithdrawn(lockRef2))
      )
  }

  test("an invalid canonical-first create does not reserve its parent or token lock after activation") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val activation = SnapshotOrdinal.unsafeApply(10L)

    def validatorRejecting(
      invalid: Signed[UpdateDelegatedStake.Create]
    ): UpdateDelegatedStakeValidator[IO] = new UpdateDelegatedStakeValidator[IO] {
      def validateCreateDelegatedStake(
        signed: Signed[UpdateDelegatedStake.Create],
        lastContext: io.constellationnetwork.schema.GlobalSnapshotInfo
      ): IO[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]]] =
        if (signed == invalid) InvalidParent(signed.parent).invalidNec.pure[IO]
        else signed.validNec.pure[IO]

      def validateWithdrawDelegatedStake(
        signed: Signed[UpdateDelegatedStake.Withdraw],
        lastContext: io.constellationnetwork.schema.GlobalSnapshotInfo
      ): IO[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]]] = signed.validNec.pure[IO]
    }

    for {
      node <- KeyPairGenerator.makeKeyPair[IO]
      create1 <- Signed.forAsyncHasher(testCreateDelegatedStake(node, sourceAddress, 100L, Hash("shared-lock")), kp)
      create2 = Signed(
        create1.value,
        NonEmptySet.one(
          SignatureProof(create1.proofs.head.id, Signature(Hex(Hash.empty.value.dropRight(2) + "01")))
        )
      )
      canonical = List(create1, create2).sorted(Signed.order[UpdateDelegatedStake.Create].toOrdering)
      legacyCanonical = List(create1, create2).sortBy(_.show)
      acceptanceManager = UpdateDelegatedStakeAcceptanceManager.make[IO](validatorRejecting(canonical.head), activation)
      legacyAcceptanceManager = UpdateDelegatedStakeAcceptanceManager.make[IO](validatorRejecting(legacyCanonical.head), activation)
      reverseResult <- acceptanceManager.accept(
        canonical.reverse,
        List.empty,
        io.constellationnetwork.schema.GlobalSnapshotInfo.empty,
        EpochProgress.MinValue,
        activation,
        List.empty
      )
      forwardResult <- acceptanceManager.accept(
        canonical,
        List.empty,
        io.constellationnetwork.schema.GlobalSnapshotInfo.empty,
        EpochProgress.MinValue,
        activation,
        List.empty
      )
      legacyResult <- legacyAcceptanceManager.accept(
        legacyCanonical,
        List.empty,
        io.constellationnetwork.schema.GlobalSnapshotInfo.empty,
        EpochProgress.MinValue,
        SnapshotOrdinal.unsafeApply(9L),
        List.empty
      )
    } yield
      expect.all(
        reverseResult.acceptedCreates.values.flatten.map(_._1).toList == List(canonical.last),
        reverseResult.notAcceptedCreates.map(_._1) == List(canonical.head),
        reverseResult.notAcceptedCreates.head._2.head == InvalidParent(create1.parent),
        reverseResult == forwardResult,
        legacyResult.acceptedCreates.isEmpty,
        legacyResult.notAcceptedCreates.size == 2,
        legacyResult.notAcceptedCreates.exists(_._2.head == DuplicatedParent(create1.parent))
      )
  }

  test("an invalid canonical-first withdrawal does not reserve its stake or effective token lock") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res

    for {
      node1 <- KeyPairGenerator.makeKeyPair[IO]
      ((lockRef1, _), baseContext) <- mkValidGlobalContext(kp, node1, kp)
      stake1 <- Signed.forAsyncHasher(testCreateDelegatedStake(node1, sourceAddress, 100L, lockRef1), kp)
      stakeRef1 <- DelegatedStakeReference.of(stake1)
      context = baseContext.copy(activeDelegatedStakes =
        SortedMap(
          sourceAddress -> SortedSet(
            DelegatedStakeRecord(stake1, SnapshotOrdinal.MinValue, Amount.empty, None, None)
          )
        ).some
      )
      withdrawal1 <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, stakeRef1.hash), kp)
      withdrawal2 = Signed(
        withdrawal1.value,
        NonEmptySet.one(
          SignatureProof(withdrawal1.proofs.head.id, Signature(Hex(Hash.empty.value.dropRight(2) + "01")))
        )
      )
      canonical = List(withdrawal1, withdrawal2).sorted(Signed.order[UpdateDelegatedStake.Withdraw].toOrdering)
      validator = new UpdateDelegatedStakeValidator[IO] {
        def validateCreateDelegatedStake(
          signed: Signed[UpdateDelegatedStake.Create],
          lastContext: io.constellationnetwork.schema.GlobalSnapshotInfo
        ): IO[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]]] = signed.validNec.pure[IO]

        def validateWithdrawDelegatedStake(
          signed: Signed[UpdateDelegatedStake.Withdraw],
          lastContext: io.constellationnetwork.schema.GlobalSnapshotInfo
        ): IO[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]]] =
          if (signed == canonical.head) InvalidStake(signed.stakeRef).invalidNec.pure[IO]
          else signed.validNec.pure[IO]
      }
      acceptanceManager = UpdateDelegatedStakeAcceptanceManager.make[IO](validator, SnapshotOrdinal.MinValue)
      result <- acceptanceManager.accept(
        List.empty,
        canonical.reverse,
        context,
        EpochProgress.MinValue,
        SnapshotOrdinal.MinValue,
        List.empty
      )
      forwardResult <- acceptanceManager.accept(
        List.empty,
        canonical,
        context,
        EpochProgress.MinValue,
        SnapshotOrdinal.MinValue,
        List.empty
      )
    } yield
      expect.all(
        result.acceptedWithdrawals.values.flatten.map(_._1).toList == List(canonical.last),
        result.notAcceptedWithdrawals.map(_._1) == List(canonical.head),
        result.notAcceptedWithdrawals.head._2.head == InvalidStake(canonical.head.stakeRef),
        result == forwardResult
      )
  }

  test("fails closed after activation when validator and GSI disagree about the withdrawn stake") { res =>
    implicit val (_, h, sp, kp, sourceAddress) = res
    val acceptingValidator = new UpdateDelegatedStakeValidator[IO] {
      def validateCreateDelegatedStake(
        signed: Signed[UpdateDelegatedStake.Create],
        lastContext: io.constellationnetwork.schema.GlobalSnapshotInfo
      ) = signed.validNec.pure[IO]

      def validateWithdrawDelegatedStake(
        signed: Signed[UpdateDelegatedStake.Withdraw],
        lastContext: io.constellationnetwork.schema.GlobalSnapshotInfo
      ) = signed.validNec.pure[IO]
    }
    val acceptanceManager = UpdateDelegatedStakeAcceptanceManager.make[IO](acceptingValidator, SnapshotOrdinal.MinValue)

    for {
      withdrawal <- Signed.forAsyncHasher(testWithdrawDelegatedStake(sourceAddress, Hash("missing-stake")), kp)
      result <- acceptanceManager.accept(
        List.empty,
        List(withdrawal),
        io.constellationnetwork.schema.GlobalSnapshotInfo.empty,
        EpochProgress.MinValue,
        SnapshotOrdinal.MinValue,
        List.empty
      )
    } yield
      expect.all(
        result.acceptedWithdrawals.isEmpty,
        result.notAcceptedWithdrawals.map(_._2.head) == List(InvalidStake(Hash("missing-stake")))
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
      unlockEpoch = tokenLockUnlockEpoch,
      replaceTokenLockRef = None
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
