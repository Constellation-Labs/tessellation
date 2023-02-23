package org.tessellation.currency

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.applicative._
import cats.syntax.semigroupk._
import org.tessellation.currency.cli.method
import org.tessellation.currency.cli.method.{Run, RunGenesis, RunValidator}
import org.tessellation.currency.http.P2PClient
import org.tessellation.currency.modules._
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.sdk.{SdkOrSharedOrKernelRegistrationIdRange, sdkKryoRegistrar}
import com.monovore.decline.Opts
import eu.timepit.refined.boolean.Or
import org.tessellation.currency.schema.currency.CurrencySnapshot
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.cli.CliMethod
import org.tessellation.sdk.domain.collateral.OwnCollateralNotSatisfied
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.security.signature.Signed

abstract class CurrencyApp[A <: CliMethod](
  header: String,
  clusterId: ClusterId,
  helpFlag: Boolean,
  version: String,
  tokenSymbol: String
) extends TessellationIOApp[A](clusterId.toString, header, clusterId, helpFlag, version) {
  type KryoRegistrationIdRange = CurrencyKryoRegistrationIdRange Or SdkOrSharedOrKernelRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    currencyKryoRegistrar.union(sdkKryoRegistrar)

  def run(method: A, sdk: SDK[IO]): Resource[IO, Unit]
}

abstract class CurrencyL0App(
  header: String,
  clusterId: ClusterId,
  helpFlag: Boolean,
  version: String,
  tokenSymbol: String
) extends CurrencyApp[Run](header, clusterId, helpFlag, version, tokenSymbol) {

  val opts: Opts[Run] = method.opts
  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      _ <- Resource.unit
      p2pClient = P2PClient.make[IO](sdkP2PClient, sdkResources.client, sdkServices.session)
      queues <- Queues.make[IO](sdkQueues).asResource
      storages <- Storages.make[IO](sdkStorages, cfg.snapshot).asResource
      validators = Validators.make[IO](seedlist)
      services <- Services
        .make[IO](
          sdkServices,
          queues,
          storages,
          validators,
          sdkResources.client,
          sdkServices.session,
          sdk.seedlist,
          sdk.nodeId,
          keyPair,
          cfg
        )
        .asResource
      programs = Programs.make[IO](sdkPrograms, storages, services)
      healthChecks <- HealthChecks
        .make[IO](
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
        services.consensus.handler
      _ <- Daemons
        .start(storages, services, programs, queues, healthChecks)
        .asResource
      api = HttpApi
        .make[IO](
          storages,
          queues,
          services,
          programs,
          healthChecks,
          keyPair.getPrivate,
          cfg.environment,
          sdk.nodeId,
          BuildInfo.version,
          cfg.http
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

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

      _ <- (method match {
        case _: RunValidator =>
          gossipDaemon.startAsRegularValidator >>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
        case m: RunGenesis =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.LoadingGenesis,
            NodeState.GenesisReady
          ) {
            GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
              val genesis = CurrencySnapshot.mkGenesis(
                accounts.map(a => (a.address, a.balance)).toMap
              )

              Signed.forAsyncKryo[IO, CurrencySnapshot](genesis, keyPair).flatMap { signedGenesis =>
                storages.snapshot.prepend(signedGenesis) >>
                  services.collateral
                    .hasCollateral(sdk.nodeId)
                    .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                  services.consensus.manager.startFacilitatingAfter(genesis.ordinal, signedGenesis)
              }
            }
          } >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready)
      }).asResource
    } yield ()
  }
}
