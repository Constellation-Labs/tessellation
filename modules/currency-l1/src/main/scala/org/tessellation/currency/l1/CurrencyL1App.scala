package org.tessellation.currency.l1

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.option._
import cats.syntax.semigroupk._
import cats.syntax.traverse._

import scala.concurrent.duration._

import org.tessellation.currency.dataApplication.{BaseDataApplicationL1Service, L1NodeContext}
import org.tessellation.currency.l1.cli.method
import org.tessellation.currency.l1.cli.method.{Run, RunInitialValidator, RunValidator}
import org.tessellation.currency.l1.domain.snapshot.programs.CurrencySnapshotProcessor
import org.tessellation.currency.l1.http.p2p.P2PClient
import org.tessellation.currency.l1.modules._
import org.tessellation.currency.l1.node.L1NodeContext
import org.tessellation.currency.schema.currency._
import org.tessellation.dag.l1.config.types._
import org.tessellation.dag.l1.domain.transaction.CustomContextualTransactionValidator
import org.tessellation.dag.l1.http.p2p.{P2PClient => DAGP2PClient}
import org.tessellation.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import org.tessellation.dag.l1.modules.{Daemons => DAGL1Daemons, Queues => DAGL1Queues, Validators => DAGL1Validators}
import org.tessellation.dag.l1.{DagL1KryoRegistrationIdRange, StateChannel, dagL1KryoRegistrar}
import org.tessellation.ext.cats.effect.ResourceIO
import org.tessellation.ext.kryo.{KryoRegistrationId, MapRegistrationId}
import org.tessellation.json.JsonBrotliBinarySerializer
import org.tessellation.node.shared.app.{NodeShared, TessellationIOApp, getMajorityPeerIds}
import org.tessellation.node.shared.ext.pureconfig._
import org.tessellation.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import org.tessellation.node.shared.resources.MkHttpServer
import org.tessellation.node.shared.resources.MkHttpServer.ServerName
import org.tessellation.node.shared.{NodeSharedOrSharedRegistrationIdRange, nodeSharedKryoRegistrar}
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.node.NodeState.SessionStarted
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.semver.{MetagraphVersion, TessellationVersion}

import com.monovore.decline.Opts
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import eu.timepit.refined.pureconfig._
import fs2.Stream
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.enumeratum._

trait OverridableL1 extends TessellationIOApp[Run] {
  def dataApplication: Option[Resource[IO, BaseDataApplicationL1Service[IO]]] = None
  def transactionValidator: Option[CustomContextualTransactionValidator] = None
}

abstract class CurrencyL1App(
  name: String,
  header: String,
  clusterId: ClusterId,
  tessellationVersion: TessellationVersion,
  metagraphVersion: MetagraphVersion
) extends TessellationIOApp[Run](
      name,
      header,
      clusterId,
      version = tessellationVersion
    )
    with OverridableL1 {

  val opts: Opts[Run] = method.opts

  type KryoRegistrationIdRange = NodeSharedOrSharedRegistrationIdRange Or DagL1KryoRegistrationIdRange

  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]] =
    nodeSharedKryoRegistrar.union(dagL1KryoRegistrar)

  def run(method: Run, nodeShared: NodeShared[IO]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- ConfigSource.default.loadF[IO, AppConfigReader]().asResource
      cfg = method.appConfig(cfgR, sharedConfig)

      dagL1Queues <- DAGL1Queues.make[IO](sharedQueues).asResource
      queues <- Queues.make[IO](dagL1Queues).asResource
      validators = DAGL1Validators
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          seedlist,
          cfg.transactionLimit,
          transactionValidator
        )
      storages <- Storages
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          sharedStorages,
          method.l0Peer,
          method.globalL0Peer,
          method.identifier,
          validators.transactionContextual
        )
        .asResource
      dagP2PClient = DAGP2PClient
        .make[IO](sharedP2PClient, sharedResources.client, currencyPathPrefix = "currency")
      p2pClient = P2PClient.make[IO](
        dagP2PClient,
        sharedResources.client
      )
      maybeMajorityPeerIds <- getMajorityPeerIds[IO](
        nodeShared.prioritySeedlist,
        cfg.priorityPeerIds,
        cfg.environment
      ).asResource
      dataApplicationService <- dataApplication.sequence
      services = Services
        .make[IO](
          storages,
          storages.lastGlobalSnapshot,
          storages.globalL0Cluster,
          validators,
          sharedServices,
          p2pClient,
          cfg,
          dataApplicationService,
          maybeMajorityPeerIds,
          hashSelect
        )
      jsonBrotliBinarySerializer <- JsonBrotliBinarySerializer.forSync[IO].asResource
      snapshotProcessor = CurrencySnapshotProcessor.make(
        method.identifier,
        storages.address,
        storages.block,
        storages.lastGlobalSnapshot,
        storages.lastSnapshot,
        storages.transaction,
        sharedServices.globalSnapshotContextFns,
        sharedServices.currencySnapshotContextFns,
        jsonBrotliBinarySerializer,
        cfg.transactionLimit
      )
      programs = Programs
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
          sharedPrograms,
          p2pClient,
          storages,
          snapshotProcessor
        )

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        blockRumorHandler[IO](queues.peerBlock)

      _ <- DAGL1Daemons
        .start(storages, services)
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
          nodeShared.nodeId,
          tessellationVersion,
          cfg.http,
          metagraphVersion.some
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
            nodeId
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
