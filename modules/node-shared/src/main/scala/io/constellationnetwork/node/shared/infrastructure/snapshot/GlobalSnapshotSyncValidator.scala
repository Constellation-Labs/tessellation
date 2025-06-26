package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.{Validated, ValidatedNec}
import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.globalSnapshotSync.{GlobalSnapshotSync, GlobalSnapshotSyncOrdinal}
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.infrastructure.snapshot.GlobalSnapshotSyncValidator.GlobalSnapshotSyncOrError
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.signature.SignedValidator.SignedValidationError
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}

import derevo.cats.{eqv, show}
import derevo.derive
import eu.timepit.refined.auto._

trait GlobalSnapshotSyncValidator[F[_]] {
  def validate(
    globalSnapshotSync: Signed[GlobalSnapshotSync],
    metagraphId: Address,
    facilitators: Set[PeerId],
    lastGlobalSnapshotSyncs: SortedMap[PeerId, Signed[GlobalSnapshotSync]]
  )(
    implicit hasher: Hasher[F]
  ): F[GlobalSnapshotSyncOrError]
}

object GlobalSnapshotSyncValidator {

  def make[F[_]: Async: SecurityProvider](
    validator: SignedValidator[F],
    seedlist: Option[Set[SeedlistEntry]]
  ): GlobalSnapshotSyncValidator[F] =
    new GlobalSnapshotSyncValidator[F] {
      def validate(
        globalSnapshotSync: Signed[GlobalSnapshotSync],
        metagraphId: Address,
        facilitators: Set[PeerId],
        lastGlobalSnapshotSyncs: SortedMap[PeerId, Signed[GlobalSnapshotSync]]
      )(
        implicit hasher: Hasher[F]
      ): F[GlobalSnapshotSyncOrError] = {

        val seedlistPeers = seedlist.map(_.map(_.peerId))

        val peerId = globalSnapshotSync.proofs.head.id.toPeerId

        def validateSignatures(globalSnapshotSync: Signed[GlobalSnapshotSync]) = for {
          hasOnlyOneSignature <- validator.validateMaxSignatureCount(globalSnapshotSync, 1).pure[F]
          isSignedCorrectly <- validator.validateSignatures(globalSnapshotSync)
          isSignedBySeedlistPeer = validator.validateSignaturesWithSeedlist(seedlistPeers, globalSnapshotSync)
          isSignedByFacilitator = validateIfSignedByFacilitator(globalSnapshotSync, facilitators)
        } yield
          hasOnlyOneSignature
            .productR(isSignedCorrectly)
            .productR(isSignedBySeedlistPeer)
            .leftMap(_.map[GlobalSnapshotSyncValidationError](SignatureValidationError))
            .productR(isSignedByFacilitator)

        def validateIfSignedByFacilitator(globalSnapshotSync: Signed[GlobalSnapshotSync], facilitators: Set[PeerId]) = {
          val peerId = globalSnapshotSync.proofs.head.id.toPeerId
          Validated.condNec(facilitators.contains(peerId), globalSnapshotSync, NotSignedByFacilitator(peerId))
        }

        def validateChain(globalSnapshotSync: Signed[GlobalSnapshotSync]) =
          lastGlobalSnapshotSyncs.get(peerId) match {
            case Some(lastSync) if globalSnapshotSync.parentOrdinal =!= lastSync.ordinal =>
              NotANextGlobalSnapshotSync.invalidNec
            case None if globalSnapshotSync.parentOrdinal =!= GlobalSnapshotSyncOrdinal.MinValue =>
              FirstGlobalSnapshotSyncWithWrongOrdinal.invalidNec
            case Some(lastSync) if globalSnapshotSync.session < lastSync.session =>
              SessionOlder.invalidNec
            case _ =>
              globalSnapshotSync.validNec[GlobalSnapshotSyncValidationError]
          }

        validateSignatures(globalSnapshotSync)
          .productR(validateChain(globalSnapshotSync).pure[F])
      }
    }

  @derive(eqv, show)
  sealed trait GlobalSnapshotSyncValidationError
  case class SignatureValidationError(error: SignedValidationError) extends GlobalSnapshotSyncValidationError
  case class NotSignedByFacilitator(peerId: PeerId) extends GlobalSnapshotSyncValidationError
  case object NotANextGlobalSnapshotSync extends GlobalSnapshotSyncValidationError
  case object SessionOlder extends GlobalSnapshotSyncValidationError
  case object FirstGlobalSnapshotSyncWithWrongOrdinal extends GlobalSnapshotSyncValidationError

  type GlobalSnapshotSyncOrError = ValidatedNec[GlobalSnapshotSyncValidationError, Signed[GlobalSnapshotSync]]
}
