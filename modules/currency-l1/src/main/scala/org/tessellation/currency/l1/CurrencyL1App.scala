package org.tessellation.currency.l1

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.semigroupk._

import scala.concurrent.duration._

import org.tessellation.BuildInfo
import org.tessellation.currency.dataApplication.{BaseDataApplicationL1Service, L1NodeContext}
import org.tessellation.currency.l1.cli.method
import org.tessellation.currency.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import org.tessellation.currency.l1.domain.snapshot.programs.CurrencySnapshotProcessor
import org.tessellation.currency.l1.http.p2p.P2PClient
import org.tessellation.currency.l1.modules._
import org.tessellation.currency.l1.node.L1NodeContext
import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.http.p2p.{P2PClient => DAGP2PClient}
import org.tessellation.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.tessellation.dag.l1.modules.{
  Daemons => DAGL1Daemons,
  HealthChecks => DAGL1HealthChecks,
  Queues => DAGL1Queues,
  Validators => DAGL1Validators
}
import org.tessellation.dag.l1.{DagL1KryoRegistrationIdRange, StateChannel, dagL1KryoRegistrar}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo.{KryoRegistrationId, MapRegistrationId}
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.SessionStarted
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.app.{SDK, TessellationIOApp, getMajorityPeerIds}
import org.tessellation.sdk.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.sdk.{SdkOrSharedOrKernelRegistrationIdRange, sdkKryoRegistrar}

import com.monovore.decline.Opts
import eu.timepit.refined.boolean.Or
import fs2.Stream

abstract class CurrencyL1App(
  name: String,
  header: String,
  clusterId: ClusterId,
  version: String
) extends TessellationIOApp[Run](
      name,
      header,
      clusterId,
      version = version
    ) {

  val opts: Opts[Run] = method.opts

  type KryoRegistrationIdRange = SdkOrSharedOrKernelRegistrationIdRange Or DagL1KryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    sdkKryoRegistrar.union(dagL1KryoRegistrar)

  def dataApplication: Option[BaseDataApplicationL1Service[IO]] = None

  def run(method: Run, sdk: SDK[IO]): Resource[IO, Unit] = {
    import sdk._

    val cfg = method.appConfig

    for {
      dagL1Queues <- DAGL1Queues.make[IO](sdkQueues).asResource
      queues <- Queues.make[IO](dagL1Queues).asResource
      storages <- Storages
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          sdkStorages,
          method.l0Peer,
          method.globalL0Peer,
          method.identifier
        )
        .asResource
      validators = DAGL1Validators
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          storages,
          seedlist
        )
      dagP2PClient = DAGP2PClient
        .make[IO](sdkP2PClient, sdkResources.client, currencyPathPrefix = "currency")
      p2pClient = P2PClient.make[IO](
        dagP2PClient,
        sdkResources.client
      )
      maybeMajorityPeerIds <- getMajorityPeerIds[IO](
        sdk.prioritySeedlist,
        method.sdkConfig.priorityPeerIds,
        cfg.environment
      ).asResource
      services = Services
        .make[IO](
          storages,
          storages.lastGlobalSnapshot,
          storages.globalL0Cluster,
          validators,
          sdkServices,
          p2pClient,
          cfg,
          dataApplication,
          maybeMajorityPeerIds
        )
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.make[IO]().asResource
      snapshotProcessor = CurrencySnapshotProcessor.make(
        method.identifier,
        storages.address,
        storages.block,
        storages.lastGlobalSnapshot,
        storages.lastSnapshot,
        storages.transaction,
        sdkServices.globalSnapshotContextFns,
        sdkServices.currencySnapshotContextFns,
        jsonBrotliBinarySerializer
      )
      programs = Programs
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          sdkPrograms,
          p2pClient,
          storages,
          snapshotProcessor
        )
      healthChecks <- DAGL1HealthChecks
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          storages,
          services,
          programs,
          dagP2PClient,
          sdkResources.client,
          sdkServices.session,
          cfg.healthCheck,
          sdk.nodeId
        )
        .asResource

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, healthChecks.ping, services.localHealthcheck, sdkStorages.forkInfo)
        .handlers <+>
        blockRumorHandler[IO](queues.peerBlock)

      _ <- DAGL1Daemons
        .start(storages, services, healthChecks)
        .asResource

      implicit0(nodeContext: L1NodeContext[IO]) = L1NodeContext.make[IO](storages.lastGlobalSnapshot, storages.lastSnapshot)

      api = HttpApi
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          services.dataApplication,
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
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          cfg,
          keyPair,
          dagP2PClient,
          programs,
          dagL1Queues,
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
        .awakeEvery[IO](10.seconds)
        .evalMap { _ =>
          storages.lastGlobalSnapshot.get.flatMap {
            case None =>
              storages.globalL0Cluster.getRandomPeer.flatMap(p => programs.globalL0PeerDiscovery.discoverFrom(p))
            case Some(latestSnapshot) =>
              programs.globalL0PeerDiscovery.discover(latestSnapshot.signed.proofs.map(_.id).map(PeerId._Id.reverseGet))
          }
        }
      _ <- services.dataApplication.map {
        DataApplication
          .run(
            storages.cluster,
            storages.l0Cluster,
            p2pClient.l0BlockOutputClient,
            p2pClient.consensusClient,
            services,
            queues,
            _,
            keyPair,
            nodeId,
            storages.lastGlobalSnapshot,
            storages.lastSnapshot
          )
          .merge(globalL0PeerDiscovery)
          .merge(stateChannel.globalSnapshotProcessing)
          .compile
          .drain
          .handleErrorWith { error =>
            logger.error(error)("An error occured during state channel runtime") >> error.raiseError[IO, Unit]
          }
      }.getOrElse(stateChannel.runtime.merge(globalL0PeerDiscovery).compile.drain).asResource
    } yield ()
  }
}
