package io.constellationnetwork.dag.l0.http.routes

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class GL0TokenLockRoutes[F[_]: Async](
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F]("TokenLockRoutesLogger")

  protected val prefixPath: InternalUrlPrefix = "/"

  private def getActiveTokenLocks(address: Address, info: GlobalSnapshotInfo): List[TokenLock] = {
    val lastActiveTokenLocks = info.activeTokenLocks.getOrElse(SortedMap.empty[Address, SortedSet[Signed[TokenLock]]])
    lastActiveTokenLocks.getOrElse(address, SortedSet.empty[Signed[TokenLock]]).toList.map(_.value)
  }

  override protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "token-locks" / AddressVar(address) =>
      snapshotStorage.head.flatMap {
        case Some((_, info)) =>
          Ok(getActiveTokenLocks(address, info))
        case None => ServiceUnavailable()
      }

  }
}
