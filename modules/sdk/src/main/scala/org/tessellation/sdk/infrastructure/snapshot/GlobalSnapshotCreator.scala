package org.tessellation.sdk.infrastructure.snapshot

import cats.MonadThrow
import cats.syntax.all._
import cats.data.{NonEmptySet, ValidatedNec}
import cats.effect.Async
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.{Block, CurrencyData, CurrencyDataInfo, GlobalIncrementalSnapshot, GlobalSnapshot, GlobalSnapshotInfo}
import org.tessellation.schema.ID.Id
import org.tessellation.security.signature.Signed
import org.tessellation.statechannel.StateChannelOutput
import org.tessellation.ext.crypto._
import org.tessellation.ext.cats.syntax.next._
import org.tessellation.merkletree.Proof
import org.tessellation.merkletree.syntax._
import org.tessellation.schema.address.Address

import scala.collection.immutable.SortedMap
import scala.util.control.NoStackTrace

trait GlobalSnapshotProcessor[F[_]] {

  def createGlobalSnapshot(
    lastSnapshot: Signed[GlobalIncrementalSnapshot],
    lastInfo: GlobalSnapshotInfo,
    blocks: Set[Signed[Block]],
    mintRewards: Boolean
  ): F[(GlobalIncrementalSnapshot, GlobalSnapshotInfo, Set[Signed[Block]])]

  def validateConsecutiveSnapshot(
    lastSnapshot: Signed[GlobalIncrementalSnapshot],
    lastInfo: GlobalSnapshotInfo,
    snapshot: GlobalIncrementalSnapshot
  ): F[Either[String, (GlobalIncrementalSnapshot, GlobalSnapshotInfo, Set[Signed[Block]])]]

}

object GlobalSnapshotProcessor {

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: KryoSerializer](
    commonSnapshotCreator: CurrencyDataProcessor[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F]
  ): GlobalSnapshotProcessor[F] =
    new GlobalSnapshotProcessor[F] {
      def createGlobalSnapshot(
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        lastInfo: GlobalSnapshotInfo,
        blocks: Set[Signed[Block]],
        stateChannelSnapshots: Set[StateChannelOutput],
        mintRewards: Boolean
      ): F[(GlobalIncrementalSnapshot, GlobalSnapshotInfo, Set[Signed[Block]])] =
        for {

          (scSnapshots, currencySnapshots, returnedSCSnapshots) <- stateChannelEventsProcessor.process(
            lastInfo,
            stateChannelSnapshots.toList
          )
          sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
            .map(_.toMap)
          lastStateChannelSnapshotHashes = lastInfo.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
          lastCurrencySnapshots = lastInfo.lastCurrencySnapshots ++ currencySnapshots

          maybeMerkleTree <- lastCurrencySnapshots.merkleTree[F]
          lastCurrencySnapshotProofs <- maybeMerkleTree.traverse { merkleTree =>
            lastCurrencySnapshots.toList.traverse {
              case (address, state) =>
                (address, state).hashF
                  .map(merkleTree.findPath(_))
                  .flatMap(MonadThrow[F].fromOption(_, InvalidMerkleTree))
                  .map((address, _))
            }
          }.map(_.map(SortedMap.from(_)).getOrElse(SortedMap.empty[Address, Proof]))

          (data, dataInfo, returnedBlocks) <- commonSnapshotCreator.createCurrencyData(
            lastSnapshot.data,
            lastInfo.data,
            lastSnapshot.ordinal,
            lastSnapshot.proofs.map(_.id),
            blocks,
            mintRewards
          )

          lastSnapshotHash <- lastSnapshot.value.hashF

          info = GlobalSnapshotInfo(
            dataInfo,
            lastStateChannelSnapshotHashes,
            lastCurrencySnapshots,
            lastCurrencySnapshotProofs
          )

          stateProof <- info.stateProof(maybeMerkleTree)

          snapshot = GlobalIncrementalSnapshot(
            lastSnapshot.ordinal.next,
            lastSnapshotHash,
            data,
            scSnapshots,
            GlobalSnapshot.nextFacilitators,
            stateProof,
          )



        } yield ()

      def validateConsecutiveSnapshot(
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        lastInfo: GlobalSnapshotInfo,
        snapshot: GlobalIncrementalSnapshot
      ): F[Either[String, (GlobalIncrementalSnapshot, GlobalSnapshotInfo, Set[Signed[Block]])]] = ???
    }

}
