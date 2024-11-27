package io.constellationnetwork.node.shared.domain.swap.block

import cats.data.NonEmptyList

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.BalanceArithmeticError
import io.constellationnetwork.schema.swap.{AllowSpendOrdinal, AllowSpendReference}
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.derive

@derive(eqv, show)
sealed trait AllowSpendBlockNotAcceptedReason

@derive(eqv, show)
sealed trait AllowSpendBlockRejectionReason extends AllowSpendBlockNotAcceptedReason

@derive(eqv, show)
case class ValidationFailed(reasons: NonEmptyList[AllowSpendBlockValidationError]) extends AllowSpendBlockRejectionReason

@derive(eqv, show)
case class RejectedAllowSpend(tx: AllowSpendReference, reason: AllowSpendRejectionReason) extends AllowSpendBlockRejectionReason

@derive(eqv, show)
sealed trait AllowSpendBlockAwaitReason extends AllowSpendBlockNotAcceptedReason

@derive(eqv, show)
case class AwaitingAllowSpend(tx: AllowSpendReference, reason: AllowSpendAwaitReason) extends AllowSpendBlockAwaitReason

@derive(eqv, show)
case class AddressBalanceOutOfRange(address: Address, error: BalanceArithmeticError) extends AllowSpendBlockAwaitReason

@derive(eqv, show)
case class SigningPeerBelowCollateral(peerIds: NonEmptyList[Address]) extends AllowSpendBlockAwaitReason

@derive(eqv, show)
sealed trait AllowSpendAwaitReason

@derive(eqv, show)
case class ParentOrdinalAboveLastTxOrdinal(parentOrdinal: AllowSpendOrdinal, lastTxOrdinal: AllowSpendOrdinal) extends AllowSpendAwaitReason

@derive(eqv, show)
sealed trait AllowSpendRejectionReason

@derive(eqv, show)
case class ParentHashNotEqLastTxHash(parentHash: Hash, lastTxHash: Hash) extends AllowSpendRejectionReason

@derive(eqv, show)
case class ParentOrdinalBelowLastTxOrdinal(parentOrdinal: AllowSpendOrdinal, lastTxOrdinal: AllowSpendOrdinal)
    extends AllowSpendRejectionReason
