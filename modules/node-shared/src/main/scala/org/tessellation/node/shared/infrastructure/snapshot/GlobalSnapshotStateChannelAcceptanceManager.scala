package org.tessellation.node.shared.infrastructure.snapshot

import cats.Eval
import cats.data.{NonEmptyChain, NonEmptyList, NonEmptySet}
import cats.effect.kernel.{Async, Ref}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.security.Hasher
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.tessellation.syntax.sortedCollection._

import _root_.cats.kernel.Order
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong

trait GlobalSnapshotStateChannelAcceptanceManager[F[_]] {
  def accept(ordinal: SnapshotOrdinal, lastGlobalSnapshotInfo: GlobalSnapshotInfo, events: List[StateChannelOutput])(
    implicit hasher: Hasher[F]
  ): F[
    (
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput]
    )
  ]
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
          toAdd = selectStateChannels(allowedPeers)(lastHash, possibleCandidates)
        } yield (toAdd, toReturn)

        private def allowedForProcessing(ordinal: SnapshotOrdinal, withHashes: List[StateChannelOutputWithHash]) =
          withHashes
            .foldLeft(Map.empty[(Address, Hash), List[StateChannelOutputWithHash]]) {
              case (acc, o) =>
                val key = (o.output.address, o.output.snapshotBinary.lastSnapshotHash)
                acc.updatedWith(key)(_.map(_ :+ o).orElse(List(o).some))
            }
            .toList
            .traverse {
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

        private def selectStateChannels(
          allowedPeers: Option[NonEmptySet[PeerId]]
        )(lastHash: Hash, stateChannels: List[StateChannelOutputWithHash]) = {
          val lastHashForStateChannel = stateChannels.groupByNec(_.output.snapshotBinary.lastSnapshotHash)

          def unfold(lastHash: Hash): Eval[List[StateChannelOutputWithHash]] =
            lastHashForStateChannel
              .get(lastHash)
              .map(pickMajority(allowedPeers))
              .map { go =>
                for {
                  head <- Eval.now(go)
                  tail <- unfold(go.hash)
                } yield head :: tail
              }
              .getOrElse(Eval.now(List.empty))

          unfold(lastHash).value.toNel.map(_.map(_.output.snapshotBinary).reverse)
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
