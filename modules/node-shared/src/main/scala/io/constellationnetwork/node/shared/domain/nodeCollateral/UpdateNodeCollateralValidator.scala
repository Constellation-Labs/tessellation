package io.constellationnetwork.node.shared.domain.nodeCollateral

import cats.data.{NonEmptySet, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.syntax.validated._
import io.constellationnetwork.node.shared.domain.nodeCollateral.UpdateNodeCollateralValidator.UpdateNodeCollateralValidationErrorOr
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.delegatedStake.{DelegatedStakeRecord, UpdateDelegatedStake}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.nodeCollateral._
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

trait UpdateNodeCollateralValidator[F[_]] {
  def validateCreateNodeCollateral(
    signed: Signed[UpdateNodeCollateral.Create],
    lastContext: GlobalSnapshotInfo
  ): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Create]]]

  def validateWithdrawNodeCollateral(
    signed: Signed[UpdateNodeCollateral.Withdraw],
    lastContext: GlobalSnapshotInfo
  ): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Withdraw]]]
}

object UpdateNodeCollateralValidator {

  def make[F[_]: Async: SecurityProvider](
    signedValidator: SignedValidator[F],
    seedlist: Option[Set[SeedlistEntry]]
  )(
    implicit hasher: Hasher[F]
  ): UpdateNodeCollateralValidator[F] =
    new UpdateNodeCollateralValidator[F] {

      def validateCreateNodeCollateral(
        signed: Signed[UpdateNodeCollateral.Create],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Create]]] =
        for {
          numberOfSignaturesV <- validateNumberOfSignatures(signed)
          signaturesV <- signedValidator
            .validateSignatures(signed)
            .map(_.errorMap[UpdateNodeCollateralValidationError](InvalidSigned))
          isSignedExclusivelyBySource <- signedValidator
            .isSignedExclusivelyBy(signed, signed.source)
            .map(_.errorMap[UpdateNodeCollateralValidationError](InvalidSigned))
          authorizedNodeIdV = validateAuthorizedNodeId(signed)
          nodeIdV = validateNodeId(signed, lastContext)
          parentV <- validateParent(signed, lastContext)
          tokenLockV <- validateTokenLock(signed, lastContext)
          pendingWithdrawalV <- validatePendingWithdrawal(signed, lastContext)
        } yield
          numberOfSignaturesV
            .productR(signaturesV)
            .productR(authorizedNodeIdV)
            .productR(isSignedExclusivelyBySource)
            .productR(nodeIdV)
            .productR(parentV)
            .productR(tokenLockV)
            .productR(pendingWithdrawalV)

      def validateWithdrawNodeCollateral(
        signed: Signed[UpdateNodeCollateral.Withdraw],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Withdraw]]] =
        for {
          numberOfSignaturesV <- validateNumberOfSignatures(signed)
          signaturesV <- signedValidator
            .validateSignatures(signed)
            .map(_.errorMap[UpdateNodeCollateralValidationError](InvalidSigned))
          isSignedExclusivelyBySource <- signedValidator
            .isSignedExclusivelyBy(signed, signed.source)
            .map(_.errorMap[UpdateNodeCollateralValidationError](InvalidSigned))
          withdrawV <- validateWithdrawal(signed, lastContext)
        } yield
          numberOfSignaturesV
            .productR(signaturesV)
            .productR(isSignedExclusivelyBySource)
            .productR(withdrawV)

      private def validateNumberOfSignatures[A <: UpdateNodeCollateral](
        signed: Signed[A]
      ): F[UpdateNodeCollateralValidationErrorOr[Signed[A]]] = {
        val result = if (signed.proofs.size == 1) {
          signed.validNec[UpdateNodeCollateralValidationError]
        } else {
          TooManySignatures(signed.proofs).invalidNec
        }
        result.pure[F]
      }

      private def validateNodeId(
        signed: Signed[UpdateNodeCollateral.Create],
        lastContext: GlobalSnapshotInfo
      ): UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Create]] = {
        val activeNodeCollaterals = lastContext.activeNodeCollaterals
          .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]])
          .getOrElse(signed.source, List.empty[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)])
        if (activeNodeCollaterals.exists(s => s._1.nodeId == signed.nodeId)) {
          StakeExistsForNode(signed.nodeId).invalidNec
        } else {
          signed.validNec
        }
      }

      private def validateParent(
        signed: Signed[UpdateNodeCollateral.Create],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Create]]] =
        for {
          lastRef <- lastContext.activeNodeCollaterals
            .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]])
            .get(signed.source)
            .flatMap(collaterals => Option.when(collaterals.nonEmpty)(collaterals.maxBy(_._1.ordinal)))
            .traverse(collateral => NodeCollateralReference.of(collateral._1))
            .map(_.getOrElse(NodeCollateralReference.empty))

        } yield
          if (lastRef === signed.parent) {
            signed.validNec
          } else {
            InvalidParent(signed.parent).invalidNec
          }

      private def validateAuthorizedNodeId(
        signed: Signed[UpdateNodeCollateral.Create]
      ): UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Create]] =
        if (seedlist.forall(_.exists(_.peerId === signed.nodeId))) {
          signed.validNec
        } else {
          UnauthorizedNode(signed.nodeId).invalidNec
        }

      private def validatePendingWithdrawal(
        signed: Signed[UpdateNodeCollateral.Create],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Create]]] =
        for {
          stakeRef <- lastContext.activeNodeCollaterals
            .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]])
            .getOrElse(signed.source, List.empty)
            .traverse { case (stake, _) => NodeCollateralReference.of(stake) }
            .map(_.find(_ === signed.parent))
          withdrawalRef = lastContext.nodeCollateralWithdrawals
            .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Withdraw], EpochProgress)]])
            .getOrElse(signed.source, List.empty)
            .find { case (w, _) => stakeRef.map(_.hash).contains(w.collateralRef) }
        } yield
          if (withdrawalRef.isEmpty) {
            signed.validNec
          } else {
            AlreadyWithdrawn(signed.parent.hash).invalidNec
          }

      private def validateWithdrawal(
        signed: Signed[UpdateNodeCollateral.Withdraw],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Withdraw]]] = {

        def validateUniqueness(address: Address): UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Withdraw]] = {
          val withdrawals = lastContext.nodeCollateralWithdrawals
            .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Withdraw], SnapshotOrdinal)]])
            .getOrElse(address, List.empty)
          if (withdrawals.exists(_._1.collateralRef === signed.collateralRef)) {
            AlreadyWithdrawn(signed.collateralRef).invalidNec
          } else {
            signed.validNec
          }
        }

        def validateCreate(address: Address): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Withdraw]]] =
          getParent(address, lastContext.activeNodeCollaterals.getOrElse(SortedMap.empty), signed).map {
            case Some(nodeCollateral) =>
              if (nodeCollateral.source =!= signed.source)
                InvalidSourceAddress(signed.collateralRef).invalidNec
              else
                signed.validNec
            case _ =>
              InvalidCollateral(signed.collateralRef).invalidNec
          }

        for {
          parentV <- validateCreate(signed.source)
          uniqueV = validateUniqueness(signed.source)
        } yield uniqueV.productR(parentV)
      }

      private def validateTokenLock(
        signed: Signed[UpdateNodeCollateral.Create],
        lastContext: GlobalSnapshotInfo
      ): F[UpdateNodeCollateralValidationErrorOr[Signed[UpdateNodeCollateral.Create]]] = {

        def tokenLockAvailable(address: Address): Boolean = {
          val maybeExistingStake = lastContext.activeDelegatedStakes
            .getOrElse(SortedMap.empty[Address, List[DelegatedStakeRecord]])
            .getOrElse(signed.source, List.empty)
            .find(_.event.tokenLockRef === signed.tokenLockRef)
            .map(_.event)

          val maybeExistingCollateral = lastContext.activeNodeCollaterals
            .getOrElse(SortedMap.empty[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]])
            .getOrElse(address, List.empty[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)])
            .find(_._1.tokenLockRef === signed.tokenLockRef)
            .map(_._1)
          maybeExistingStake.isEmpty && maybeExistingCollateral.forall(_.nodeId != signed.nodeId)
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
        nodeCollaterals: SortedMap[Address, List[(Signed[UpdateNodeCollateral.Create], SnapshotOrdinal)]],
        signed: Signed[UpdateNodeCollateral.Withdraw]
      ): F[Option[Signed[UpdateNodeCollateral.Create]]] =
        for {
          maybeParent <- nodeCollaterals.getOrElse(address, List.empty).findM { s =>
            NodeCollateralReference.of(s._1).map(_.hash === signed.collateralRef)
          }
        } yield maybeParent.map(_._1)

    }

  @derive(eqv, show)
  sealed trait UpdateNodeCollateralValidationError

  case class InvalidSigned(error: SignedValidationError) extends UpdateNodeCollateralValidationError

  case class TooManySignatures(proofs: NonEmptySet[SignatureProof]) extends UpdateNodeCollateralValidationError

  case class StakeExistsForNode(peerId: PeerId) extends UpdateNodeCollateralValidationError

  case class UnauthorizedNode(peerId: PeerId) extends UpdateNodeCollateralValidationError

  case class InvalidCollateral(collateralRef: Hash) extends UpdateNodeCollateralValidationError

  case class InvalidSourceAddress(collateralRef: Hash) extends UpdateNodeCollateralValidationError

  case class InvalidTokenLock(tokenLockReference: Hash) extends UpdateNodeCollateralValidationError

  case class AlreadyWithdrawn(collateralRef: Hash) extends UpdateNodeCollateralValidationError

  case class InvalidParent(parent: NodeCollateralReference) extends UpdateNodeCollateralValidationError

  type UpdateNodeCollateralValidationErrorOr[A] = ValidatedNec[UpdateNodeCollateralValidationError, A]
}
