package org.tessellation

import cats.ApplicativeError
import cats.effect._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.semigroupk._

import org.tessellation.cli.method._
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.kryo._
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.trust.handler.trustHandler
import org.tessellation.modules._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.{GlobalSnapshot, IncrementalGlobalSnapshot}
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.domain.collateral.OwnCollateralNotSatisfied
import org.tessellation.sdk.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._

object Main
    extends TessellationIOApp[Run](
      name = "dag-l0",
      header = "Tessellation Node",
      version = BuildInfo.version,
      clusterId = ClusterId("6d7f1d6a-213a-4148-9d45-d7200f555ecf")
    ) {

  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = CoreKryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    coreKryoRegistrar

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      queues <- Queues.make[IO](sdkQueues).asResource
      p2pClient = P2PClient.make[IO](sdkP2PClient, sdkResources.client, sdkServices.session)
      maybeRollbackHash = Option(method).collect { case rr: RunRollback => rr.rollbackHash }
      storages <- Storages.make[IO](sdkStorages, cfg.snapshot, maybeRollbackHash).asResource
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
        trustHandler(storages.trust) <+> services.consensus.handler

      _ <- Daemons
        .start(storages, services, programs, queues, healthChecks, nodeId, cfg)
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
        case _: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {

            storages.globalSnapshot.head.flatMap {
              ApplicativeError.liftFromOption[IO](_, new Throwable(s"Rollback performed but snapshot not found!")).flatMap {
                case (snapshot, state) =>
                  services.collateral
                    .hasCollateral(sdk.nodeId)
                    .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                    services.consensus.manager.startFacilitatingAfter(snapshot.ordinal, snapshot, state)
              }
            }
          } >>
            gossipDaemon.startAsInitialValidator >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready)
        case m: RunGenesis =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.LoadingGenesis,
            NodeState.GenesisReady
          ) {
            GenesisLoader.make[IO].load(m.genesisPath).flatMap { accounts =>
              val genesis = GlobalSnapshot.mkGenesis(
                accounts.map(a => (a.address, a.balance)).toMap,
                m.startingEpochProgress
              )

              Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap(_.toHashed[IO]).flatMap { hashedGenesis =>
                GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis).flatMap { firstIncrementalSnapshot =>
                  Signed.forAsyncKryo[IO, IncrementalGlobalSnapshot](firstIncrementalSnapshot, keyPair).flatMap {
                    signedFirstIncrementalSnapshot =>
                      storages.globalSnapshot.prepend(signedFirstIncrementalSnapshot, hashedGenesis.info) >>
                        services.collateral.hasCollateral(sdk.nodeId).flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                        services.consensus.manager
                          .startFacilitatingAfter(
                            signedFirstIncrementalSnapshot.ordinal,
                            signedFirstIncrementalSnapshot,
                            hashedGenesis.info
                          )

                  }
                }
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
