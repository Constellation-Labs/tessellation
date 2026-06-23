package io.constellationnetwork.dag.l0.http.routes

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.ext.http4s.AddressVar
import io.constellationnetwork.node.shared.domain.snapshot.storage.SnapshotStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.mpt.GlobalStateConverter.syntax._
import io.constellationnetwork.schema.mpt.{GlobalStateKey, MptStore}
import io.constellationnetwork.schema.tokenLock.TokenLock
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class GL0TokenLockRoutes[F[_]: Async](
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  mptStore: MptStore[F, GlobalStateKey]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F]("TokenLockRoutesLogger")

  protected val prefixPath: InternalUrlPrefix = "/"

  // v4.1.0: read the FULL active token-lock state from the MPT, NOT GlobalSnapshotInfo.activeTokenLocks.
  // After the MPT migration the GSI carries only the per-snapshot DELTA (the StateChangesAccumulator), so a
  // token lock committed in an earlier snapshot is absent from the head delta and this endpoint would wrongly
  // return "not found" for every ordinal after the one in which the lock changed (the delegated-staking /
  // token-lock-replacement e2e failures). Mirrors DelegatedStakesRoutes / NodeCollateralRoutes.
  private def getActiveTokenLocks(address: Address): F[List[TokenLock]] =
    mptStore
      .getActiveTokenLocks(address)
      .map(_.getOrElse(SortedSet.empty[Signed[TokenLock]]).toList.map(_.value))

  override protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "token-locks" / AddressVar(address) =>
      snapshotStorage.head.flatMap {
        case Some(_) => Ok(getActiveTokenLocks(address))
        case None    => ServiceUnavailable()
      }

  }
}
