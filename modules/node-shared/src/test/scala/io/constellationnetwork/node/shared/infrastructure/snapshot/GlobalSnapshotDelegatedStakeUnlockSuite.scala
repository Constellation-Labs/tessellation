package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.IO
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.{SharedArtifact, TokenUnlock}
import io.constellationnetwork.schema.balance.{Amount, Balance}
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.SimpleIOSuite

object GlobalSnapshotDelegatedStakeUnlockSuite extends SimpleIOSuite {

  test("preserves duplicate unlocks at A-1 and credits a pre-existing duplicate token lock once at A") {
    val tokenLockOwner = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
    val incorrectWithdrawalBucket = Address("DAG8hGZnBCZiiFTJwYr4BnZtHQcFEUbxo1jxmK3r")
    val tokenLockRef = Hash("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")
    val firstNodeId = Id(Hex("1234567890abcdef")).toPeerId
    val secondNodeId = Id(Hex("abcdef1234567890")).toPeerId
    val proof = NonEmptySet.one(SignatureProof(firstNodeId.toId, Signature(Hex(Hash.empty.value))))
    val amount = TokenLockAmount(PosLong.unsafeFrom(500000000000L))
    val activeTokenLock = Signed(
      TokenLock(
        source = tokenLockOwner,
        amount = amount,
        fee = TokenLockFee(NonNegLong.MinValue),
        parent = TokenLockReference.empty,
        currencyId = None,
        unlockEpoch = None
      ),
      proof
    )
    val firstStake = Signed(
      UpdateDelegatedStake.Create(
        source = tokenLockOwner,
        nodeId = firstNodeId,
        amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount.value.value)),
        tokenLockRef = tokenLockRef
      ),
      proof
    )
    val secondStake = Signed(
      UpdateDelegatedStake.Create(
        source = tokenLockOwner,
        nodeId = secondNodeId,
        amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount.value.value)),
        tokenLockRef = tokenLockRef
      ),
      proof
    )
    val expiredWithdrawals = SortedMap(
      incorrectWithdrawalBucket -> SortedSet(
        PendingDelegatedStakeWithdrawal(firstStake, Amount.empty, SnapshotOrdinal.unsafeApply(1L), EpochProgress(1L)),
        PendingDelegatedStakeWithdrawal(secondStake, Amount.empty, SnapshotOrdinal.unsafeApply(2L), EpochProgress(2L))
      )
    )
    val expectedUnlock = TokenUnlock(tokenLockRef, amount, currencyId = None, tokenLockOwner)
    val activationOrdinal = SnapshotOrdinal.unsafeApply(10L)

    val before = GlobalSnapshotAcceptanceManager.generateDelegatedStakeTokenUnlocks(
      expiredWithdrawals,
      Map(tokenLockRef -> activeTokenLock),
      SnapshotOrdinal.unsafeApply(9L),
      activationOrdinal
    )
    val at = GlobalSnapshotAcceptanceManager.generateDelegatedStakeTokenUnlocks(
      expiredWithdrawals,
      Map(tokenLockRef -> activeTokenLock),
      activationOrdinal,
      activationOrdinal
    )

    IO(
      expect.all(
        before == Right(Map(incorrectWithdrawalBucket -> List(expectedUnlock, expectedUnlock))),
        at == Right(Map(tokenLockOwner -> List(expectedUnlock))),
        GlobalSnapshotAcceptanceManager
          .excludeNaturallyExpiredDelegatedStakeUnlocks(
            at.toOption.get,
            Set(tokenLockRef),
            fixActive = true
          )
          .isEmpty,
        GlobalSnapshotAcceptanceManager.excludeNaturallyExpiredDelegatedStakeUnlocks(
          before.toOption.get,
          Set(tokenLockRef),
          fixActive = false
        ) == before.toOption.get
      )
    )
  }

  test("natural expiry suppresses the overlapping delegated unlock at activation") {
    val source = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
    val amount = TokenLockAmount(PosLong.unsafeFrom(500000000000L))
    val tokenLockRef = Hash("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")
    val unlock = TokenUnlock(tokenLockRef, amount, currencyId = None, source)
    val generated = Map(source -> List(unlock))
    val deduplicated = GlobalSnapshotAcceptanceManager.excludeNaturallyExpiredDelegatedStakeUnlocks(
      generated,
      Set(tokenLockRef),
      fixActive = true
    )

    IO(
      expect.all(
        deduplicated.isEmpty
      )
    )
  }

  test("the wired transition finalizes duplicate pending withdrawals exactly once at activation") {
    JsonSerializer.forSync[IO].flatMap { implicit serializer =>
      implicit val hasher: Hasher[IO] = Hasher.forJson[IO]
      val owner = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
      val staleBucket = Address("DAG8hGZnBCZiiFTJwYr4BnZtHQcFEUbxo1jxmK3r")
      val firstNodeId = Id(Hex("1234567890abcdef")).toPeerId
      val secondNodeId = Id(Hex("abcdef1234567890")).toPeerId
      val proof = NonEmptySet.one(SignatureProof(firstNodeId.toId, Signature(Hex(Hash.empty.value))))
      val amount = TokenLockAmount(PosLong.unsafeFrom(500000000000L))
      val activeTokenLock = Signed(
        TokenLock(owner, amount, TokenLockFee(NonNegLong.MinValue), TokenLockReference.empty, None, None),
        proof
      )
      val activationOrdinal = SnapshotOrdinal.unsafeApply(10L)
      val startingBalance = Balance(NonNegLong.unsafeFrom(7L))

      for {
        hashedTokenLock <- activeTokenLock.toHashed
        tokenLockRef = hashedTokenLock.hash
        firstStake = Signed(
          UpdateDelegatedStake.Create(
            owner,
            firstNodeId,
            DelegatedStakeAmount(NonNegLong.unsafeFrom(amount.value.value)),
            tokenLockRef = tokenLockRef
          ),
          proof
        )
        secondStake = Signed(
          UpdateDelegatedStake.Create(
            owner,
            secondNodeId,
            DelegatedStakeAmount(NonNegLong.unsafeFrom(amount.value.value)),
            tokenLockRef = tokenLockRef
          ),
          proof
        )
        duplicatePending = SortedMap(
          staleBucket -> SortedSet(
            PendingDelegatedStakeWithdrawal(firstStake, Amount.empty, SnapshotOrdinal.unsafeApply(1L), EpochProgress(1L)),
            PendingDelegatedStakeWithdrawal(secondStake, Amount.empty, SnapshotOrdinal.unsafeApply(2L), EpochProgress(2L))
          )
        )
        generated <- IO.fromEither(
          GlobalSnapshotAcceptanceManager
            .generateDelegatedStakeTokenUnlocks(
              duplicatePending,
              Map(tokenLockRef -> activeTokenLock),
              activationOrdinal,
              activationOrdinal
            )
            .leftMap(error => new RuntimeException(error.toString))
        )
        transition <- GlobalSnapshotAcceptanceManager
          .applyDelegatedStakeTokenLockTransition[IO](
            EpochProgress(NonNegLong.unsafeFrom(3L)),
            activationOrdinal,
            activationOrdinal,
            SnapshotOrdinal.MinValue,
            SortedMap(owner -> startingBalance),
            SortedMap.empty,
            SortedMap(owner -> SortedSet(activeTokenLock)),
            Map(tokenLockRef -> activeTokenLock),
            generated,
            duplicatePending,
            duplicatePending
          )
          .flatMap(result => IO.fromEither(result.leftMap(error => new RuntimeException(error.toString))))
        artifacts = transition.naturallyExpiredArtifacts ++ transition.generatedArtifacts
        expectedUnlock = TokenUnlock(tokenLockRef, amount, None, owner)
      } yield
        expect.all(
          transition.generatedTokenUnlocks == Map(owner -> List(expectedUnlock)),
          transition.balances.get(owner).contains(Balance(NonNegLong.unsafeFrom(startingBalance.value.value + amount.value.value))),
          transition.activeTokenLocks.isEmpty,
          transition.pendingWithdrawals.isEmpty,
          artifacts == SortedSet[SharedArtifact](expectedUnlock)
        )
    }
  }

  test("the wired transition treats only unlock epochs below the current epoch as naturally expired") {
    JsonSerializer.forSync[IO].flatMap { implicit serializer =>
      implicit val hasher: Hasher[IO] = Hasher.forJson[IO]
      val owner = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
      val nodeId = Id(Hex("1234567890abcdef")).toPeerId
      val proof = NonEmptySet.one(SignatureProof(nodeId.toId, Signature(Hex(Hash.empty.value))))
      val amount = TokenLockAmount(PosLong.unsafeFrom(500000000000L))
      val epochProgress = EpochProgress(NonNegLong.unsafeFrom(10L))
      val activationOrdinal = SnapshotOrdinal.unsafeApply(10L)

      def run(unlockEpoch: EpochProgress): IO[GlobalSnapshotAcceptanceManager.DelegatedStakeTokenLockTransition] = {
        val activeTokenLock = Signed(
          TokenLock(owner, amount, TokenLockFee(NonNegLong.MinValue), TokenLockReference.empty, None, Some(unlockEpoch)),
          proof
        )

        for {
          hashedTokenLock <- activeTokenLock.toHashed
          tokenLockRef = hashedTokenLock.hash
          stake = Signed(
            UpdateDelegatedStake.Create(
              owner,
              nodeId,
              DelegatedStakeAmount(NonNegLong.unsafeFrom(amount.value.value)),
              tokenLockRef = tokenLockRef
            ),
            proof
          )
          pending = SortedMap(
            owner -> SortedSet(
              PendingDelegatedStakeWithdrawal(stake, Amount.empty, SnapshotOrdinal.MinValue, EpochProgress.MinValue)
            )
          )
          generated <- IO.fromEither(
            GlobalSnapshotAcceptanceManager
              .generateDelegatedStakeTokenUnlocks(
                pending,
                Map(tokenLockRef -> activeTokenLock),
                activationOrdinal,
                activationOrdinal
              )
              .leftMap(error => new RuntimeException(error.toString))
          )
          transition <- GlobalSnapshotAcceptanceManager
            .applyDelegatedStakeTokenLockTransition[IO](
              epochProgress,
              activationOrdinal,
              activationOrdinal,
              SnapshotOrdinal.MinValue,
              SortedMap(owner -> Balance.empty),
              SortedMap.empty,
              SortedMap(owner -> SortedSet(activeTokenLock)),
              Map(tokenLockRef -> activeTokenLock),
              generated,
              pending,
              pending
            )
            .flatMap(result => IO.fromEither(result.leftMap(error => new RuntimeException(error.toString))))
        } yield transition
      }

      for {
        atBoundary <- run(epochProgress)
        beforeBoundary <- run(EpochProgress(NonNegLong.unsafeFrom(9L)))
      } yield
        expect.all(
          atBoundary.generatedTokenUnlocks.valuesIterator.flatten.size == 1,
          atBoundary.naturallyExpiredArtifacts.isEmpty,
          (atBoundary.naturallyExpiredArtifacts ++ atBoundary.generatedArtifacts).size == 1,
          atBoundary.balances.get(owner).contains(Balance(NonNegLong.unsafeFrom(amount.value.value))),
          atBoundary.activeTokenLocks.isEmpty,
          beforeBoundary.generatedTokenUnlocks.isEmpty,
          beforeBoundary.naturallyExpiredArtifacts.size == 1,
          beforeBoundary.generatedArtifacts.isEmpty,
          beforeBoundary.balances.get(owner).contains(Balance(NonNegLong.unsafeFrom(amount.value.value))),
          beforeBoundary.activeTokenLocks.isEmpty
        )
    }
  }
}
