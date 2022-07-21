package org.tessellation

import cats.ApplicativeError
import cats.effect._
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.option._
import cats.syntax.semigroupk._

import org.tessellation.cli.method._
import org.tessellation.dag._
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.kryo._
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.genesis.{Loader => GenesisLoader}
import org.tessellation.infrastructure.trust.handler.trustHandler
import org.tessellation.modules._
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.sdk.app.{SDK, TessellationIOApp}
import org.tessellation.sdk.infrastructure.gossip.RumorHandlers
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.security.signature.Signed

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or

object Main
    extends TessellationIOApp[Run](
      name = "dag-l0",
      header = "Tessellation Node",
      version = BuildInfo.version,
      clusterId = ClusterId("6d7f1d6a-213a-4148-9d45-d7200f555ecf")
    ) {

  val opts: Opts[Run] = cli.method.opts

  type KryoRegistrationIdRange = CoreKryoRegistrationIdRange Or DagSharedKryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    coreKryoRegistrar.union(dagSharedKryoRegistrar)

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      _ <- IO.unit.asResource
      p2pClient = P2PClient.make[IO](sdkP2PClient, sdkResources.client, sdkServices.session)
      queues <- Queues.make[IO](sdkQueues).asResource
      maybeRollbackHash = Option(method).collect { case rr: RunRollback => rr.rollbackHash }
      storages <- Storages.make[IO](sdkStorages, cfg.snapshot, maybeRollbackHash).asResource
      validators = Validators.make[IO](whitelisting)
      services <- Services
        .make[IO](
          sdkServices,
          queues,
          storages,
          validators,
          sdkResources.client,
          sdkServices.session,
          sdk.whitelisting,
          sdk.nodeId,
          keyPair,
          cfg
        )
        .asResource
      programs = Programs.make[IO](sdkPrograms, storages, services, p2pClient)
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

      rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping).handlers <+>
        trustHandler(storages.trust) <+> services.consensus.handler

      _ <- Daemons
        .start(storages, services, programs, queues, healthChecks, validators, p2pClient, rumorHandler, nodeId, cfg)
        .asResource

      api = HttpApi
        .make[IO](storages, queues, services, programs, healthChecks, keyPair.getPrivate, cfg.environment, sdk.nodeId)
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      _ <- (method match {
        case _: RunValidator =>
          storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
        case _: RunRollback =>
          storages.node.tryModifyState(
            NodeState.Initial,
            NodeState.RollbackInProgress,
            NodeState.RollbackDone
          ) {

            storages.globalSnapshot.head.flatMap {
              ApplicativeError.liftFromOption[IO](_, new Throwable(s"Rollback performed but snapshot not found!")).flatMap {
                globalSnapshot =>
                  services.collateral
                    .hasCollateral(sdk.nodeId)
                    .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                    services.consensus.storage.setLastKeyAndArtifact((globalSnapshot.ordinal, globalSnapshot).some) >>
                    services.consensus.manager.scheduleTriggerOnTime
              }
            }
          } >>
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
              val genesis = GlobalSnapshot.mkGenesis(accounts.map(a => (a.address, a.balance)).toMap)

              Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair).flatMap { signedGenesis =>
                storages.globalSnapshot.prepend(signedGenesis) >>
                  services.collateral
                    .hasCollateral(sdk.nodeId)
                    .flatMap(OwnCollateralNotSatisfied.raiseError[IO, Unit].unlessA) >>
                  services.consensus.storage.setLastKeyAndArtifact((genesis.ordinal, signedGenesis).some) >>
                  services.consensus.manager.scheduleTriggerOnTime
              }
            }
          } >>
            services.cluster.createSession >>
            services.session.createSession >>
            storages.node.setNodeState(NodeState.Ready)
      }).asResource
    } yield ()
  }
}
