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
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import org.http4s.HttpRoutes
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.dsl.Http4sDsl
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class GL0TokenLockRoutes[F[_]: Async: Hasher](
  snapshotStorage: SnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  mptStore: MptStore[F, GlobalStateKey]
) extends Http4sDsl[F]
    with PublicRoutes[F] {

  private val logger = Slf4jLogger.getLoggerFromName[F]("TokenLockRoutesLogger")

  protected val prefixPath: InternalUrlPrefix = "/"

  // v4.1.0: serve the FULL active token-lock state from the MPT as TokenLockView (transaction + hash + status).
  // After the MPT migration GlobalSnapshotInfo.activeTokenLocks carries only the per-snapshot DELTA, so reading
  // head.info would wrongly return "not found" for any lock committed in an earlier snapshot (the delegated-staking
  // / token-lock-replacement e2e failures). The served hash is the canonical TokenLockReference (signed.value.hash)
  // -- the same value a client passes as a delegated stake's tokenLockRef -- so the lock can be matched by hash,
  // consistent with the gl1 by-hash route and the DelegatedStakes / NodeCollateral info routes.
  private def getActiveTokenLocks(address: Address): F[List[TokenLockView]] =
    mptStore
      .getActiveTokenLocks(address)
      .map(_.getOrElse(SortedSet.empty[Signed[TokenLock]]).toList)
      .flatMap(_.traverse { signed =>
        TokenLockReference.of(signed).map(ref => TokenLockView(signed.value, ref.hash, TokenLockStatus.Waiting))
      })

  override protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "token-locks" / AddressVar(address) =>
      snapshotStorage.head.flatMap {
        case Some(_) => Ok(getActiveTokenLocks(address))
        case None    => ServiceUnavailable()
      }

  }
}
