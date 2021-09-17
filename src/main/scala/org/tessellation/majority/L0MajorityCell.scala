package org.tessellation.majority

import cats.data.{NonEmptyList, StateT}
import cats.effect.IO
import cats.syntax.all._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.tessellation.majority.L0MajorityCell.maxHeight
import org.tessellation.majority.MapRefUtils.MapRefOps
import org.tessellation.majority.SnapshotStorage.{MajorityHeight, SnapshotProposal}
import org.tessellation.node.{Node, Peer}
import org.tessellation.schema.{Cell, CellError, Done, More, StackF, Ω}

case class L0MajorityCell(proposal: SignedSnapshotProposal, l0MajorityContext: L0MajorityContext)
    extends Cell[IO, StackF, SignedSnapshotProposal, Either[CellError, Ω], L0MajorityF](
      proposal,
      L0MajorityCell.hylo(_).run(l0MajorityContext).map { case (_, value) => value },
      ProcessProposal
    )

object L0MajorityCell {
  type StateM[A] = StateT[IO, L0MajorityContext, A]

    val coalgebra: CoalgebraM[StateM, StackF, L0MajorityF] = CoalgebraM {
      case ProcessProposal(proposal) =>
        StateT { ctx =>
          for {
            maxLastMajorityState <- ctx.snapshotStorage.lastMajorityState.get.map(maxHeight)
            activeBetweenHeights <- ctx.snapshotStorage.activeBetweenHeights.get.map(_.getOrElse(MajorityHeight(None, None)))
            peer = Peer(ctx.node.ip, ctx.node.port, ctx.node.id, NonEmptyList.one(activeBetweenHeights))
            processProposalResponse <-
              proposal match {
                case p @ OwnProposal(sp @ SnapshotProposal(_, height, _)) =>
                  ctx.snapshotStorage.createdSnapshots.modify { current =>
                    val updated = current.getOrElse(height, sp)

                    (current + (height -> updated), Done[L0MajorityF](ProposalProcessed(p).asRight[CellError]))
                  }
                case p @ PeerProposal(id, sp @ SnapshotProposal(_, height, _)) =>
                  ctx.snapshotStorage.peerProposals(id).modify { maybeMap =>
                    val oldProposals = maybeMap.getOrElse(Map.empty)
                    val updated = oldProposals + (height -> oldProposals.getOrElse(height, sp))
                    (updated.some, Done[L0MajorityF](ProposalProcessed(p).asRight[CellError]))
                  }
              }
            createdProposals <- ctx.snapshotStorage.createdSnapshots.get
            peers <- ctx.node.getPeers
            peerProposals <- ctx.snapshotStorage.peerProposals.toMap
            peerProposalsTotal = peers.map(p => p -> peerProposals.getOrElse(p.id, Map.empty)) +
              (peer -> createdProposals)
            readyToPickMajority = peerProposalsTotal.nonEmpty && peerProposalsTotal.forall {
              case (p, proposals) =>
                proposals.exists { case (height, _) => height == maxLastMajorityState + 2 } ||
                  !MajorityHeight.isHeightBetween(maxLastMajorityState + 2)(p.majorityHeight)

            }
            result = if (readyToPickMajority)
              More[L0MajorityF](PickMajority)
            else
              processProposalResponse
          } yield (ctx, result)
        }

      case PickMajority =>
        StateT { ctx =>
          for {
            createdSnapshots <- ctx.snapshotStorage.createdSnapshots.get
            peerProposals <- ctx.snapshotStorage.peerProposals.toMap
            peers <- ctx.node.getPeers
            activeBetweenHeights <- ctx.snapshotStorage.activeBetweenHeights.get
              .map(_.getOrElse(MajorityHeight(None, None)))
            peersCache = peers.map {
              p => (p.id, p.majorityHeight)
            }.toMap ++ Map(ctx.node.id -> NonEmptyList.one(activeBetweenHeights))
            majorityStateChooser = MajorityStateChooser(ctx.node.id)
            calculatedMajority = majorityStateChooser.chooseMajorityState(
              createdSnapshots,
              peerProposals,
              peersCache
            )
            _ <- ctx.snapshotStorage.lastMajorityState.set(calculatedMajority)
          } yield (ctx, Done(Majority(calculatedMajority).asRight[CellError]))
        }

      case other =>
        StateT { ctx =>
          IO((ctx, Done(CellError(s"Unhandled coalgebra case $other").asLeft[Ω])))
        }
    }

    val algebra: AlgebraM[StateM, StackF, Either[CellError, Ω]] = AlgebraM {
      case Done(result) => StateT (ctx => (ctx, result).pure[IO])
      case More(a)      => StateT (ctx => (ctx, a).pure[IO])
      case _            => StateT (ctx => (ctx, CellError("Unhandled algebra case!").asLeft[Ω]).pure[IO])
    }

    val hylo: L0MajorityF => StateM[Either[CellError, Ω]] = scheme.hyloM(algebra, coalgebra)

  def maxHeight[V](snapshots: Map[Long, V]): Long =
    if (snapshots.isEmpty) 0
    else snapshots.keySet.max

}
