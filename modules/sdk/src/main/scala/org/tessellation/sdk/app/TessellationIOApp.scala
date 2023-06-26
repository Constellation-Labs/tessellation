package org.tessellation.sdk.app

import java.security.KeyPair

import cats.effect._
import cats.effect.std.{Random, Supervisor}
import cats.syntax.either._
import cats.syntax.show._
import cats.syntax.traverse._

import org.tessellation.cli.env._
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk._
import org.tessellation.sdk.cli.CliMethod
import org.tessellation.sdk.domain.seedlist.SeedlistEntry
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.infrastructure.cluster.services.Session
import org.tessellation.sdk.infrastructure.logs.LoggerConfigurator
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.seedlist.{Loader => SeedlistLoader}
import org.tessellation.sdk.infrastructure.trust.TrustRatingCsvLoader
import org.tessellation.sdk.modules._
import org.tessellation.sdk.resources.SdkResources
import org.tessellation.security.SecurityProvider

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.boolean.Or
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class TessellationIOApp[A <: CliMethod](
  name: String,
  header: String,
  clusterId: ClusterId,
  helpFlag: Boolean = true,
  version: String = ""
) extends CommandIOApp(
      name,
      header,
      helpFlag,
      version
    ) {

  /** Command-line opts
    */
  def opts: Opts[A]

  type KryoRegistrationIdRange

  /** Kryo registration is required for (de)serialization.
    */
  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]]

  protected val logger = Slf4jLogger.getLogger[IO]

  def run(method: A, sdk: SDK[IO]): Resource[IO, Unit]

  override final def main: Opts[IO[ExitCode]] =
    opts.map { method =>
      val cfg = method.sdkConfig

      val keyStore = method.keyStore
      val alias = method.alias
      val password = method.password

      val registrar: Map[Class[_], Int Refined Or[KryoRegistrationIdRange, SdkOrSharedOrKernelRegistrationIdRange]] =
        kryoRegistrar.union(sdkKryoRegistrar)

      Random.scalaUtilRandom[IO].flatMap { _random =>
        SecurityProvider.forAsync[IO].use { implicit _securityProvider =>
          loadKeyPair[IO](keyStore, alias, password).flatMap { _keyPair =>
            val selfId = PeerId.fromPublic(_keyPair.getPublic)
            IO {
              Map(
                "application_name" -> name,
                "self_id" -> selfId.show,
                "external_ip" -> cfg.httpConfig.externalIp.show
              ).foreach {
                case (k, v) => System.setProperty(k, v)
              }
            } >>
              LoggerConfigurator.configureLogger[IO](cfg.environment) >>
              logger.info(s"App environment: ${cfg.environment}") >>
              logger.info(s"App version: ${version.show}") >>
              KryoSerializer.forAsync[IO](registrar).use { implicit _kryoPool =>
                Metrics.forAsync[IO](Seq(("application", name))).use { implicit _metrics =>
                  SignallingRef.of[IO, Unit](()).flatMap { _restartSignal =>
                    def mkSDK =
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
                          versionHash <- version.hash.liftTo[IO].asResource
                          _seedlist <- loadSeedlist("Seedlist", method.seedlistPath).asResource
                          _l0Seedlist <- loadSeedlist("l0Seedlist", method.l0SeedlistPath).asResource
                          _trustRatings <- method.trustRatingsPath.traverse(TrustRatingCsvLoader.make[IO].load).asResource
                          storages <- SdkStorages.make[IO](clusterId, cfg).asResource
                          res <- SdkResources.make[IO](cfg, _keyPair.getPrivate, storages.session, selfId)
                          session = Session.make[IO](storages.session, storages.node, storages.cluster)
                          p2pClient = SdkP2PClient.make[IO](res.client, session)
                          queues <- SdkQueues.make[IO].asResource
                          validators = SdkValidators.make[IO](
                            _l0Seedlist,
                            _seedlist,
                            method.stateChannelAllowanceLists
                          )
                          services <- SdkServices
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
                              method.stateChannelAllowanceLists
                            )
                            .asResource

                          programs <- SdkPrograms
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

                          sdk = new SDK[IO] {
                            val random = _random
                            val securityProvider = _securityProvider
                            val kryoPool = _kryoPool
                            val metrics = _metrics
                            val supervisor = _supervisor

                            val keyPair = _keyPair
                            val seedlist = _seedlist
                            val generation = _generation
                            val trustRatings = _trustRatings

                            val sdkResources = res
                            val sdkP2PClient = p2pClient
                            val sdkQueues = queues
                            val sdkStorages = storages
                            val sdkServices = services
                            val sdkPrograms = programs
                            val sdkValidators = validators

                            def restartSignal = _restartSignal
                          }
                        } yield sdk
                      }

                    def startup: Resource[IO, Unit] =
                      mkSDK.handleErrorWith { (e: Throwable) =>
                        (logger.error(e)(s"Unhandled exception during initialization.") >> IO
                          .raiseError[SDK[IO]](e)).asResource
                      }.flatMap { sdk =>
                        run(method, sdk).handleErrorWith { (e: Throwable) =>
                          (logger.error(e)(s"Unhandled exception during runtime.") >> IO.raiseError[Unit](e)).asResource
                        }
                      }

                    _restartSignal.discrete.switchMap { _ =>
                      Stream.eval(startup.useForever)
                    }.compile.drain.as(ExitCode.Success)
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
