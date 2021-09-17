package org.tessellation.majority

import cats.effect.IO
import cats.syntax.all._
import higherkindness.droste.{AlgebraM, CoalgebraM, scheme}
import org.tessellation.schema.{Cell, CellError, Done, More, StackF, Ω}

case class L0MajorityCellInStream(pickMajorityInStream: PickMajorityInStream)
  extends Cell[IO, StackF, PickMajorityInStream, Either[CellError, Ω], L0MajorityFInStream](
    pickMajorityInStream,
    L0MajorityCellInStream.hylo,
    identity
  )

object L0MajorityCellInStream {

  val coalgebra: CoalgebraM[IO, StackF, L0MajorityFInStream] = CoalgebraM {
    case PickMajorityInStream(peer, peers, createdSnapshots, peerProposals) =>
      for {
        peersCache <- IO {
          peers.map(p => (p.id, p.majorityHeight)).toMap ++
            Map(peer.id -> peer.majorityHeight)
        }
        majorityStateChooser = MajorityStateChooser(peer.id)
        calculatedMajority = majorityStateChooser.chooseMajorityState(
          createdSnapshots,
          peerProposals,
          peersCache
        )
      } yield Done(Majority(calculatedMajority).asRight[CellError])

    case other =>
      IO(Done(CellError(s"Unhandled coalgebra case $other").asLeft[Ω]))
  }

  val algebra: AlgebraM[IO, StackF, Either[CellError, Ω]] = AlgebraM {
    case Done(result) => result.pure[IO]
    case More(a)      => a.pure[IO]
    case _            => IO(CellError("Unhandled algebra case!").asLeft[Ω])
  }

  val hylo: L0MajorityFInStream => IO[Either[CellError, Ω]] = scheme.hyloM(algebra, coalgebra)
}
