package org.tessellation.node.shared

import cats.data.NonEmptySet
import cats.effect.Sync
import cats.syntax.all._

import org.tessellation.env.AppEnvironment
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.schema.peer.PeerId
import org.tessellation.syntax.sortedCollection.sortedSetSyntax

import org.typelevel.log4cats.slf4j.Slf4jLogger

package object app {

  def getMajorityPeerIds[F[_]: Sync](
    prioritySeedlist: Option[Set[SeedlistEntry]],
    defaultMajorityPeerIds: Option[NonEmptySet[PeerId]],
    env: AppEnvironment
  ): F[Option[NonEmptySet[PeerId]]] =
    prioritySeedlist
      .map(_.map(_.peerId))
      .flatMap(_.toSortedSet.toNes)
      .orElse(defaultMajorityPeerIds)
      .pure[F]
      .flatTap(ids => Slf4jLogger.getLogger[F].info(s"Majority Peer IDs ${ids.show}"))
      .reject {
        case None if env =!= AppEnvironment.Dev =>
          new IllegalStateException("Majority Peer IDs required")
      }

}
