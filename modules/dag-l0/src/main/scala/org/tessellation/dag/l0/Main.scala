package org.tessellation.dag.l0

import cats.effect._
import cats.syntax.all._

import org.tessellation.BuildInfo
import org.tessellation.dag.l0.cli.method._
import org.tessellation.dag.l0.config.types._
import org.tessellation.dag.l0.http.p2p.P2PClient
import org.tessellation.dag.l0.infrastructure.snapshot.schema.{Finished, GlobalConsensusOutcome}
import org.tessellation.dag.l0.infrastructure.trust.handler.{ordinalTrustHandler, trustHandler}
import org.tessellation.dag.l0.modules._
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.kryo._
import org.tessellation.node.shared.app.{NodeShared, TessellationIOApp}
import org.tessellation.node.shared.domain.collateral.OwnCollateralNotSatisfied
import org.tessellation.node.shared.ext.pureconfig._
import org.tessellation.node.shared.infrastructure.consensus._
import org.tessellation.node.shared.infrastructure.consensus.trigger.EventTrigger
import org.tessellation.node.shared.infrastructure.genesis.{GenesisFS => GenesisLoader}
import org.tessellation.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.node.shared.infrastructure.snapshot.storage.SnapshotLocalFileSystemStorage
import org.tessellation.node.shared.resources.MkHttpServer
import org.tessellation.node.shared.resources.MkHttpServer.ServerName
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.semver.TessellationVersion
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshot, SnapshotOrdinal}
import org.tessellation.security.Hasher
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.enumeratum._

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

    for {
      cfgR <- ConfigSource.default.loadF[IO, AppConfigReader]().asResource
      cfg = method.appConfig(cfgR, sharedConfig)
      queues <- Queues.make[IO](sharedQueues).asResource

      p2pClient = P2PClient.make[IO](sharedP2PClient, sharedResources.client, sharedServices.session)
      storages <- Storages
        .make[IO](
          sharedStorages,
          sharedConfig,
          nodeShared.seedlist,
          cfg.snapshot,
          cfg.incremental,
          trustRatings,
          sharedConfig.environment,
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
          cfg,
          Hasher.forKryo[IO]
        )
        .asResource
      programs = Programs.make[IO](
        sharedPrograms,
        storages,
        services,
        keyPair,
        cfg,
        cfg.incremental.lastFullGlobalSnapshotOrdinal.getOrElse(cfg.environment, SnapshotOrdinal.MinValue),
        p2pClient,
        sharedServices.globalSnapshotContextFns,
        hashSelect
      )

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        trustHandler(storages.trust) <+> ordinalTrustHandler(storages.trust) <+> services.consensus.handler

      _ <- Daemons
        .start(storages, services, programs, queues, nodeId, cfg, hasherSelector)
        .asResource

      api = HttpApi
        .make[IO](
          storages,
          queues,
          services,
          programs,
          keyPair.getPrivate,
          sharedConfig.environment,
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
        sharedConfig.gossip.daemon,
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
                hasherSelector.forOrdinal(snapshot.ordinal) { implicit hasher =>
                  storages.globalSnapshot
                    .prepend(snapshot, snapshotInfo)
                } >>
                  services.consensus.manager.startFacilitatingAfterRollback(
                    snapshot.ordinal,
                    GlobalConsensusOutcome(
                      snapshot.ordinal,
                      Facilitators(List(nodeId)),
                      RemovedFacilitators.empty,
                      WithdrawnFacilitators.empty,
                      Finished(snapshot, snapshotInfo, EventTrigger, Candidates.empty, Hash.empty)
                    )
                  )
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

              hasherSelector
                .forOrdinal(genesis.ordinal) { implicit hasher =>
                  Signed
                    .forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
                    .flatMap(_.toHashed[IO])
                }
                .flatMap { hashedGenesis =>
                  SnapshotLocalFileSystemStorage.make[IO, GlobalSnapshot](cfg.snapshot.snapshotPath).flatMap {
                    fullGlobalSnapshotLocalFileSystemStorage =>
                      hasherSelector
                        .forOrdinal(genesis.ordinal) { implicit hasher =>
                          fullGlobalSnapshotLocalFileSystemStorage.write(hashedGenesis.signed) >>
                            GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
                        }
                        .flatMap { firstIncrementalSnapshot =>
                          hasherSelector
                            .forOrdinal(firstIncrementalSnapshot.ordinal) { implicit hasher =>
                              Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](firstIncrementalSnapshot, keyPair)
                            }
                            .flatMap { signedFirstIncrementalSnapshot =>
                              hasherSelector.forOrdinal(signedFirstIncrementalSnapshot.ordinal) { implicit hasher =>
                                storages.globalSnapshot.prepend(signedFirstIncrementalSnapshot, hashedGenesis.info)
                              } >>
                                services.collateral
                                  .hasCollateral(nodeShared.nodeId)
                                  .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                                services.consensus.manager
                                  .startFacilitatingAfterRollback(
                                    signedFirstIncrementalSnapshot.ordinal,
                                    GlobalConsensusOutcome(
                                      signedFirstIncrementalSnapshot.ordinal,
                                      Facilitators(List(nodeId)),
                                      RemovedFacilitators.empty,
                                      WithdrawnFacilitators.empty,
                                      Finished(
                                        signedFirstIncrementalSnapshot,
                                        hashedGenesis.info,
                                        EventTrigger,
                                        Candidates.empty,
                                        Hash.empty
                                      )
                                    )
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
