package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.Show
import cats.syntax.show._

import scala.concurrent.duration.FiniteDuration

import io.constellationnetwork.node.shared.infrastructure.consensus.declaration.PeerDeclaration
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash

import derevo.cats.{eqv, show}
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import monocle.Lens
import monocle.macros.GenLens

@derive(eqv, encoder, decoder)
case class Facilitators(value: List[PeerId])

@derive(eqv, encoder, decoder)
case class RemovedFacilitators(value: Set[PeerId])
object RemovedFacilitators {
  def empty: RemovedFacilitators = RemovedFacilitators(Set.empty)
}

@derive(eqv, encoder, decoder)
case class WithdrawnFacilitators(value: Set[PeerId])
object WithdrawnFacilitators {
  def empty: WithdrawnFacilitators = WithdrawnFacilitators(Set.empty)
}

@derive(eqv, encoder, decoder, show)
case class Candidates(value: Set[PeerId])
object Candidates {
  def empty: Candidates = Candidates(Set.empty)
}

@derive(eqv)
case class ConsensusState[Key, Status, Outcome, Kind](
  key: Key,
  lastOutcome: Outcome,
  facilitators: Facilitators,
  status: Status,
  createdAt: FiniteDuration,
  removedFacilitators: RemovedFacilitators = RemovedFacilitators.empty,
  withdrawnFacilitators: WithdrawnFacilitators = WithdrawnFacilitators.empty,
  lockStatus: LockStatus = LockStatus.Open,
  spreadAckKinds: Set[Kind]
)

object ConsensusState {
  implicit def showInstance[K: Show, S: Show, O, Kind: Show]: Show[ConsensusState[K, S, O, Kind]] = { cs =>
    s"""ConsensusState{
       |key=${cs.key.show},
       |lockStatus=${cs.lockStatus.show},
       |facilitatorCount=${cs.facilitators.value.size.show},
       |removedFacilitators=${cs.removedFacilitators.value.show},
       |withdrawnFacilitators=${cs.withdrawnFacilitators.value.show},
       |spreadAckKinds=${cs.spreadAckKinds.show},
       |status=${cs.status.show}
       |}""".stripMargin.replace(",\n", ", ")
  }

  implicit def _lockStatus[K, S, O, Kind]: Lens[ConsensusState[K, S, O, Kind], LockStatus] =
    GenLens[ConsensusState[K, S, O, Kind]](_.lockStatus)

  implicit def _facilitators[K, S, O, Kind]: Lens[ConsensusState[K, S, O, Kind], Facilitators] =
    GenLens[ConsensusState[K, S, O, Kind]](_.facilitators)

  implicit def _removedFacilitators[K, S, O, Kind]: Lens[ConsensusState[K, S, O, Kind], RemovedFacilitators] =
    GenLens[ConsensusState[K, S, O, Kind]](_.removedFacilitators)
}

trait ConsensusOps[S, Kind] {
  def collectedKinds(status: S): Set[Kind]
  def maybeCollectingKind(status: S): Option[Kind]
  def kindGetter: Kind => PeerDeclarations => Option[PeerDeclaration]
}

@derive(eqv)
case class ArtifactInfo[A, C](artifact: A, context: C, hash: Hash)
object ArtifactInfo {
  implicit def showInstance[A, C]: Show[ArtifactInfo[A, C]] = pi => s"ArtifactInfo{hash=${pi.hash.show}}"
}

@derive(eqv, show)
sealed trait LockStatus

object LockStatus {
  case object Open extends LockStatus
  case object Closed extends LockStatus
  case object Reopened extends LockStatus
}
