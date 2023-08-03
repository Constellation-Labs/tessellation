package org.tessellation.dag.l1.domain.consensus.block.storage

import cats.effect.{Ref, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._

import org.tessellation.dag.l1.domain.consensus.block.RoundData
import org.tessellation.schema.Block
import org.tessellation.schema.round.RoundId

import io.chrisdavenport.mapref.MapRef

class ConsensusStorage[F[_], B <: Block](
  val ownConsensus: Ref[F, Option[RoundData[B]]],
  val peerConsensuses: MapRef[F, RoundId, Option[RoundData[B]]]
)

object ConsensusStorage {

  def make[F[_]: Sync, B <: Block]: F[ConsensusStorage[F, B]] =
    for {
      peerConsensuses <- MapRef.ofConcurrentHashMap[F, RoundId, RoundData[B]]()
      ownConsensus <- Ref.of[F, Option[RoundData[B]]](None)
    } yield new ConsensusStorage(ownConsensus, peerConsensuses)
}
