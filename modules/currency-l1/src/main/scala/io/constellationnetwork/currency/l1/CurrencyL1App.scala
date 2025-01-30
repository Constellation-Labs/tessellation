package io.constellationnetwork.currency.l1

import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.option._
import cats.syntax.semigroupk._
import cats.syntax.traverse._

import scala.concurrent.duration._

import io.constellationnetwork.currency.dataApplication.{BaseDataApplicationL1Service, L1NodeContext}
import io.constellationnetwork.currency.l1.cli.method
import io.constellationnetwork.currency.l1.cli.method._
import io.constellationnetwork.currency.l1.domain.snapshot.programs.CurrencySnapshotProcessor
import io.constellationnetwork.currency.l1.http.p2p.P2PClient
import io.constellationnetwork.currency.l1.modules._
import io.constellationnetwork.currency.l1.node.L1NodeContext
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.dag.l1.config.types._
import io.constellationnetwork.dag.l1.domain.transaction.{CustomContextualTransactionValidator, TransactionFeeEstimator}
import io.constellationnetwork.dag.l1.http.p2p.{P2PClient => DAGP2PClient}
import io.constellationnetwork.dag.l1.infrastructure.block.rumor.handler.blockRumorHandler
import io.constellationnetwork.dag.l1.infrastructure.swap.rumor.handler.allowSpendBlockRumorHandler
import io.constellationnetwork.dag.l1.infrastructure.tokenlock.rumor.handler.tokenLockBlockRumorHandler
import io.constellationnetwork.dag.l1.modules.{Daemons => DAGL1Daemons, Queues => DAGL1Queues, Validators => DAGL1Validators}
import io.constellationnetwork.dag.l1.{DagL1KryoRegistrationIdRange, StateChannel, dagL1KryoRegistrar}
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.kryo.{KryoRegistrationId, MapRegistrationId}
import io.constellationnetwork.json.JsonBrotliBinarySerializer
import io.constellationnetwork.node.shared.app.{NodeShared, TessellationIOApp, getMajorityPeerIds}
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.infrastructure.gossip.{GossipDaemon, RumorHandlers}
import io.constellationnetwork.node.shared.resources.MkHttpServer
import io.constellationnetwork.node.shared.resources.MkHttpServer.ServerName
import io.constellationnetwork.node.shared.{NodeSharedOrSharedRegistrationIdRange, nodeSharedKryoRegistrar}
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.node.NodeState.SessionStarted
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.security.Hasher

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
  def transactionFeeEstimator: Option[TransactionFeeEstimator[IO]] = None
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

  val networkStateAfterJoining: NodeState = NodeState.Ready

  def run(method: Run, nodeShared: NodeShared[IO, Run]): Resource[IO, Unit] = {
    import nodeShared._

    for {
      cfgR <- ConfigSource
        .resources("currency-l1.conf")
        .withFallback(ConfigSource.resources("dag-l1.conf"))
        .withFallback(ConfigSource.default)
        .loadF[IO, AppConfigReader]()
        .asResource
      cfg = method.appConfig(cfgR, sharedConfig)

      dagL1Queues <- DAGL1Queues.make[IO](sharedQueues).asResource
      queues <- Queues.make[IO](dagL1Queues).asResource
      txHasher = Hasher.forKryo[IO]
      validators = hasherSelector.withCurrent { implicit hasher =>
        DAGL1Validators
          .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
            cfg.shared,
            seedlist,
            cfg.transactionLimit,
            transactionValidator,
            txHasher,
            CurrencyId(method.identifier).some
          )
      }
      storages <- hasherSelector.withCurrent { implicit hasher =>
        Storages
          .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo](
            sharedStorages,
            method.l0Peer,
            method.globalL0Peer,
            method.identifier,
            validators.transactionContextual,
            validators.allowSpendContextual,
            validators.tokenLockContextual
          )
      }.asResource
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
        .make[IO, Run](
          storages,
          storages.lastGlobalSnapshot,
          storages.globalL0Cluster,
          validators,
          sharedServices,
          p2pClient,
          cfg,
          dataApplicationService,
          transactionFeeEstimator,
          maybeMajorityPeerIds,
          Hasher.forKryo[IO]
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
        cfg.transactionLimit,
        Hasher.forKryo[IO]
      )
      programs = Programs
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, Run](
          sharedPrograms,
          p2pClient,
          storages,
          snapshotProcessor
        )

      rumorHandler = RumorHandlers
        .make[IO](storages.cluster, services.localHealthcheck, sharedStorages.forkInfo)
        .handlers <+>
        blockRumorHandler[IO](queues.peerBlock) <+>
        allowSpendBlockRumorHandler[IO](queues.allowSpendBlocks) <+>
        tokenLockBlockRumorHandler[IO](queues.tokenLocksBlocks)

      _ <- DAGL1Daemons
        .start(storages, services)
        .asResource

      implicit0(nodeContext: L1NodeContext[IO]) = L1NodeContext.make[IO](storages.lastGlobalSnapshot, storages.lastSnapshot)

      api = HttpApi
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, Run](
          services.dataApplication,
          storages,
          queues,
          keyPair.getPrivate,
          services,
          programs,
          nodeShared.nodeId,
          tessellationVersion,
          cfg.http,
          metagraphVersion.some,
          txHasher
        )
      _ <- MkHttpServer[IO].newEmber(ServerName("public"), cfg.http.publicHttp, api.publicApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("p2p"), cfg.http.p2pHttp, api.p2pApp)
      _ <- MkHttpServer[IO].newEmber(ServerName("cli"), cfg.http.cliHttp, api.cliApp)

      stateChannel <- StateChannel
        .make[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotInfo, Run](
          cfg,
          keyPair,
          dagP2PClient,
          programs,
          dagL1Queues,
          nodeId,
          services,
          storages,
          validators,
          txHasher
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
              storages.node.tryModifyState(SessionStarted, NodeState.Ready) >>
              services.restart.setClusterLeaveRestartMethod(
                RunValidator(
                  cfg.keyStore,
                  cfg.alias,
                  cfg.password,
                  cfg.environment,
                  cfg.httpConfig,
                  cfg.l0Peer,
                  cfg.globalL0Peer,
                  cfg.identifier,
                  cfg.seedlistPath,
                  cfg.collateralAmount,
                  cfg.trustRatingsPath,
                  cfg.prioritySeedlistPath
                )
              )

          case cfg: RunValidator =>
            gossipDaemon.startAsRegularValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin)

          case cfg: RunValidatorWithJoinAttempt =>
            gossipDaemon.startAsRegularValidator >>
              programs.l0PeerDiscovery.discoverFrom(cfg.l0Peer) >>
              programs.globalL0PeerDiscovery.discoverFrom(cfg.globalL0Peer) >>
              storages.node.tryModifyState(NodeState.Initial, NodeState.ReadyToJoin) >>
              programs.joining.joinOneOf(cfg.majorityForkPeerIds)
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
      _ <- hasherSelector.withCurrent { implicit hasher =>
        services.dataApplication.map { da =>
          DataApplication
            .run(
              cfg.dataConsensus,
              storages.cluster,
              storages.l0Cluster,
              storages.lastGlobalSnapshot,
              storages.lastSnapshot,
              storages.node,
              p2pClient.l0BlockOutputClient,
              p2pClient.consensusClient,
              services,
              queues,
              da,
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
        }.getOrElse {
          Swap
            .run(
              cfg.swap,
              storages.cluster,
              storages.l0Cluster,
              storages.lastGlobalSnapshot,
              storages.node,
              p2pClient.l0BlockOutputClient,
              p2pClient.swapConsensusClient,
              services,
              storages.allowSpend,
              storages.allowSpendBlock,
              queues,
              validators.allowSpend,
              keyPair,
              nodeId
            )
            .merge {
              TokenLock.run(
                cfg.tokenLock,
                storages.cluster,
                storages.l0Cluster,
                storages.lastGlobalSnapshot,
                storages.node,
                p2pClient.l0BlockOutputClient,
                p2pClient.tokenLockConsensusClient,
                services,
                storages.tokenLock,
                storages.tokenLockBlock,
                queues,
                validators.tokenLock,
                keyPair,
                nodeId
              )
            }
            .merge(stateChannel.runtime)
            .merge(globalL0PeerDiscovery)
            .compile
            .drain
        }.asResource
      }
    } yield ()
  }
}
