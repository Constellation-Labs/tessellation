package io.constellationnetwork.node.shared.infrastructure.fork

import cats.effect.Sync
import cats.syntax.all._

import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hex.Hex

object ExitOnFork {

  def panicExit(flag: String): Unit = {
    // Avoid F logger due to immediate kill
    println(s"Exit due to feature flag on $flag")
    System.exit(1)
  }

  def hasFlag[F[_]: Sync](flag: String): F[Boolean] =
    Sync[F].delay(sys.env.get(flag).contains("true"))

  def exitOnFeature[F[_]: Sync](flag: String): F[Unit] =
    hasFlag[F](flag).map { x =>
      if (x) {
        panicExit(flag)
      }
    }

  def exitOnCheck[F[_]: Sync](flag: String, facilitators: () => Set[PeerId]): F[Unit] =
    hasFlag[F](flag).flatMap { x =>
      if (x) Sync[F].delay(sys.env.get("CL_FOLLOWER_ID"))
      else Sync[F].pure(None: Option[String])
    }.flatMap(_.traverse_ { id =>
      for {
        peers <- Sync[F].delay(facilitators())
        peerId = PeerId(Hex(id))
        hasFollowerPeer = peers.contains(peerId)
        _ = if (!hasFollowerPeer) panicExit(flag)
        _ <- Sync[F].unit
      } yield ()
    })

}
