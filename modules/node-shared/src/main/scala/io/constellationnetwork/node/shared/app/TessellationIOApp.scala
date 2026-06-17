package io.constellationnetwork.node.shared.app

import java.security.KeyPair

import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import scala.reflect.ClassTag

import io.constellationnetwork.domain.allowance_list.AllowanceListEntry
import io.constellationnetwork.domain.seedlist.SeedlistEntry
import io.constellationnetwork.env.AppEnvironment.Dev
import io.constellationnetwork.env.env._
import io.constellationnetwork.env.{AppEnvironment, JarSignature}
import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.keytool.KeyStoreUtils
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared._
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.http.p2p.SharedP2PClient
import io.constellationnetwork.node.shared.infrastructure.allowance_list.{Loader => AllowanceListLoader}
import io.constellationnetwork.node.shared.infrastructure.cluster.services.Session
import io.constellationnetwork.node.shared.infrastructure.logs.LoggerConfigurator
import io.constellationnetwork.node.shared.infrastructure.metrics.MetricsFactory
import io.constellationnetwork.node.shared.infrastructure.seedlist.{Loader => SeedlistLoader}
import io.constellationnetwork.node.shared.infrastructure.trust.TrustRatingCsvLoader
import io.constellationnetwork.node.shared.logger.{ClickHouseLoggerBundle, Slf4jLoggerBundle}
import io.constellationnetwork.node.shared.modules._
import io.constellationnetwork.node.shared.resources.SharedResources
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.{MetagraphVersion, TessellationVersion}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import eu.timepit.refined.pureconfig._
import eu.timepit.refined.refineV
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.enumeratum._
import pureconfig.{ConfigReader, ConfigSource}

abstract class TessellationIOApp[A <: CliMethod](
  name: String,
  header: String,
  clusterId: ClusterId,
  layer: Layer,
  helpFlag: Boolean = true,
  version: TessellationVersion = TessellationVersion.unsafeFrom("0.0.1"),
  metagraphVersion: MetagraphVersion = MetagraphVersion.unsafeFrom("0.0.1")
) extends CommandIOApp(
      name,
      header,
      helpFlag,
      version.version.value
    ) {

  /** Command-line opts
    */
  def opts: Opts[A]

  protected val configFiles: List[String]

  type KryoRegistrationIdRange

  /** Kryo registration is required for (de)serialization.
    */
  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]]

  protected val logger = Slf4jLogger.getLogger[IO]

  protected def loadConfigAs[C: ConfigReader: ClassTag]: IO[C] =
    configFiles
      .foldRight(ConfigSource.default) { (file, acc) =>
        ConfigSource.resources(file).withFallback(acc)
      }
      .loadF[IO, C]()

  override protected def computeWorkerThreadCount: Int =
    Math.max(2, Runtime.getRuntime().availableProcessors() - 1)

  def run(method: A, nodeShared: NodeShared[IO, A]): Resource[IO, Unit]

  override final def main: Opts[IO[ExitCode]] =
    opts.map { method =>
      val keyStore = method.keyStore
      val alias = method.alias
      val password = method.password

      val registrar: Map[Class[_], Int Refined Or[KryoRegistrationIdRange, NodeSharedOrSharedRegistrationIdRange]] =
        kryoRegistrar.union(nodeSharedKryoRegistrar)

      loadConfigAs[SharedConfigReader].flatMap { cfgR =>
        val cfg = method.nodeSharedConfig(cfgR)

        val _hashSelect = new HashSelect {
          def select(ordinal: SnapshotOrdinal): HashLogic =
            if (ordinal <= cfg.lastKryoHashOrdinal.getOrElse(cfg.environment, SnapshotOrdinal.MinValue)) KryoHash else JsonHash
        }

        implicit val _globalStateProofSelector: GlobalStateProofSelector =
          GlobalStateProofSelector(cfg.lastLegacyStateProofOrdinal.getOrElse(cfg.environment, SnapshotOrdinal.unsafeApply(Long.MaxValue)))

        implicit val _currencyStateProofSelector: CurrencyStateProofSelector =
          CurrencyStateProofSelector.instance

        Random.scalaUtilRandom[IO].flatMap { implicit _random =>
          SecurityProvider.forAsync[IO].use { implicit _securityProvider =>
            loadKeyPair[IO](keyStore, alias, password).flatMap { _keyPair =>
              val selfId = PeerId.fromPublic(_keyPair.getPublic)
              IO {
                Map(
                  "application_name" -> name,
                  "self_id" -> selfId.show,
                  "external_ip" -> cfg.http.externalIp.show
                ).foreach {
                  case (k, v) => System.setProperty(k, v)
                }
              } >>
                LoggerConfigurator.configureLogger[IO](cfg.environment) >>
                logger.info(s"App environment: ${cfg.environment}") >>
                logger.info(s"App version: ${version.show}") >>
                logger.info(s"App collateral: ${cfg.collateral.amount.show}") >>
                TessellationIOApp.logHeapCheck(logger) >>
                jarHash(cfg.environment).flatMap { jarHash =>
                  logger.info(s"Jar hash: ${jarHash.value}") >>
                    KryoSerializer.forAsync[IO](registrar).use { implicit _kryoPool =>
                      JsonSerializer.forAsync[IO].asResource.use { implicit _jsonSerializer =>
                        implicit val _hasherSelector = HasherSelector.forSync[IO](Hasher.forJson, Hasher.forKryo, _hashSelect)
                        MetricsFactory.make[IO](Seq(("application", name)), selfId, cfg.environment, cfg.clickHouseConfig).use {
                          implicit _metrics =>
                            SignallingRef.of[IO, Boolean](false).flatMap { _stopSignal =>
                              SignallingRef.of[IO, Option[A]](None).flatMap { _restartSignal =>
                                Ref.of[IO, Option[A]](None).flatMap { _restartMethodR =>
                                  def mkNodeShared =
                                    Supervisor[IO].flatMap { implicit _supervisor =>
                                      def loadSeedlist(name: String, seedlistPath: Option[SeedListPath]): IO[Option[Set[SeedlistEntry]]] =
                                        seedlistPath
                                          .traverse(SeedlistLoader.make[IO].load)
                                          .flatTap { seedlist =>
                                            seedlist
                                              .map(_.size)
                                              .fold(logger.info(s"$name disabled.")) { size =>
                                                logger.info(s"$name enabled. Allowed nodes: $size")
                                              }
                                          }

                                      def loadAllowanceList(name: String, allowanceListPath: Option[AllowanceListPath])
                                        : IO[Option[Set[AllowanceListEntry]]] =
                                        allowanceListPath
                                          .traverse(AllowanceListLoader.make[IO].load)
                                          .flatTap { allowanceList =>
                                            allowanceList
                                              .map(_.size)
                                              .fold(logger.info(s"$name disabled.")) { size =>
                                                logger.info(s"$name enabled. Allowed nodes: $size")
                                              }
                                          }

                                      for {
                                        _ <- logger.info(s"Self peerId: $selfId").asResource
                                        tokenIdentifierOpt: Option[Address] =
                                          sys.env.get("CL_L0_TOKEN_IDENTIFIER").flatMap { s =>
                                            refineV[DAGAddressRefined](s).toOption.map(Address(_))
                                          }
                                        _generation <- Generation.make[IO].asResource
                                        versionHash <- _hasherSelector
                                          .withCurrent(_.hash(version))
                                          .asResource
                                          .map(x => sys.env.get("CL_VERSION_HASH").map(Hash(_)).getOrElse(x))
                                        metagraphVersionHash <- _hasherSelector
                                          .withCurrent(_.hash(metagraphVersion))
                                          .asResource
                                          .map(x => sys.env.get("CL_METAGRAPH_VERSION_HASH").map(Hash(_)).getOrElse(x))
                                        _seedlist <- loadSeedlist("Seedlist", method.seedlistPath).asResource
                                        _l0Seedlist <- loadSeedlist("l0Seedlist", method.l0SeedlistPath).asResource
                                        _prioritySeedlist <- loadSeedlist("prioritySeedlist", method.prioritySeedlistPath).asResource
                                        _trustRatings <- method.trustRatingsPath.traverse(TrustRatingCsvLoader.make[IO].load).asResource
                                        maybeCustomAllowanceList <- loadAllowanceList("allowanceList", method.allowanceListPath).asResource
                                        storages <- _hasherSelector
                                          .withCurrent(implicit hasher => SharedStorages.make[IO](clusterId, cfg))
                                          .asResource
                                        res <- SharedResources.make[IO](cfg, _keyPair.getPrivate, storages.session, selfId)
                                        session = Session.make[IO](storages.session, storages.node, storages.cluster)
                                        p2pClient = SharedP2PClient.make[IO](res.client, session, cfg)
                                        queues <- SharedQueues.make[IO].asResource

                                        _loggerBundle <- {
                                          val useClickHouse = layer == DagL0 || layer == DagL1

                                          if (useClickHouse) {
                                            ClickHouseLoggerBundle
                                              .make[IO](selfId, cfg.environment, cfg.clickHouseConfig)
                                              .recoverWith {
                                                case ClickHouseLoggerBundle.NotConfigured =>
                                                  Resource.eval(logger.info("ClickHouse not configured. Using console logger.")) >>
                                                    Slf4jLoggerBundle.make[IO]
                                                case ClickHouseLoggerBundle.ConfigError(e) =>
                                                  Resource.eval(
                                                    logger.warn(s"ClickHouse config invalid: ${e.getMessage}. Using console logger.")
                                                  ) >>
                                                    Slf4jLoggerBundle.make[IO]
                                                case ClickHouseLoggerBundle.ConnectionError(e) =>
                                                  Resource.eval(
                                                    logger.warn(s"ClickHouse connection failed: ${e.getMessage}. Using console logger.")
                                                  ) >>
                                                    Slf4jLoggerBundle.make[IO]
                                              }
                                          } else {
                                            Slf4jLoggerBundle.make[IO]
                                          }
                                        }

                                        validators = _hasherSelector.withCurrent { implicit hasher =>
                                          SharedValidators.make[IO](
                                            cfg.environment,
                                            cfg.addresses,
                                            _l0Seedlist,
                                            _seedlist,
                                            method.stateChannelAllowanceLists,
                                            cfg.feeConfigs,
                                            cfg.snapshotSize.maxStateChannelSnapshotBinarySizeInBytes,
                                            Hasher.forKryo[IO],
                                            cfg.delegatedStaking,
                                            cfg.priceOracle,
                                            Some(storages.mptStore)
                                          )
                                        }
                                        services <- SharedServices
                                          .make[IO, A](
                                            cfg,
                                            selfId,
                                            _generation,
                                            _keyPair,
                                            storages,
                                            queues,
                                            session,
                                            p2pClient.node,
                                            validators,
                                            _seedlist,
                                            _restartSignal,
                                            versionHash,
                                            metagraphVersionHash,
                                            jarHash,
                                            cfg.collateral,
                                            method.stateChannelAllowanceLists,
                                            cfg.environment,
                                            Hasher.forKryo[IO],
                                            maybeCustomAllowanceList,
                                            tokenIdentifierOpt,
                                            _loggerBundle
                                          )
                                          .asResource

                                        programs <- SharedPrograms
                                          .make[IO, A](
                                            cfg,
                                            storages,
                                            services,
                                            p2pClient.cluster,
                                            p2pClient.sign,
                                            services.localHealthcheck,
                                            _seedlist,
                                            selfId,
                                            versionHash,
                                            metagraphVersionHash,
                                            maybeCustomAllowanceList,
                                            tokenIdentifierOpt
                                          )
                                          .asResource

                                        nodeShared = new NodeShared[IO, A] {
                                          val random = _random
                                          val securityProvider = _securityProvider
                                          val kryoPool = _kryoPool
                                          val jsonSerializer = _jsonSerializer
                                          val metrics = _metrics
                                          val supervisor = _supervisor
                                          val hasherSelector = _hasherSelector
                                          val globalStateProofSelector = _globalStateProofSelector
                                          val currencyStateProofSelector = _currencyStateProofSelector

                                          val keyPair = _keyPair
                                          val seedlist = _seedlist
                                          val generation = _generation
                                          val trustRatings = _trustRatings

                                          val sharedConfig = cfg

                                          val hashSelect = _hashSelect

                                          val sharedResources = res
                                          val sharedP2PClient = p2pClient
                                          val sharedQueues = queues
                                          val sharedStorages = storages
                                          val sharedServices = services
                                          val sharedPrograms = programs
                                          val sharedValidators = validators
                                          val prioritySeedlist = _prioritySeedlist
                                          val customAllowanceList = maybeCustomAllowanceList

                                          val loggerBundle = _loggerBundle

                                          def restartSignal = _restartSignal

                                          def stopSignal = _stopSignal
                                        }
                                      } yield nodeShared
                                    }

                                  def startup(method: A): Resource[IO, Unit] =
                                    mkNodeShared.handleErrorWith { (e: Throwable) =>
                                      (logger.error(e)(s"Unhandled exception during initialization.") >> IO
                                        .raiseError[NodeShared[IO, A]](e)).asResource
                                    }.flatMap { nodeShared =>
                                      run(method, nodeShared).handleErrorWith { (e: Throwable) =>
                                        (logger.error(e)(s"Unhandled exception during runtime.") >> IO.raiseError[Unit](e)).asResource
                                      }
                                    }

                                  _restartSignal.discrete.switchMap { restartMethod =>
                                    Stream.eval(startup(restartMethod.getOrElse(method)).useForever)
                                  }.interruptWhen {
                                    _stopSignal.discrete
                                  }.compile.drain.as(ExitCode.Success)
                                }
                              }
                            }
                        }
                      }
                    }
                }
            }
          }
        }
      }
    }

  private def jarHash(currentEnv: AppEnvironment): IO[Hash] =
    JarSignature
      .jarHash[IO]
      .onError(logger.error(_)(s"Error calculating jar's hash."))
      .recoverWith {
        case _ if currentEnv === Dev =>
          logger.warn(s"Using mock value for dev environment.") >> Hash.empty.pure[IO]
      }
      .map(x => sys.env.get("CL_JAR_HASH").map(Hash(_)).getOrElse(x))

  private def loadKeyPair[F[_]: Async: SecurityProvider](
    keyStore: StorePath,
    alias: KeyAlias,
    password: Password
  ): F[KeyPair] =
    KeyStoreUtils
      .readKeyPairFromStore[F](
        keyStore.value.toString,
        alias.value.value,
        password.value.value.toCharArray,
        password.value.value.toCharArray
      )

}

object TessellationIOApp {

  /** Recommended JVM max heap (megabytes) for a validator participating in global consensus.
    *
    * Matches the mainnet default documented in `docker/bin/docker-env-setup.sh` (-Xmx8192M = 8 GB).
    *
    * Under-provisioned heap causes long stop-the-world GC pauses -- observed on testnet: a validator with an undersized heap stalled for
    * 21.5 seconds in a single GC event, backing up the consensus command queue and indirectly draining neighbor peers that were waiting on
    * its HTTP responses. This constant is the threshold we log a warning at; it is not enforced (operators may still run under-provisioned
    * nodes, they just see a loud startup message).
    */
  private val RecommendedHeapMb: Long = 8192L

  /** Emit a startup log line describing the JVM max heap, and a loud error if it is below the recommended floor. Operators see this on
    * every startup regardless of launcher (docker, systemd, bare JVM, etc.) because it reads `Runtime.getRuntime.maxMemory()` directly.
    */
  def logHeapCheck(logger: org.typelevel.log4cats.SelfAwareStructuredLogger[IO]): IO[Unit] =
    IO(Runtime.getRuntime.maxMemory() / (1024L * 1024L)).flatMap { heapMaxMb =>
      if (heapMaxMb < RecommendedHeapMb)
        logger.error(
          s"JVM max heap = ${heapMaxMb}MB, below recommended ${RecommendedHeapMb}MB. " +
            s"Undersized heap causes long GC pauses which stall consensus and degrade cluster " +
            s"throughput. Set JVM max heap to at least ${RecommendedHeapMb}m via your launcher " +
            s"(e.g. -Xmx${RecommendedHeapMb}m, JAVA_OPTS, or jvm.options)."
        )
      else
        logger.info(s"JVM max heap = ${heapMaxMb}MB (recommended >= ${RecommendedHeapMb}MB).")
    }

}
