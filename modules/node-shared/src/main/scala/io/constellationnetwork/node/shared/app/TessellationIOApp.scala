package io.constellationnetwork.node.shared.app

import java.security.KeyPair

import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.env.JarSignature
import io.constellationnetwork.env.env._
import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.ext.kryo._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.keytool.KeyStoreUtils
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared._
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.config.types._
import io.constellationnetwork.node.shared.domain.seedlist.SeedlistEntry
import io.constellationnetwork.node.shared.ext.pureconfig._
import io.constellationnetwork.node.shared.http.p2p.SharedP2PClient
import io.constellationnetwork.node.shared.infrastructure.cluster.services.Session
import io.constellationnetwork.node.shared.infrastructure.logs.LoggerConfigurator
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.node.shared.infrastructure.seedlist.{Loader => SeedlistLoader}
import io.constellationnetwork.node.shared.infrastructure.trust.TrustRatingCsvLoader
import io.constellationnetwork.node.shared.modules._
import io.constellationnetwork.node.shared.resources.SharedResources
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.cluster.ClusterId
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.semver.TessellationVersion
import io.constellationnetwork.security._

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import eu.timepit.refined.pureconfig._
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.slf4j.Slf4jLogger
import pureconfig.ConfigSource
import pureconfig.generic.auto._
import pureconfig.module.catseffect.syntax._
import pureconfig.module.enumeratum._

abstract class TessellationIOApp[A <: CliMethod](
  name: String,
  header: String,
  clusterId: ClusterId,
  helpFlag: Boolean = true,
  version: TessellationVersion = TessellationVersion.unsafeFrom("0.0.1")
) extends CommandIOApp(
      name,
      header,
      helpFlag,
      version.version.value
    ) {

  /** Command-line opts
    */
  def opts: Opts[A]

  type KryoRegistrationIdRange

  /** Kryo registration is required for (de)serialization.
    */
  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]]

  protected val logger = Slf4jLogger.getLogger[IO]

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

      ConfigSource.default.loadF[IO, SharedConfigReader]().flatMap { cfgR =>
        val cfg = method.nodeSharedConfig(cfgR)

        val _hashSelect = new HashSelect {
          def select(ordinal: SnapshotOrdinal): HashLogic =
            if (ordinal <= cfg.lastKryoHashOrdinal.getOrElse(cfg.environment, SnapshotOrdinal.MinValue)) KryoHash else JsonHash
        }

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
                JarSignature.jarHash[IO].flatMap { jarHash =>
                  logger.info(s"Jar hash: ${jarHash.value}") >>
                    KryoSerializer.forAsync[IO](registrar).use { implicit _kryoPool =>
                      JsonSerializer.forSync[IO].asResource.use { implicit _jsonSerializer =>
                        implicit val _hasherSelector = HasherSelector.forSync[IO](Hasher.forJson, Hasher.forKryo, _hashSelect)
                        Metrics.forAsync[IO](Seq(("application", name))).use { implicit _metrics =>
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

                                    for {
                                      _ <- logger.info(s"Self peerId: $selfId").asResource
                                      _generation <- Generation.make[IO].asResource
                                      versionHash <- _hasherSelector.withCurrent(_.hash(version)).asResource
                                      _seedlist <- loadSeedlist("Seedlist", method.seedlistPath).asResource
                                      _l0Seedlist <- loadSeedlist("l0Seedlist", method.l0SeedlistPath).asResource
                                      _prioritySeedlist <- loadSeedlist("prioritySeedlist", method.prioritySeedlistPath).asResource
                                      _trustRatings <- method.trustRatingsPath.traverse(TrustRatingCsvLoader.make[IO].load).asResource
                                      storages <- SharedStorages.make[IO](clusterId, cfg).asResource
                                      res <- SharedResources.make[IO](cfg, _keyPair.getPrivate, storages.session, selfId)
                                      session = Session.make[IO](storages.session, storages.node, storages.cluster)
                                      p2pClient = SharedP2PClient.make[IO](res.client, session)
                                      queues <- SharedQueues.make[IO].asResource
                                      validators = _hasherSelector.withCurrent { implicit hasher =>
                                        SharedValidators.make[IO](
                                          cfg.addresses,
                                          _l0Seedlist,
                                          _seedlist,
                                          method.stateChannelAllowanceLists,
                                          cfg.feeConfigs,
                                          cfg.snapshotSize.maxStateChannelSnapshotBinarySizeInBytes,
                                          Hasher.forKryo[IO]
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
                                          jarHash,
                                          cfg.collateral,
                                          method.stateChannelAllowanceLists,
                                          cfg.environment,
                                          Hasher.forKryo[IO]
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
                                          versionHash
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
