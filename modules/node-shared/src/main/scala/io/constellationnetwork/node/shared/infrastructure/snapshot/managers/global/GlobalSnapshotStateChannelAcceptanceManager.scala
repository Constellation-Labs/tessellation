package io.constellationnetwork.node.shared.infrastructure.snapshot.managers.global

import cats.data.{NonEmptyChain, NonEmptyList, NonEmptySet}
import cats.effect.kernel.{Async, Ref}
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.traverse._
import cats.{Eval, Functor}

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.SignatureProof
import io.constellationnetwork.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import io.constellationnetwork.syntax.sortedCollection._

import _root_.cats.kernel.Order
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait GlobalSnapshotStateChannelAcceptanceManager[F[_]] {
  type Branches = NonEmptyList[NonEmptyList[Signed[StateChannelSnapshotBinary]]]

  def accept(ordinal: SnapshotOrdinal, lastGlobalSnapshotInfo: GlobalSnapshotInfo, events: List[StateChannelOutput])(
    implicit hasher: Hasher[F]
  ): F[
    (
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput]
    )
  ]

  /** Returns deterministic sibling root alternatives. The first branch is byte-for-byte the legacy selection; later branches are tried only
    * when Currency validation deterministically rejects an earlier root for an unsupported historical dependency.
    */
  def acceptBranches(ordinal: SnapshotOrdinal, lastGlobalSnapshotInfo: GlobalSnapshotInfo, events: List[StateChannelOutput])(
    implicit hasher: Hasher[F],
    functor: Functor[F]
  ): F[(SortedMap[Address, Branches], Set[StateChannelOutput])] =
    accept(ordinal, lastGlobalSnapshotInfo, events).map {
      case (selected, returned) =>
        (selected.map { case (address, branch) => address -> NonEmptyList.one(branch) }, returned)
    }
}

object GlobalSnapshotStateChannelAcceptanceManager {
  def make[F[_]: Async](
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]],
    pullDelay: NonNegLong = NonNegLong.MinValue,
    purgeDelay: NonNegLong = NonNegLong.MinValue
  ): F[GlobalSnapshotStateChannelAcceptanceManager[F]] =
    Ref.of[F, Map[(Address, Hash), Long]](Map.empty).map { firstSeenKeysForOrdinalR =>
      new GlobalSnapshotStateChannelAcceptanceManager[F] {

        def accept(ordinal: SnapshotOrdinal, lastGlobalSnapshotInfo: GlobalSnapshotInfo, events: List[StateChannelOutput])(
          implicit hasher: Hasher[F]
        ): F[
          (
            SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
            Set[StateChannelOutput]
          )
        ] =
          acceptBranches(ordinal, lastGlobalSnapshotInfo, events).map {
            case (branches, returned) =>
              (branches.map { case (address, alternatives) => address -> alternatives.head }, returned)
          }

        override def acceptBranches(ordinal: SnapshotOrdinal, lastGlobalSnapshotInfo: GlobalSnapshotInfo, events: List[StateChannelOutput])(
          implicit hasher: Hasher[F],
          functor: Functor[F]
        ): F[(SortedMap[Address, Branches], Set[StateChannelOutput])] =
          events
            .groupBy(_.address)
            .toList
            .traverse {
              case (address, outputs) =>
                acceptForAddress(
                  ordinal,
                  stateChannelAllowanceLists.flatMap(_.get(address))
                )(
                  lastGlobalSnapshotInfo.lastStateChannelSnapshotHashes.getOrElse(address, Hash.empty),
                  outputs
                ).map {
                  case (accepted, returned) => (accepted.map(address -> _), returned.toSet)
                }
            }
            .flatTap { _ =>
              firstSeenKeysForOrdinalR.update(_.filterNot { case (_, seenAt) => shouldPurge(seenAt, ordinal) })
            }
            .map(_.unzip)
            .map {
              case (accepted, returned) => (accepted.flatMap(_.toList).toMap.toSortedMap, returned.toSet.flatten)
            }

        private def acceptForAddress(
          ordinal: SnapshotOrdinal,
          allowedPeers: Option[NonEmptySet[PeerId]]
        )(lastHash: Hash, outputs: List[StateChannelOutput])(implicit hasher: Hasher[F]) = for {
          outputsWithHashes <- outputs.traverse(stateChannelOutputWithHashes)
          (notAllowed, allowed) <- allowedForProcessing(ordinal, outputsWithHashes).map(_.partitionMap(identity))
          (impossibleCandidates, possibleCandidates) = onlyPossibleReferences(lastHash, allowed.flatten).partitionMap(identity)
          toReturn = notAllowed.flatten.map(_.output) ++ impossibleCandidates.map(_.output)
          toAdd = selectStateChannelBranches(allowedPeers)(lastHash, possibleCandidates)
        } yield (toAdd, toReturn)

        private def allowedForProcessing(ordinal: SnapshotOrdinal, withHashes: List[StateChannelOutputWithHash]) =
          withHashes.groupBy(o => (o.output.address, o.output.snapshotBinary.lastSnapshotHash)).toList.traverse {
            case (key, outputs) =>
              firstSeenKeysForOrdinalR.modify { current =>
                current.get(key) match {
                  case Some(seenAt) if shouldPurge(seenAt, ordinal) =>
                    (current, Left(List.empty))
                  case Some(seenAt) if shouldPull(seenAt, ordinal) =>
                    (current, Right(outputs))
                  case Some(_) =>
                    (current, Left(outputs))
                  case None if shouldPull(ordinal.value, ordinal) =>
                    (current + (key -> ordinal.value.value), Right(outputs))
                  case None =>
                    (current + (key -> ordinal.value.value), Left(outputs))
                }
              }
          }

        private def onlyPossibleReferences(
          lastHashReference: Hash,
          outputs: List[StateChannelOutputWithHash]
        ): List[Either[StateChannelOutputWithHash, StateChannelOutputWithHash]] = {
          val references = lastHashReference :: outputs.map(_.hash)

          outputs.map { o =>
            val hasReference = references.contains(o.output.snapshotBinary.value.lastSnapshotHash)

            Either.cond(hasReference, o, o)
          }
        }

        private def selectStateChannelBranches(
          allowedPeers: Option[NonEmptySet[PeerId]]
        )(lastHash: Hash, stateChannels: List[StateChannelOutputWithHash]): Option[Branches] = {
          val byParent = stateChannels.groupByNec(_.output.snapshotBinary.lastSnapshotHash)

          // Preserve the rc.12 primary selector literally. In particular, its two successive
          // signature-count filters operate on individual signed envelopes before equal unsigned
          // contents are counted. Collapsing envelopes by unsigned hash first is not equivalent:
          // the maxima can belong to different re-signings of the same content. Alternatives are
          // obtained only after removing every envelope for the already-selected unsigned hash.
          def rankedByLegacySelection(outputs: NonEmptyChain[StateChannelOutputWithHash]): List[StateChannelOutputWithHash] = {
            val selected = pickMajority(allowedPeers)(outputs)
            val alternatives = outputs.toNonEmptyList.toList
              .filterNot(_.hash === selected.hash)
              .groupBy(_.hash)
              .toList
              .map { case (hash, sameContent) => hash -> sameContent.minBy(_.proofsHash) }
              .sortBy(_._1)
              .map(_._2)

            selected :: alternatives
          }

          def primaryDescendants(parent: Hash): Eval[List[StateChannelOutputWithHash]] =
            byParent
              .get(parent)
              .map(pickMajority(allowedPeers))
              .map { selected =>
                for {
                  tail <- primaryDescendants(selected.hash)
                } yield selected :: tail
              }
              .getOrElse(Eval.now(List.empty))

          byParent.get(lastHash).flatMap { roots =>
            rankedByLegacySelection(roots)
              .traverse(root => (root :: primaryDescendants(root.hash).value).map(_.output.snapshotBinary).reverse.toNel)
              .flatMap(_.toNel)
          }
        }

        private def pickMajority(allowedPeers: Option[NonEmptySet[PeerId]])(outputs: NonEmptyChain[StateChannelOutputWithHash]) =
          (pickMajorityByNumberOfSignatures(filterWithAllowedPeers(allowedPeers)) _)
            .andThen(pickMajorityByNumberOfSignatures(_.toSortedSet))(outputs)
            .groupBy(_.hash)
            .mapBoth((hash, o) => ((o.length, hash), o.sortBy(_.proofsHash).head))(Order.reverse(implicitly[Order[(Long, Hash)]]))
            .head
            ._2

        private def pickMajorityByNumberOfSignatures(
          filterSignatures: NonEmptySet[SignatureProof] => SortedSet[SignatureProof]
        )(outputs: NonEmptyChain[StateChannelOutputWithHash]) =
          outputs.tail
            .foldLeft(NonEmptyChain(outputs.head)) {
              case (acc, o) if filterSignatures(acc.head.proofs).size < filterSignatures(o.proofs).size  => NonEmptyChain(o)
              case (acc, o) if filterSignatures(acc.head.proofs).size == filterSignatures(o.proofs).size => acc.append(o)
              case (acc, _)                                                                              => acc
            }

        private def filterWithAllowedPeers(
          allowedPeers: Option[NonEmptySet[PeerId]]
        )(signatures: NonEmptySet[SignatureProof]): SortedSet[SignatureProof] =
          signatures.filter(signature => allowedPeers.map(allowed => allowed.contains(signature.id.toPeerId)).getOrElse(true))

        private def stateChannelOutputWithHashes(output: StateChannelOutput)(implicit hasher: Hasher[F]) =
          output.snapshotBinary.toHashed.map(hashed => StateChannelOutputWithHash(output, hashed.hash, hashed.proofsHash))

        private def shouldPurge(seenAt: Long, ordinal: SnapshotOrdinal): Boolean =
          seenAt <= ordinal.value - pullDelay - purgeDelay

        private def shouldPull(seenAt: Long, ordinal: SnapshotOrdinal): Boolean =
          seenAt <= ordinal.value - pullDelay

      }
    }

  private case class StateChannelOutputWithHash(output: StateChannelOutput, hash: Hash, proofsHash: ProofsHash) {
    def proofs = output.snapshotBinary.proofs
  }

}
