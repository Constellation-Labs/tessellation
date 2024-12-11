package io.constellationnetwork.node.shared.domain.block.processing

import cats.data.NonEmptyList

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.BalanceArithmeticError
import io.constellationnetwork.schema.transaction.{TransactionOrdinal, TransactionReference}
import io.constellationnetwork.schema.{BlockReference, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.encoder
import derevo.derive

// NOTE: @derive(eqv, show) should not be required for each individual case class, but otherwise it throws in runtime

@derive(eqv, show)
sealed trait BlockNotAcceptedReason

@derive(eqv, show, encoder)
sealed trait BlockRejectionReason extends BlockNotAcceptedReason

@derive(eqv, show, encoder)
case class ValidationFailed(reasons: NonEmptyList[BlockValidationError]) extends BlockRejectionReason

@derive(eqv, show, encoder)
case class ParentNotFound(parent: BlockReference) extends BlockRejectionReason

@derive(eqv, show, encoder)
case object SnapshotOrdinalUnavailable extends BlockRejectionReason

@derive(eqv, show, encoder)
case object InvalidCurrencyMessageEvent extends BlockRejectionReason

@derive(eqv, show)
case class RejectedTransaction(tx: TransactionReference, reason: TransactionRejectionReason) extends BlockRejectionReason

@derive(eqv, show, encoder)
case class DataBlockNotAccepted(reason: String) extends BlockRejectionReason

@derive(eqv, show)
sealed trait BlockAwaitReason extends BlockNotAcceptedReason

@derive(eqv, show)
case class AwaitingTransaction(tx: TransactionReference, reason: TransactionAwaitReason) extends BlockAwaitReason

@derive(eqv, show)
case class AddressBalanceOutOfRange(address: Address, error: BalanceArithmeticError) extends BlockAwaitReason

@derive(eqv, show)
case class SigningPeerBelowCollateral(peerIds: NonEmptyList[Address]) extends BlockAwaitReason

@derive(eqv, show)
case class ProposalSizeExceeded(ordinal: SnapshotOrdinal) extends BlockAwaitReason

@derive(eqv, show)
sealed trait TransactionAwaitReason

@derive(eqv, show)
case class ParentOrdinalAboveLastTxOrdinal(parentOrdinal: TransactionOrdinal, lastTxOrdinal: TransactionOrdinal)
    extends TransactionAwaitReason

@derive(eqv, show, encoder)
sealed trait TransactionRejectionReason

@derive(eqv, show)
case class ParentHashNotEqLastTxHash(parentHash: Hash, lastTxHash: Hash) extends TransactionRejectionReason

@derive(eqv, show)
case class ParentOrdinalBelowLastTxOrdinal(parentOrdinal: TransactionOrdinal, lastTxOrdinal: TransactionOrdinal)
    extends TransactionRejectionReason
