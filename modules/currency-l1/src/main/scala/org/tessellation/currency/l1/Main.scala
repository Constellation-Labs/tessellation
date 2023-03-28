package org.tessellation.currency.l1

import java.util.UUID

import cats.Applicative
import cats.effect.kernel.Resource
import cats.effect.std.Supervisor
import cats.effect.{Async, IO}
import cats.syntax.flatMap._
import cats.syntax.semigroupk._

import scala.concurrent.duration._

import org.tessellation.BuildInfo
import org.tessellation.currency.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import org.tessellation.currency.l1.domain.snapshot.programs.CurrencySnapshotProcessor
import org.tessellation.currency.l1.modules.{Programs, Storages}
import org.tessellation.currency.schema.currency.{CurrencyBlock, CurrencySnapshot, CurrencyTransaction}
import org.tessellation.currency.{CurrencyKryoRegistrationIdRange, currencyKryoRegistrar}
import org.tessellation.dag.l1.http.p2p.P2PClient
import org.tessellation.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.tessellation.dag.l1.modules._
import org.tessellation.dag.l1.{DagL1KryoRegistrationIdRange, StateChannel, dagL1KryoRegistrar}
import org.tessellation.ext.cats.effect.ResourceF
import org.tessellation.ext.kryo.{KryoRegistrationId, MapRegistrationId}
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.SessionStarted
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.sdk.{SdkOrSharedOrKernelRegistrationIdRange, sdkKryoRegistrar}

import com.monovore.decline.Opts
import eu.timepit.refined.boolean.Or
import fs2.Stream

object Main extends CurrencyL1App("", "") {
  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      queues <- Queues.make[IO, CurrencyTransaction, CurrencyBlock](sdkQueues).asResource
      storages <- Storages
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](sdkStorages, method.l0Peer, method.globalL0Peer)
        .asResource
      validators = Validators.make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](storages, seedlist)
      p2pClient = P2PClient.make[IO, CurrencyTransaction, CurrencyBlock](sdkP2PClient, sdkResources.client, currencyPathPrefix = "currency")
      services = Services
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](
          storages,
          storages.lastGlobalSnapshot,
          storages.globalL0Cluster,
          validators,
          sdkServices,
          p2pClient,
          cfg
        )
      snapshotProcessor = CurrencySnapshotProcessor.make(
        method.identifier,
        storages.address,
        storages.block,
        storages.lastGlobalSnapshot,
        storages.lastSnapshot,
        storages.transaction
      )
      programs = Programs.make(sdkPrograms, p2pClient, storages, snapshotProcessor)
      healthChecks <- HealthChecks
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](
          storages,
          services,
          programs,
          p2pClient,
          sdkResources.client,
          sdkServices.session,
          cfg.healthCheck,
          sdk.nodeId
        )
        .asResource

      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck).handlers <+>
        blockRumorHandler[IO, CurrencyBlock](queues.peerBlock)

      _ <- Daemons
        .start(storages, services, healthChecks)
        .asResource

      api = HttpApi
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](
          storages,
          queues,
          keyPair.getPrivate,
          services,
          programs,
          healthChecks,
          sdk.nodeId,
          BuildInfo.version,
          cfg.http
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      stateChannel <- StateChannel
        .make[IO, CurrencyTransaction, CurrencyBlock, CurrencySnapshot](
          cfg,
          keyPair,
          p2pClient,
          programs,
          queues,
          nodeId,
          services,
          storages,
          validators
        )
        .asResource

      gossipDaemon = GossipDaemon.make[IO](
        storages.rumor,
        queues.rumor,
        storages.cluster,
        p2pClient.gossip,
        rumorHandler,
        validators.rumorValidator,
        services.localHealthcheck,
        nodeId,
        generation,
        cfg.gossip.daemon,
        services.collateral
      )

      _ <- run(method, gossipDaemon, programs, storages, services, stateChannel)
    } yield ()
  }
}

abstract class CurrencyL1App(name: String, header: String)
    extends TessellationIOApp[Run](
      s"Currency-l1 - $name",
      s"Currency L1 node - $header",
      ClusterId(UUID.fromString("517c3a05-9219-471b-a54c-21b7d72f4ae5")),
      version = BuildInfo.version
    ) {

  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = CurrencyKryoRegistrationIdRange Or SdkOrSharedOrKernelRegistrationIdRange Or DagL1KryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    currencyKryoRegistrar.union(sdkKryoRegistrar).union(dagL1KryoRegistrar)

  final def run[F[_]: Supervisor: Async](
    method: Run,
    gossipDaemon: GossipDaemon[F],
    programs: Programs[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot],
    storages: Storages[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot],
    services: Services[F, CurrencyTransaction, CurrencyBlock],
    stateChannel: StateChannel[F, CurrencyTransaction, CurrencyBlock, CurrencySnapshot]
  ): Resource[F, Unit] = for {
    _ <- {
      method match {
        case cfg: RunInitialValidator =>
          gossipDaemon.startAsInitialValidator >>
            programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
            programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.tryModifyState(SessionStarted, NodeState.Ready)

        case cfg: RunValidator =>
          gossipDaemon.startAsRegularValidator >>
            programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
            programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
      }
    }.asResource
    globalL0PeerDiscovery = Stream
      .awakeEvery[F](10.seconds)
      .evalMap { _ =>
        storages.lastGlobalSnapshot.get.flatMap {
          _.fold(Applicative[F].unit) { latestSnapshot =>
            programs.globalL0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
          }
        }
      }
    _ <- stateChannel.runtime.merge(globalL0PeerDiscovery).compile.drain.asResource
  } yield ()
}
