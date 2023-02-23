package org.tessellation.currency.modules

import java.security.KeyPair

import cats.effect.kernel.Async
import cats.effect.std.{Random, Supervisor}
import cats.syntax.functor._

import org.tessellation.currency.config.types.AppConfig
import org.tessellation.currency.infrastructure.snapshot.{CurrencySnapshotConsensus, CurrencySnapshotEvent}
import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshot, CurrencyTransaction}
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.domain.cluster.services.{Cluster, Session}
import org.tessellation.sdk.domain.collateral.Collateral
import org.tessellation.sdk.domain.gossip.Gossip
import org.tessellation.sdk.domain.healthcheck.LocalHealthcheck
import org.tessellation.sdk.domain.snapshot.services.AddressService
import org.tessellation.sdk.infrastructure.Collateral
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.snapshot.SnapshotConsensus
import org.tessellation.sdk.infrastructure.snapshot.services.AddressService
import org.tessellation.sdk.modules.SdkServices
import org.tessellation.security.SecurityProvider

import org.http4s.client.Client

object Services {

  def make[F[_]: Async: Random: KryoSerializer: SecurityProvider: Metrics: Supervisor](
    sdkServices: SdkServices[F],
    queues: Queues[F],
    storages: Storages[F],
    validators: Validators[F],
    client: Client[F],
    session: Session[F],
    seedlist: Option[Set[PeerId]],
    selfId: PeerId,
    keyPair: KeyPair,
    cfg: AppConfig
  ): F[Services[F]] =
    for {
      consensus <- CurrencySnapshotConsensus
        .make[F](
          sdkServices.gossip,
          selfId,
          keyPair,
          seedlist,
          cfg.collateral.amount,
          storages.cluster,
          storages.node,
          storages.snapshot,
          validators.blockValidator,
          cfg.snapshot,
          cfg.environment,
          client,
          session
        )
      addressService = AddressService.make[F, CurrencySnapshot](storages.snapshot)
      collateralService = Collateral.make[F](cfg.collateral, storages.snapshot)
    } yield
      new Services[F](
        localHealthcheck = sdkServices.localHealthcheck,
        cluster = sdkServices.cluster,
        session = sdkServices.session,
        gossip = sdkServices.gossip,
        consensus = consensus,
        address = addressService,
        collateral = collateralService
      ) {}
}

sealed abstract class Services[F[_]] private (
  val localHealthcheck: LocalHealthcheck[F],
  val cluster: Cluster[F],
  val session: Session[F],
  val gossip: Gossip[F],
  val consensus: SnapshotConsensus[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot, CurrencySnapshotEvent],
  val address: AddressService[F, CurrencySnapshot],
  val collateral: Collateral[F]
)
