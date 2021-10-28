package org.tesselation.infrastructure.gossip

import cats.Eq
import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import org.tesselation.config.types.RumorStorageConfig
import org.tesselation.domain.gossip.RumorStorage
import org.tesselation.schema._
import org.tesselation.schema.gossip.{HashAndRumor, Rumor, RumorBatch}
import org.tesselation.schema.peer.PeerId
import org.tesselation.security.hash.Hash
import org.tesselation.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import eu.timepit.refined.types.numeric.PosLong
import io.chrisdavenport.mapref.MapRef

object RumorStorage {

  def make[F[_]: Async](cfg: RumorStorageConfig): F[RumorStorage[F]] =
    for {
      active <- MapRef.ofConcurrentHashMap[F, Hash, Signed[Rumor]]()
      seen <- MapRef.ofConcurrentHashMap[F, Hash, Unit]()
      seenCounter <- MapRef.ofConcurrentHashMap[F, (PeerId, String), PosLong]()
    } yield make(active, seen, seenCounter, cfg)

  def make[F[_]: Async](
    active: MapRef[F, Hash, Option[Signed[Rumor]]],
    seen: MapRef[F, Hash, Option[Unit]],
    seenCounter: MapRef[F, (PeerId, String), Option[PosLong]],
    cfg: RumorStorageConfig
  ): RumorStorage[F] =
    new RumorStorage[F] {

      def addRumors(rumors: RumorBatch): F[RumorBatch] =
        for {
          unseen <- setRumorsSeenAndGetUnseen(rumors)
          _ <- setRumorsActive(unseen)
        } yield unseen

      def getRumors(hashes: List[Hash]): F[RumorBatch] =
        hashes.flatTraverse { hash =>
          active(hash).get.map(opt => opt.map(r => List(hash -> r)).getOrElse(List.empty[HashAndRumor]))
        }

      def getActiveHashes: F[List[Hash]] = active.keys

      def getSeenHashes: F[List[Hash]] = seen.keys

      def tryGetAndUpdateCounter(rumor: Rumor): F[Option[PosLong]] =
        seenCounter((rumor.origin, rumor.tpe)).getAndUpdate {
          _.fold(rumor.counter)(_.max(rumor.counter)).some
        }

      def resetCounter(peer: PeerId): F[Unit] =
        seenCounter.keys
          .map(_.filter {
            case (id, _) => Eq[PeerId].eqv(id, peer)
          })
          .flatMap(_.traverse(seenCounter(_).set(none)))
          .void

      private def setRumorsSeenAndGetUnseen(rumors: RumorBatch): F[RumorBatch] =
        for {
          unseen <- rumors.traverseFilter {
            case p @ (hash, _) =>
              seen(hash).getAndSet(().some).map {
                case Some(_) => none[HashAndRumor]
                case None    => p.some
              }
          }
        } yield unseen

      private def setRumorsActive(rumors: RumorBatch): F[Unit] =
        for {
          _ <- rumors.traverse { case (hash, rumor) => active(hash).set(rumor.some) }
          _ <- Spawn[F].start(setRetention(rumors))
        } yield ()

      private def setRetention(rumors: RumorBatch): F[Unit] =
        for {
          _ <- Temporal[F].sleep(cfg.activeRetention)
          _ <- rumors.traverse { case (hash, _) => active(hash).set(none[Signed[Rumor]]) }
          _ <- Temporal[F].sleep(cfg.seenRetention)
          _ <- rumors.traverse { case (hash, _) => seen(hash).set(none[Unit]) }
        } yield ()
    }

}
