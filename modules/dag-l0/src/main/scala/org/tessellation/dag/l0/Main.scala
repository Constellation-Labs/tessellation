package org.tessellation.dag.l0

import cats.effect._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.semigroupk._

import org.tessellation.BuildInfo
import org.tessellation.dag.l0.cli.method._
import org.tessellation.dag.l0.http.p2p.P2PClient
import org.tessellation.dag.l0.infrastructure.trust.handler.{ordinalTrustHandler, trustHandler}
import org.tessellation.dag.l0.modules._
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.kryo._
import org.tessellation.node.shared.app.{NodeShared, TessellationIOApp}
import org.tessellation.node.shared.domain.collateral.OwnCollateralNotSatisfied
import org.tessellation.node.shared.infrastructure.genesis.{GenesisFS => GenesisLoader}
import org.tessellation.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.node.shared.resources.MkHttpServer
import org.tessellation.node.shared.resources.MkHttpServer.ServerName
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.semver.TessellationVersion
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot}
import org.tessellation.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._

object Main
    extends TessellationIOApp[Run](
      name = "dag-l0",
      header = "Tessellation Node",
      version = TessellationVersion.unsafeFrom(BuildInfo.version),
      clusterId = ClusterId("6d7f1d6a-213a-4148-9d45-d7200f555ecf")
    ) {

  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = DagL0KryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    dagL0KryoRegistrar

  def run(method: Run, nodeShared: NodeShared[IO]): Resource[IO, Unit] = {
    import nodeShared._

    val cfg = method.appConfig

    for {
      queues <- Queues.make[IO](sharedQueues).asResource
      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session)
      storages <- Storages
        .make[IO](
          sharedStorages,
          method.nodeSharedConfig,
          nodeShared.seedlist,
          cfg.snapshot,
          trustRatings,
          cfg.environment,
          hashSelect
        )
        .asResource
      services <- Services
        .make[IO](
          sharedServices,
          queues,
          storages,
          nodeShared.sharedValidators,
          sharedResources.client,
          sharedServices.session,
          nodeShared.seedlist,
          method.stateChannelAllowanceLists,
          nodeShared.nodeId,
          keyPair,
          cfg
        )
        .asResource
      programs = Programs.make[IO](
        sharedPrograms,
        storages,
        services,
        keyPair,
        cfg,
        method.lastFullGlobalSnapshotOrdinal,
        p2pClient,
        sharedServices.globalSnapshotContextFns,
        hashSelect
      )
      healthChecks <- HealthChecks
        .make[IO](
          storages,
          services,
          programs,
          p2pClient,
          sharedResources.client,
          sharedServices.session,
          cfg.healthCheck,
          nodeShared.nodeId
        )
        .asResource

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        trustHandler(storages.trust) <+> ordinalTrustHandler(storages.trust) <+> services.consensus.handler

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
          nodeShared.nodeId,
          TessellationVersion.unsafeFrom(BuildInfo.version),
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
        nodeShared.sharedValidators.rumorValidator,
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
            programs.rollbackLoader.load(m.rollbackHash).flatMap {
              case (snapshotInfo, snapshot) =>
                storages.globalSnapshot
                  .prepend(snapshot, snapshotInfo) >>
                  services.consensus.manager.startFacilitatingAfterRollback(snapshot.ordinal, snapshot, snapshotInfo)
            }
          } >>
            services.collateral
              .hasCollateral(nodeShared.nodeId)
              .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
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
            GenesisLoader.make[IO, GlobalSnapshot].loadBalances(m.genesisPath).flatMap { accounts =>
              val genesis = GlobalSnapshot.mkGenesis(
                accounts.map(a => (a.address, a.balance)).toMap,
                m.startingEpochProgress
              )

              Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair).flatMap(_.toHashed[IO]).flatMap { hashedGenesis =>
                SnapshotLocalFileSystemStorage.make[IO, GlobalSnapshot](cfg.snapshot.snapshotPath).flatMap {
                  fullGlobalSnapshotLocalFileSystemStorage =>
                    fullGlobalSnapshotLocalFileSystemStorage.write(hashedGenesis.signed) >>
                      GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis, hashSelect).flatMap { firstIncrementalSnapshot =>
                        Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair).flatMap {
                          signedFirstIncrementalSnapshot =>
                            storages.globalSnapshot.prepend(signedFirstIncrementalSnapshot, hashedGenesis.info) >>
                              services.collateral
                                .hasCollateral(nodeShared.nodeId)
                                .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                              services.consensus.manager
                                .startFacilitatingAfterRollback(
                                  signedFirstIncrementalSnapshot.ordinal,
                                  signedFirstIncrementalSnapshot,
                                  hashedGenesis.info
                                )

                        }
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
