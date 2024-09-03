package io.constellationnetwork.dag.l1.domain.consensus.block.storage

import cats.effect.{Ref, Sync}
import cats.syntax.flatMap._
import cats.syntax.functor._

import io.constellationnetwork.dag.l1.domain.consensus.block.RoundData
import io.constellationnetwork.schema.round.RoundId

import io.chrisdavenport.mapref.MapRef

class ConsensusStorage[F[_]](
  val ownConsensus: Ref[F, Option[RoundData]],
  val peerConsensuses: MapRef[F, RoundId, Option[RoundData]]
)

object ConsensusStorage {

  def make[F[_]: Sync]: F[ConsensusStorage[F]] =
    for {
      peerConsensuses <- MapRef.ofConcurrentHashMap[F, RoundId, RoundData]()
      ownConsensus <- Ref.of[F, Option[RoundData]](None)
    } yield new ConsensusStorage(ownConsensus, peerConsensuses)
}
