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
import org.tessellation.sdk.infrastructure.snapshot.CurrencyDataCreator.CurrencyDataCreationResult
import org.tessellation.sdk.infrastructure.snapshot.GlobalSnapshotCreator.GlobalSnapshotCreationResult

import scala.collection.immutable.SortedMap
import scala.util.control.NoStackTrace

trait GlobalSnapshotCreator[F[_]] {

  def createGlobalSnapshot(
    lastSnapshot: Signed[GlobalIncrementalSnapshot],
    lastState: GlobalSnapshotInfo,
    blocks: Set[Signed[Block]],
    stateChannelSnapshots: Set[StateChannelOutput],
    mintRewards: Boolean
  ): F[GlobalSnapshotCreationResult]

}

object GlobalSnapshotCreator {

  case class GlobalSnapshotCreationResult(
    snapshot: GlobalIncrementalSnapshot,
    state: GlobalSnapshotInfo,
    awaitingBlocks: Set[Signed[Block]],
    rejectedBlocks: Set[Signed[Block]],
    awaitingStateChannelSnapshots: Set[StateChannelOutput]
  )

  case object InvalidMerkleTree extends NoStackTrace

  def make[F[_]: Async: KryoSerializer](
    currencyDataCreator: CurrencyDataCreator[F],
    stateChannelEventsProcessor: GlobalSnapshotStateChannelEventsProcessor[F]
  ): GlobalSnapshotCreator[F] =
    new GlobalSnapshotCreator[F] {
      def createGlobalSnapshot(
        lastSnapshot: Signed[GlobalIncrementalSnapshot],
        lastState: GlobalSnapshotInfo,
        blocks: Set[Signed[Block]],
        stateChannelSnapshots: Set[StateChannelOutput],
        mintRewards: Boolean
      ): F[GlobalSnapshotCreationResult] =
        for {

          (scSnapshots, currencySnapshots, returnedStateChannelSnapshots) <- stateChannelEventsProcessor.process(
            lastState,
            stateChannelSnapshots.toList
          )
          sCSnapshotHashes <- scSnapshots.toList.traverse { case (address, nel) => nel.head.hashF.map(address -> _) }
            .map(_.toMap)
          lastStateChannelSnapshotHashes = lastState.lastStateChannelSnapshotHashes ++ sCSnapshotHashes
          lastCurrencySnapshots = lastState.lastCurrencySnapshots ++ currencySnapshots

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

          dataCreationResult <- currencyDataCreator.createCurrencyData(
            lastSnapshot.currencyData,
            lastState.currencyData,
            lastSnapshot.ordinal,
            lastSnapshot.proofs.map(_.id),
            blocks,
            mintRewards
          )

          lastSnapshotHash <- lastSnapshot.value.hashF

          info = GlobalSnapshotInfo(
            dataCreationResult.info,
            lastStateChannelSnapshotHashes,
            lastCurrencySnapshots,
            lastCurrencySnapshotProofs
          )

          stateProof <- info.stateProof(maybeMerkleTree)

          snapshot = GlobalIncrementalSnapshot(
            lastSnapshot.ordinal.next,
            lastSnapshotHash,
            dataCreationResult.data,
            scSnapshots,
            GlobalSnapshot.nextFacilitators,
            stateProof
          )

        } yield
          GlobalSnapshotCreationResult(
            snapshot,
            info,
            dataCreationResult.awaitingBlocks,
            dataCreationResult.rejectedBlocks,
            returnedStateChannelSnapshots
          )
    }

}
