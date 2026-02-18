package io.constellationnetwork.node.shared.infrastructure.snapshot.storage

import cats.effect.kernel.{Async, Clock, Ref}
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import scala.concurrent.duration._

import io.constellationnetwork.node.shared.domain.snapshot.storage.{PeerAvailability => PeerAvailabilityAlgebra}
import io.constellationnetwork.schema.peer.{Peer, PeerId}

object PeerAvailability {

  private val stalenessThreshold: FiniteDuration = 5.minutes
  private val reprobeChance: Double = 0.2

  private case class Entry(
    successCount: Int,
    failureCount: Int,
    lastSuccess: Option[FiniteDuration],
    lastFailure: Option[FiniteDuration]
  )

  private sealed trait Tier
  private case object Good extends Tier
  private case object Unknown extends Tier
  private case object Bad extends Tier

  def make[F[_]: Async: Random]: F[PeerAvailabilityAlgebra[F]] =
    Ref.of[F, Map[PeerId, Entry]](Map.empty).map { ref =>
      new PeerAvailabilityAlgebra[F] {

        def recordSuccess(peer: Peer): F[Unit] =
          Clock[F].monotonic.flatMap { now =>
            ref.update { entries =>
              val prev = entries.getOrElse(peer.id, Entry(0, 0, None, None))
              entries.updated(peer.id, prev.copy(
                successCount = prev.successCount + 1,
                lastSuccess = Some(now)
              ))
            }
          }

        def recordFailure(peer: Peer): F[Unit] =
          Clock[F].monotonic.flatMap { now =>
            ref.update { entries =>
              val prev = entries.getOrElse(peer.id, Entry(0, 0, None, None))
              entries.updated(peer.id, prev.copy(
                failureCount = prev.failureCount + 1,
                lastFailure = Some(now)
              ))
            }
          }

        def sortByAvailability(peers: List[Peer]): F[List[Peer]] =
          for {
            now <- Clock[F].monotonic
            entries <- ref.get
            classified <- peers.traverse { peer =>
              classifyPeer(now, entries.get(peer.id)).map(tier => (peer, tier))
            }
            good = classified.collect { case (p, Good) => p }
            unknown = classified.collect { case (p, Unknown) => p }
            bad = classified.collect { case (p, Bad) => p }
            shuffledGood <- Random[F].shuffleList(good)
            shuffledUnknown <- Random[F].shuffleList(unknown)
            shuffledBad <- Random[F].shuffleList(bad)
          } yield shuffledGood ++ shuffledUnknown ++ shuffledBad

        private def classifyPeer(now: FiniteDuration, maybeEntry: Option[Entry]): F[Tier] =
          maybeEntry match {
            case None => (Unknown: Tier).pure[F]
            case Some(entry) =>
              val isStale = entry.lastFailure.exists(lf => (now - lf) > stalenessThreshold)

              val isGood = entry.successCount > 0 &&
                (entry.failureCount == 0 || entry.lastSuccess.exists(ls =>
                  entry.lastFailure.forall(lf => ls > lf)
                ))

              if (isGood) (Good: Tier).pure[F]
              else if (isStale) (Unknown: Tier).pure[F]
              else Random[F].nextDouble.map { roll =>
                if (roll < reprobeChance) Unknown else Bad
              }
          }
      }
    }
}
