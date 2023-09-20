package org.tessellation.sdk.infrastructure.snapshot

import cats.MonadThrow
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._

import scala.util.control.NoStackTrace

import org.tessellation.schema.peer
import org.tessellation.sdk.domain.snapshot.ProposalSelect
import org.tessellation.sdk.infrastructure.consensus.PeerDeclarations
import org.tessellation.security.hash
import org.tessellation.security.hash.Hash

import eu.timepit.refined.types.numeric.PosDouble

object ProposalSelectWithFallback {

  case object FailedToScore extends NoStackTrace

  def make[F[_]: MonadThrow](
    primary: ProposalSelect[F],
    fallbacks: ProposalSelect[F]*
  ): ProposalSelect[F] = new ProposalSelect[F] {
    val strategies: List[ProposalSelect[F]] = primary :: List.from(fallbacks)

    def score(declarations: Map[peer.PeerId, PeerDeclarations]): F[List[(hash.Hash, PosDouble)]] = {
      type Result = List[(Hash, PosDouble)]
      type Agg = List[ProposalSelect[F]]

      strategies.tailRecM {
        case Nil => List.empty[(Hash, PosDouble)].asRight[Agg].pure[F]
        case strategy :: tail =>
          strategy
            .score(declarations)
            .map(ls => Either.cond(ls.nonEmpty, ls, tail))
      }
    }
  }

}
