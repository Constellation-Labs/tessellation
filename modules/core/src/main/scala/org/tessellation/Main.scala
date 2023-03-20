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
import org.tessellation.infrastructure.snapshot.{GlobalSnapshotLoader, GlobalSnapshotTraverse}
import org.tessellation.infrastructure.trust.handler.trustHandler
import org.tessellation.modules._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot}
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
      p2pClient = P2PClient.make[IO](sdkP2PClient)
      storages <- Storages.make[IO](sdkStorages, cfg.snapshot).asResource
      services <- Services
        .make[IO](
          sdkServices,
          queues,
          storages,
          sdk.sdkValidators,
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
        sdk.sdkValidators.rumorValidator,
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
        case m: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {
            storages.incrementalGlobalSnapshotLocalFileSystemStorage
              .read(m.rollbackHash)
              .flatMap(ApplicativeError.liftFromOption[IO](_, new Throwable(s"Rollback performed but snapshot not found!")))
              .flatMap { snapshot =>
                GlobalSnapshotLoader.make[IO](storages.incrementalGlobalSnapshotLocalFileSystemStorage, cfg.snapshot.snapshotPath).flatMap {
                  loader =>
                    val snapshotTraverse = GlobalSnapshotTraverse.make[IO](loader.readGlobalSnapshot, services.snapshotContextFunctions)
                    snapshotTraverse.computeState(snapshot).flatMap { snapshotInfo =>
                      storages.globalSnapshot.prepend(snapshot, snapshotInfo) >>
                        services.collateral
                          .hasCollateral(sdk.nodeId)
                          .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                        services.consensus.manager.startFacilitatingAfter(snapshot.ordinal, snapshot, snapshotInfo)
                    }
                }
              } >>
              gossipDaemon.startAsInitialValidator >>
              services.cluster.createSession >>
              services.session.createSession >>
              storages.node.setNodeState(NodeState.Ready)
          }
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
                  Signed.forAsyncKryo[IO, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).flatMap {
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
