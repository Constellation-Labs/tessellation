package org.tessellation

import cats.effect._
import cats.syntax.option._
import cats.syntax.semigroupk._
import cats.syntax.show._

import org.tessellation.cli.method.{Run, RunGenesis, RunValidator}
import org.tessellation.dag._
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.kryo._
import org.tessellation.http.p2p.P2PClient
import org.tessellation.infrastructure.db.Database
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
      name = "",
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

    Database.forAsync[IO](cfg.db).flatMap { implicit database =>
      for {
        _ <- IO.unit.asResource
        p2pClient = P2PClient.make[IO](sdkP2PClient, sdkResources.client, sdkServices.session)
        queues <- Queues.make[IO](sdkQueues).asResource
        storages <- Storages.make[IO](sdkStorages, cfg.snapshot).asResource
        services <- Services.make[IO](sdkServices, queues, storages, sdk.nodeId, keyPair, cfg).asResource
        programs = Programs.make[IO](sdkPrograms, storages, services, p2pClient)
        validators = Validators.make[IO](cfg.snapshot)
        healthChecks <- HealthChecks
          .make[IO](storages, services, programs, p2pClient, cfg.healthCheck, sdk.nodeId)
          .asResource

        _ <- services.stateChannelRunner.initializeKnownCells.asResource

        rumorHandler = RumorHandlers.make[IO](storages.cluster, healthChecks.ping).handlers <+>
          trustHandler(storages.trust) <+> services.consensus.handler

        _ <- Daemons
          .start(storages, services, programs, queues, healthChecks, validators, p2pClient, rumorHandler, nodeId, cfg)
          .asResource

        api = HttpApi.make[IO](storages, queues, services, programs, keyPair.getPrivate, cfg.environment, sdk.nodeId)
        _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
        _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
        _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

        _ <- (method match {
          case _: RunValidator =>
            storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)
          case m: RunGenesis =>
            storages.node.tryModifyState(
              NodeState.Initial,
              NodeState.LoadingGenesis,
              NodeState.GenesisReady
            ) {
              for {
                accounts <- GenesisLoader.make[IO].load(m.genesisPath)
                _ <- logger.info(s"Genesis accounts: ${accounts.show}")

                genesis = GlobalSnapshot.mkGenesis(accounts.map(a => (a.address, a.balance)).toMap)
                signedGenesis <- Signed.forAsyncKryo[IO, GlobalSnapshot](genesis, keyPair)

                _ <- storages.globalSnapshot.prepend(signedGenesis)
                _ <- services.consensus.storage.setLastKeyAndArtifact((genesis.ordinal, signedGenesis).some)
                _ <- services.cluster.createSession
                _ <- services.session.createSession
                _ <- storages.node.setNodeState(NodeState.Ready)
              } yield ()
            }
        }).asResource
      } yield ()
    }
  }
}
