package org.tessellation.sdk.infrastructure.gossip

import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.order._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import org.tessellation.schema.gossip._
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.config.types.RumorStorageConfig
import org.tessellation.sdk.domain.gossip.RumorStorage
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import io.chrisdavenport.mapref.MapRef

object RumorStorage {

  def make[F[_]: Async](cfg: RumorStorageConfig): F[RumorStorage[F]] =
    for {
      active <- MapRef.ofConcurrentHashMap[F, Hash, Signed[Rumor]]()
      seen <- MapRef.ofConcurrentHashMap[F, Hash, Unit]()
      seenCounter <- MapRef.ofConcurrentHashMap[F, (PeerId, ContentType), Ordinal]()
    } yield make(active, seen, seenCounter, cfg)

  def make[F[_]: Async](
    active: MapRef[F, Hash, Option[Signed[Rumor]]],
    seen: MapRef[F, Hash, Option[Unit]],
    maxOrdinal: MapRef[F, (PeerId, ContentType), Option[Ordinal]],
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

      def tryUpdateOrdinal(rumor: Rumor): F[Boolean] =
        maxOrdinal((rumor.origin, rumor.contentType)).modify {
          case Some(k) => (k.max(rumor.ordinal).some, k < rumor.ordinal)
          case None    => (rumor.ordinal.some, true)
        }

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
