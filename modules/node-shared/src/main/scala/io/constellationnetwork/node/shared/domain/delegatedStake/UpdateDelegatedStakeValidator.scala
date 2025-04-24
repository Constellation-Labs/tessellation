package io.constellationnetwork.node.shared.domain.delegatedStake

import cats.data.{NonEmptySet, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.domain.delegatedStake.UpdateDelegatedStakeValidator.UpdateDelegatedStakeValidationErrorOr
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake._
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.nodeCollateral.{NodeCollateralRecord, UpdateNodeCollateral}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.tokenLock.{TokenLock, TokenLockReference}
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.derive

trait UpdateDelegatedStakeValidator[F[_]] {
  def validateCreateDelegatedStake(
    signed: Signed[UpdateDelegatedStake.Create],
    lastContext: GlobalSnapshotInfo
  ): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]]]

  def validateWithdrawDelegatedStake(
    signed: Signed[UpdateDelegatedStake.Withdraw],
    lastContext: GlobalSnapshotInfo
  ): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]]]
}

object UpdateDelegatedStakeValidator {
  def make[F[_]: Async: SecurityProvider](
    signedValidator: SignedValidator[F],
    seedlist: Option[Set[SeedlistEntry]]
  )(implicit hasher: Hasher[F]): UpdateDelegatedStakeValidator[F] =
    new UpdateDelegatedStakeValidator[F] {

      def validateCreateDelegatedStake(
        signed: Signed[UpdateDelegatedStake.Create],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]]] =
        for {
          numberOfSignaturesV <- validateNumberOfSignatures(signed)
          signaturesV <- signedValidator
            .validateSignatures(signed)
            .map(_.errorMap[UpdateDelegatedStakeValidationError](InvalidSigned))
          isSignedExclusivelyBySource <- signedValidator
            .isSignedExclusivelyBy(signed, signed.source)
            .map(_.errorMap[UpdateDelegatedStakeValidationError](InvalidSigned))
          authorizedNodeIdV = validateAuthorizedNodeId(signed)
          nodeIdV = validateNodeId(signed, lastContext)
          parentV <- validateParent(signed, lastContext)
          tokenLockV <- validateTokenLock(signed, lastContext)
          pendingWithdrawalV = validatePendingWithdrawal(signed, lastContext)
        } yield
          numberOfSignaturesV
            .productR(signaturesV)
            .productR(isSignedExclusivelyBySource)
            .productR(authorizedNodeIdV)
            .productR(nodeIdV)
            .productR(parentV)
            .productR(tokenLockV)
            .productR(pendingWithdrawalV)

      def validateWithdrawDelegatedStake(
        signed: Signed[UpdateDelegatedStake.Withdraw],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]]] =
        for {
          numberOfSignaturesV <- validateNumberOfSignatures(signed)
          signaturesV <- signedValidator
            .validateSignatures(signed)
            .map(_.errorMap[UpdateDelegatedStakeValidationError](InvalidSigned))
          isSignedExclusivelyBySource <- signedValidator
            .isSignedExclusivelyBy(signed, signed.source)
            .map(_.errorMap[UpdateDelegatedStakeValidationError](InvalidSigned))
          withdrawV <- validateWithdrawal(signed, lastContext)
        } yield
          numberOfSignaturesV
            .productR(signaturesV)
            .productR(isSignedExclusivelyBySource)
            .productR(withdrawV)

      private def validateNumberOfSignatures[A <: UpdateDelegatedStake](
        signed: Signed[A]
      ): F[UpdateDelegatedStakeValidationErrorOr[Signed[A]]] = {
        val result = if (signed.proofs.size == 1) {
          signed.validNec[UpdateDelegatedStakeValidationError]
        } else {
          TooManySignatures(signed.proofs).invalidNec
        }
        result.pure[F]
      }

      private def validateParent(
        signed: Signed[UpdateDelegatedStake.Create],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]]] =
        for {
          lastRef <- lastContext.activeDelegatedStakes
            .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
            .get(signed.source)
            .flatMap(stakes => Option.when(stakes.nonEmpty)(stakes.maxBy(_.createdAt)))
            .traverse(stake => DelegatedStakeReference.of(stake.event))
            .map(_.getOrElse(DelegatedStakeReference.empty))
        } yield
          if (lastRef == signed.parent) {
            signed.validNec
          } else {
            InvalidParent(signed.parent).invalidNec
          }

      private def validateAuthorizedNodeId(
        signed: Signed[UpdateDelegatedStake.Create]
      ): UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]] =
        if (seedlist.forall(_.exists(_.peerId === signed.nodeId))) {
          signed.validNec
        } else {
          UnauthorizedNode(signed.nodeId).invalidNec
        }

      private def validateNodeId(
        signed: Signed[UpdateDelegatedStake.Create],
        lastContext: GlobalSnapshotInfo
      ): UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]] = {
        val activeDelegatedStakes = lastContext.activeDelegatedStakes
          .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
          .getOrElse(signed.source, List.empty)
        if (activeDelegatedStakes.exists(s => s.event.nodeId == signed.nodeId)) {
          StakeExistsForNode(signed.nodeId).invalidNec
        } else {
          signed.validNec
        }
      }

      private def validatePendingWithdrawal(
        signed: Signed[UpdateDelegatedStake.Create],
        lastContext: GlobalSnapshotInfo
      ): UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]] = {
        val withdrawalRef = lastContext.delegatedStakesWithdrawals
          .getOrElse(SortedMap.empty[Address, List[PendingDelegatedStakeWithdrawal]])
          .getOrElse(signed.source, List.empty)
          .find { case PendingDelegatedStakeWithdrawal(w, _, _, _) => signed.tokenLockRef == w.tokenLockRef }
        if (withdrawalRef.isEmpty) {
          signed.validNec
        } else {
          AlreadyWithdrawn(signed.parent.hash).invalidNec
        }
      }

      private def validateWithdrawal(
        signed: Signed[UpdateDelegatedStake.Withdraw],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]]] = {

        def validateUniqueness(address: Address): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]]] = {
          val withdrawals = lastContext.delegatedStakesWithdrawals
            .getOrElse(SortedMap.empty[Address, List[PendingDelegatedStakeWithdrawal]])
            .getOrElse(address, List.empty)
          for {
            stakeRefs <- withdrawals.traverse(w => DelegatedStakeReference.of(w.event))
          } yield
            if (stakeRefs.exists(ref => ref.hash === signed.stakeRef)) {
              AlreadyWithdrawn(signed.stakeRef).invalidNec
            } else {
              signed.validNec
            }
        }

        def validateCreate(address: Address): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Withdraw]]] =
          getParent(
            address,
            lastContext.activeDelegatedStakes.getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]]),
            signed
          ).map {
            case Some(delegatedStaking) =>
              if (delegatedStaking.source =!= signed.source)
                InvalidSourceAddress(signed.stakeRef).invalidNec
              else
                signed.validNec
            case _ =>
              InvalidStake(signed.stakeRef).invalidNec
          }

        for {
          parentV <- validateCreate(signed.source)
          uniqueV <- validateUniqueness(signed.source)
        } yield uniqueV.productR(parentV)
      }

      private def validateTokenLock(
        signed: Signed[UpdateDelegatedStake.Create],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateDelegatedStakeValidationErrorOr[Signed[UpdateDelegatedStake.Create]]] = {

        def tokenLockAvailable(address: Address): Boolean = {
          val maybeExistingStake = lastContext.activeDelegatedStakes
            .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
            .getOrElse(address, List.empty)
            .find(_.event.tokenLockRef === signed.tokenLockRef)
            .map(_.event)

          val maybeExistingCollateral = lastContext.activeNodeCollaterals
            .getOrElse(SortedMap.empty[Address, List[NodeCollateralRecord]])
            .getOrElse(address, List.empty[NodeCollateralRecord])
            .find(_.event.tokenLockRef === signed.tokenLockRef)
          maybeExistingCollateral.isEmpty && maybeExistingStake.forall(_.nodeId != signed.nodeId)
        }

        def tokenLockValid(address: Address): F[Boolean] = {
          val tokenLocks = lastContext.activeTokenLocks
            .getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
            .getOrElse(address, SortedSet.empty[Signed[TokenLock]])
          for {
            tokenLocksWithReferences <- tokenLocks.toList.traverse(t => TokenLockReference.of(t).map(r => (t, r)))
          } yield
            tokenLocksWithReferences.find { case (_, r) => r.hash === signed.tokenLockRef } match {
              case Some((tokenLock, _)) =>
                signed.amount.value.value === tokenLock.amount.value.value && tokenLock.unlockEpoch.isEmpty
              case None => false
            }
        }

        val available = tokenLockAvailable(signed.source)

        for {
          valid <- if (available) tokenLockValid(signed.source) else available.pure[F]
        } yield if (valid) signed.validNec else InvalidTokenLock(signed.tokenLockRef).invalidNec
      }

      private def getParent(
        address: Address,
        delegatedStakes: SortedMap[Address, List[DelegatedStakeRecord]],
        signed: Signed[UpdateDelegatedStake.Withdraw]
      ): F[Option[Signed[UpdateDelegatedStake.Create]]] =
        for {
          maybeParent <- delegatedStakes.getOrElse(address, List.empty).findM { s =>
            DelegatedStakeReference.of(s.event).map(_.hash === signed.stakeRef)
          }
        } yield maybeParent.map(_.event)
    }

  @derive(eqv, show)
  sealed trait UpdateDelegatedStakeValidationError

  case class InvalidSigned(error: SignedValidationError) extends UpdateDelegatedStakeValidationError
  case class TooManySignatures(proofs: NonEmptySet[SignatureProof]) extends UpdateDelegatedStakeValidationError

  case class StakeExistsForNode(peerId: PeerId) extends UpdateDelegatedStakeValidationError

  case class UnauthorizedNode(peerId: PeerId) extends UpdateDelegatedStakeValidationError

  case class InvalidStake(parent: Hash) extends UpdateDelegatedStakeValidationError

  case class InvalidTokenLock(tokenLockRef: Hash) extends UpdateDelegatedStakeValidationError

  case class InvalidSourceAddress(tokenLockRef: Hash) extends UpdateDelegatedStakeValidationError

  case class AlreadyWithdrawn(parent: Hash) extends UpdateDelegatedStakeValidationError

  case class InvalidParent(parent: DelegatedStakeReference) extends UpdateDelegatedStakeValidationError

  type UpdateDelegatedStakeValidationErrorOr[A] = ValidatedNec[UpdateDelegatedStakeValidationError, A]
}
