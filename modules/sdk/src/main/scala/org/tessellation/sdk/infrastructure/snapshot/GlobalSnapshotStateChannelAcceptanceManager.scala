package org.tessellation.sdk.infrastructure.snapshot

import cats.Eval
import cats.data.{NonEmptyChain, NonEmptyList, NonEmptySet}
import cats.effect.kernel.Async
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.list._
import cats.syntax.option._
import cats.syntax.traverse._

import scala.collection.immutable.{SortedMap, SortedSet}

import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.address.Address
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.{GlobalSnapshotInfo, SnapshotOrdinal}
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.statechannel.{StateChannelOutput, StateChannelSnapshotBinary}
import org.tessellation.syntax.sortedCollection._

import _root_.cats.kernel.Order
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.PosLong
import io.chrisdavenport.mapref.MapRef

trait GlobalSnapshotStateChannelAcceptanceManager[F[_]] {
  def accept(ordinal: SnapshotOrdinal, lastGlobalSnapshotInfo: GlobalSnapshotInfo, events: List[StateChannelOutput]): F[
    (
      SortedMap[Address, NonEmptyList[Signed[StateChannelSnapshotBinary]]],
      Set[StateChannelOutput]
    )
  ]
}

object GlobalSnapshotStateChannelAcceptanceManager {
  def make[F[_]: Async: KryoSerializer](
    ordinalDelay: Option[PosLong],
    stateChannelAllowanceLists: Option[Map[Address, NonEmptySet[PeerId]]]
  ): F[GlobalSnapshotStateChannelAcceptanceManager[F]] =
    MapRef.ofConcurrentHashMap[F, Long, Set[(Address, Hash)]]().map { firstSeenKeysForOrdinalR =>
      new GlobalSnapshotStateChannelAcceptanceManager[F] {

        def accept(ordinal: SnapshotOrdinal, lastGlobalSnapshotInfo: GlobalSnapshotInfo, events: List[StateChannelOutput]): F[
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
                acceptForAddress(ordinal, stateChannelAllowanceLists.flatMap(_.get(address)))(
                  lastGlobalSnapshotInfo.lastStateChannelSnapshotHashes.get(address).getOrElse(Hash.empty),
                  outputs
                ).map {
                  case (accepted, returned) => (accepted.map(address -> _), returned.toSet)
                }
            }
            .flatTap(_ => firstSeenKeysForOrdinalR(ordinal.value).set(None))
            .map(_.unzip)
            .map {
              case (accepted, returned) => (accepted.map(_.toList).flatten.toMap.toSortedMap, returned.toSet.flatten)
            }

        private def acceptForAddress(
          ordinal: SnapshotOrdinal,
          allowedPeers: Option[NonEmptySet[PeerId]]
        )(lastHash: Hash, outputs: List[StateChannelOutput]) = for {
          outputsWithHashes <- outputs.traverse(stateChannelOutputWithHashes)
          possibleCandidates = onlyPossibleReferences(lastHash, outputsWithHashes)
          (toReturn, toProcess) <- allowedForProcessing(ordinal, possibleCandidates).map(_.partitionMap(identity))
          toAdd = selectStateChannels(allowedPeers)(lastHash, toProcess.flatten)
        } yield (toAdd, toReturn.flatten.map(_.output))

        private def onlyPossibleReferences(lastHashReferencs: Hash, outputs: List[StateChannelOutputWithHash]) =
          outputs
            .filter(o => (lastHashReferencs :: outputs.map(_.hash)).contains(o.output.snapshotBinary.value.lastSnapshotHash))

        private def allowedForProcessing(ordinal: SnapshotOrdinal, withHashes: List[StateChannelOutputWithHash]) =
          ordinalDelay.traverse { delay =>
            withHashes.groupBy(o => (o.output.address, o.output.snapshotBinary.lastSnapshotHash)).toList.traverse {
              case (key, outputs) =>
                for {
                  seenKeys <- firstSeenKeysForOrdinalR(ordinal.value).get.map(_.getOrElse(Set.empty))

                  modified <- firstSeenKeysForOrdinalR(ordinal.value + delay).modify { keysOpt =>
                    if (seenKeys.contains(key))
                      (keysOpt, Right(outputs))
                    else
                      (keysOpt.map(keys => keys + key).orElse(Set(key).some), Left(outputs))
                  }

                } yield modified
            }
          }.map(_.getOrElse(List(Right(withHashes))))

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

        private def stateChannelOutputWithHashes(output: StateChannelOutput) =
          output.snapshotBinary.toHashed.map(hashed => StateChannelOutputWithHash(output, hashed.hash, hashed.proofsHash))

      }
    }

  private case class StateChannelOutputWithHash(output: StateChannelOutput, hash: Hash, proofsHash: ProofsHash) {
    def proofs = output.snapshotBinary.proofs
  }

}
