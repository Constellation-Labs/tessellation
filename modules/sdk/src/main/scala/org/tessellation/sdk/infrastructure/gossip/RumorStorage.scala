package org.tessellation.sdk.infrastructure.gossip

import cats.effect.{Async, Spawn, Temporal}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._

import org.tessellation.schema.gossip._
import org.tessellation.sdk.config.types.RumorStorageConfig
import org.tessellation.sdk.domain.gossip.RumorStorage
import org.tessellation.security.Hashed
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import eu.timepit.refined.auto.autoUnwrap
import io.chrisdavenport.mapref.MapRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

object RumorStorage {

  def make[F[_]: Async](cfg: RumorStorageConfig): F[RumorStorage[F]] =
    for {
      active <- MapRef.ofConcurrentHashMap[F, Hash, Signed[RumorRaw]]()
      seen <- MapRef.ofConcurrentHashMap[F, Hash, Unit]()
    } yield make(active, seen, cfg)

  def make[F[_]: Async](
    active: MapRef[F, Hash, Option[Signed[RumorRaw]]],
    seen: MapRef[F, Hash, Option[Unit]],
    cfg: RumorStorageConfig
  ): RumorStorage[F] =
    new RumorStorage[F] {

      private val rumorLogger = Slf4jLogger.getLoggerFromName[F](rumorLoggerName)

      def getRumors(hashes: List[Hash]): F[List[Signed[RumorRaw]]] =
        hashes.flatTraverse { hash =>
          active(hash).get.map(_.toList)
        }

      def addRumorIfNotSeen(hashedRumor: Hashed[RumorRaw]): F[Boolean] =
        seen(hashedRumor.hash)
          .getAndSet(().some)
          .map(_.isEmpty)
          .flatTap(setRumorActive(hashedRumor).whenA(_))

      def getRumor(hash: Hash): F[Option[Signed[RumorRaw]]] =
        active(hash).get

      def getActiveHashes: F[List[Hash]] = active.keys

      def getSeenHashes: F[List[Hash]] = seen.keys

      private def setRumorActive(hashedRumor: Hashed[RumorRaw]): F[Unit] =
        active(hashedRumor.hash).set(hashedRumor.signed.some) >> Spawn[F].start(setRetention(hashedRumor)).void

      private def setRetention(hashedRumor: Hashed[RumorRaw]): F[Unit] =
        Temporal[F].sleep(cfg.activeRetention) >>
          active(hashedRumor.hash).set(none[Signed[RumorRaw]]) >>
          rumorLogger.info(s"Rumor deactivated {hash=${hashedRumor.hash.show}}") >>
          Temporal[F].sleep(cfg.seenRetention) >>
          seen(hashedRumor.hash).set(none[Unit])

    }

}
