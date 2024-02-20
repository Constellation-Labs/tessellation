package org.tessellation.node.shared.app

import java.security.KeyPair

import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.syntax.all._

import org.tessellation.env.env._
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.kryo._
import org.tessellation.json.JsonSerializer
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.kryo.KryoSerializer
import org.tessellation.node.shared._
import org.tessellation.node.shared.cli.CliMethod
import org.tessellation.node.shared.config.types._
import org.tessellation.node.shared.domain.seedlist.SeedlistEntry
import org.tessellation.node.shared.ext.pureconfig._
import org.tessellation.node.shared.http.p2p.SharedP2PClient
import org.tessellation.node.shared.infrastructure.cluster.services.Session
import org.tessellation.node.shared.infrastructure.logs.LoggerConfigurator
import org.tessellation.node.shared.infrastructure.metrics.Metrics
import org.tessellation.node.shared.infrastructure.seedlist.{Loader => SeedlistLoader}
import org.tessellation.node.shared.infrastructure.trust.TrustRatingCsvLoader
import org.tessellation.node.shared.modules._
import org.tessellation.node.shared.resources.SharedResources
import org.tessellation.schema.SnapshotOrdinal
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.semver.TessellationVersion
import org.tessellation.security._

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

  def run(method: A, nodeShared: NodeShared[IO]): Resource[IO, Unit]

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

        Random.scalaUtilRandom[IO].flatMap { _random =>
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
                KryoSerializer.forAsync[IO](registrar).use { implicit _kryoPool =>
                  JsonSerializer.forSync[IO].asResource.use { implicit _jsonSerializer =>
                    implicit val _hasher = Hasher.forSync[IO](_hashSelect)
                    Metrics.forAsync[IO](Seq(("application", name))).use { implicit _metrics =>
                      SignallingRef.of[IO, Boolean](false).flatMap { _stopSignal =>
                        SignallingRef.of[IO, Unit](()).flatMap { _restartSignal =>
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
                                versionHash <- _hasher.hash(version).asResource
                                _seedlist <- loadSeedlist("Seedlist", method.seedlistPath).asResource
                                _l0Seedlist <- loadSeedlist("l0Seedlist", method.l0SeedlistPath).asResource
                                _prioritySeedlist <- loadSeedlist("prioritySeedlist", method.prioritySeedlistPath).asResource
                                _trustRatings <- method.trustRatingsPath.traverse(TrustRatingCsvLoader.make[IO].load).asResource
                                storages <- SharedStorages.make[IO](clusterId, cfg).asResource
                                res <- SharedResources.make[IO](cfg, _keyPair.getPrivate, storages.session, selfId)
                                session = Session.make[IO](storages.session, storages.node, storages.cluster)
                                p2pClient = SharedP2PClient.make[IO](res.client, session)
                                queues <- SharedQueues.make[IO].asResource
                                validators = SharedValidators.make[IO](
                                  _l0Seedlist,
                                  _seedlist,
                                  method.stateChannelAllowanceLists,
                                  cfg.snapshotSize.maxStateChannelSnapshotBinarySizeInBytes
                                )
                                services <- SharedServices
                                  .make[IO](
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
                                    cfg.collateral,
                                    method.stateChannelAllowanceLists,
                                    cfg.environment,
                                    _hashSelect
                                  )
                                  .asResource

                                programs <- SharedPrograms
                                  .make[IO](
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

                                nodeShared = new NodeShared[IO] {
                                  val random = _random
                                  val securityProvider = _securityProvider
                                  val kryoPool = _kryoPool
                                  val jsonSerializer = _jsonSerializer
                                  val metrics = _metrics
                                  val supervisor = _supervisor
                                  val hasher = _hasher

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

                          def startup: Resource[IO, Unit] =
                            mkNodeShared.handleErrorWith { (e: Throwable) =>
                              (logger.error(e)(s"Unhandled exception during initialization.") >> IO
                                .raiseError[NodeShared[IO]](e)).asResource
                            }.flatMap { nodeShared =>
                              run(method, nodeShared).handleErrorWith { (e: Throwable) =>
                                (logger.error(e)(s"Unhandled exception during runtime.") >> IO.raiseError[Unit](e)).asResource
                              }
                            }

                          _restartSignal.discrete.switchMap { _ =>
                            Stream
                              .eval(startup.useForever)
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
