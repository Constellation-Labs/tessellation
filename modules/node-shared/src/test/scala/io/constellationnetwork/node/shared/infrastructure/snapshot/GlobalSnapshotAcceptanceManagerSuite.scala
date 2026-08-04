package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.IO

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.TokenUnlock
import io.constellationnetwork.schema.balance.Amount
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import weaver.SimpleIOSuite

object GlobalSnapshotAcceptanceManagerSuite extends SimpleIOSuite {

  test("withdrawal unlock generation preserves legacy behavior before activation and credits once at activation") {
    val source = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
    val tokenLockRef = Hash("1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef")
    val nodeId1 = Id(Hex("1234567890abcdef")).toPeerId
    val nodeId2 = Id(Hex("abcdef1234567890")).toPeerId
    val proof = NonEmptySet.one(SignatureProof(nodeId1.toId, Signature(Hex(Hash.empty.value))))
    val amount = TokenLockAmount(PosLong.unsafeFrom(500000000000L))
    val activeTokenLock = Signed(
      TokenLock(
        source = source,
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
        source = source,
        nodeId = nodeId1,
        amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount.value.value)),
        tokenLockRef = tokenLockRef
      ),
      proof
    )
    val secondStake = Signed(
      UpdateDelegatedStake.Create(
        source = source,
        nodeId = nodeId2,
        amount = DelegatedStakeAmount(NonNegLong.unsafeFrom(amount.value.value)),
        tokenLockRef = tokenLockRef
      ),
      proof
    )
    val expiredWithdrawals = SortedMap(
      source -> SortedSet(
        PendingDelegatedStakeWithdrawal(firstStake, Amount.empty, SnapshotOrdinal.unsafeApply(1L), EpochProgress(1L)),
        PendingDelegatedStakeWithdrawal(secondStake, Amount.empty, SnapshotOrdinal.unsafeApply(2L), EpochProgress(1L))
      )
    )

    val activationOrdinal = SnapshotOrdinal.unsafeApply(10L)
    val legacyResult = GlobalSnapshotAcceptanceManager.generateTokenUnlocks(
      expiredWithdrawals,
      Map(tokenLockRef -> activeTokenLock),
      SnapshotOrdinal.unsafeApply(9L),
      activationOrdinal
    )
    val hardenedResult = GlobalSnapshotAcceptanceManager.generateTokenUnlocks(
      expiredWithdrawals,
      Map(tokenLockRef -> activeTokenLock),
      activationOrdinal,
      activationOrdinal
    )

    IO(
      expect.all(
        legacyResult == Right(
          Map(
            source -> List(
              TokenUnlock(tokenLockRef, amount, currencyId = None, source),
              TokenUnlock(tokenLockRef, amount, currencyId = None, source)
            )
          )
        ),
        hardenedResult == Right(
          Map(
            source -> List(TokenUnlock(tokenLockRef, amount, currencyId = None, source))
          )
        )
      )
    )
  }
}
