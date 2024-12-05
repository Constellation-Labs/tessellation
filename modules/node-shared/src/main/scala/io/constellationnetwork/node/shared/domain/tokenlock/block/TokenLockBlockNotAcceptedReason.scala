package io.constellationnetwork.node.shared.domain.tokenlock.block

import cats.data.NonEmptyList

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.BalanceArithmeticError
import io.constellationnetwork.schema.tokenLock.{TokenLockOrdinal, TokenLockReference}
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
sealed trait TokenLockBlockNotAcceptedReason

@derive(eqv, show)
sealed trait TokenLockBlockRejectionReason extends TokenLockBlockNotAcceptedReason

@derive(eqv, show)
case class ValidationFailed(reasons: NonEmptyList[TokenLockBlockValidationError]) extends TokenLockBlockRejectionReason

@derive(eqv, show)
case class RejectedTokenLock(tx: TokenLockReference, reason: TokenLockRejectionReason) extends TokenLockBlockRejectionReason

@derive(eqv, show)
sealed trait TokenLockBlockAwaitReason extends TokenLockBlockNotAcceptedReason

@derive(eqv, show)
case class AwaitingTokenLock(tx: TokenLockReference, reason: TokenLockAwaitReason) extends TokenLockBlockAwaitReason

@derive(eqv, show)
case class AddressBalanceOutOfRange(address: Address, error: BalanceArithmeticError) extends TokenLockBlockAwaitReason

@derive(eqv, show)
case class SigningPeerBelowCollateral(peerIds: NonEmptyList[Address]) extends TokenLockBlockAwaitReason

@derive(eqv, show)
sealed trait TokenLockAwaitReason

@derive(eqv, show)
case class ParentOrdinalAboveLastTxOrdinal(parentOrdinal: TokenLockOrdinal, lastTxOrdinal: TokenLockOrdinal) extends TokenLockAwaitReason

@derive(eqv, show)
sealed trait TokenLockRejectionReason

@derive(eqv, show)
case class ParentHashNotEqLastTxHash(parentHash: Hash, lastTxHash: Hash) extends TokenLockRejectionReason

@derive(eqv, show)
case class ParentOrdinalBelowLastTxOrdinal(parentOrdinal: TokenLockOrdinal, lastTxOrdinal: TokenLockOrdinal)
    extends TokenLockRejectionReason
