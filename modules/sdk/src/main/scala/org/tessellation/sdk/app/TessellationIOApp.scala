package org.tessellation.sdk.app

import java.security.KeyPair

import cats.effect._
import cats.effect.std.Random
import cats.syntax.applicative._
import cats.syntax.option._
import cats.syntax.show._

import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.cluster.ClusterId
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.cli.CliMethod
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.infrastructure.cluster.services.Session
import org.tessellation.sdk.infrastructure.logs.LoggerConfigurator
import org.tessellation.sdk.infrastructure.metrics.Metrics
import org.tessellation.sdk.infrastructure.whitelisting.{Loader => WhitelistingLoader}
import org.tessellation.sdk.modules._
import org.tessellation.sdk.resources.SdkResources
import org.tessellation.sdk.{sdkKryoRegistrar, _}
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

  /**
    * Command-line opts
    */
  def opts: Opts[A]

  type KryoRegistrationIdRange

  /**
    * Kryo registration is required for (de)serialization.
    */
  val kryoRegistrar: Map[Class[_], KryoRegistrationId[KryoRegistrationIdRange]]

  protected implicit val logger = Slf4jLogger.getLogger[IO]

  def run(method: A, sdk: SDK[IO]): Resource[IO, Unit]

  override final def main: Opts[IO[ExitCode]] =
    opts.map { method =>
      val cfg = method.sdkConfig

      val keyStore = method.keyStore
      val alias = method.alias
      val password = method.password

      val registrar: Map[Class[_], Int Refined Or[KryoRegistrationIdRange, SdkOrSharedOrKernelRegistrationIdRange]] =
        kryoRegistrar.union(sdkKryoRegistrar)

      LoggerConfigurator.configureLogger[IO](cfg.environment) >>
        logger.info(s"App environment: ${cfg.environment}") >>
        logger.info(s"App version: ${version.show}") >>
        Random.scalaUtilRandom[IO].flatMap { _random =>
          SecurityProvider.forAsync[IO].use { implicit _securityProvider =>
            loadKeyPair[IO](keyStore, alias, password).flatMap { _keyPair =>
              val selfId = PeerId.fromPublic(_keyPair.getPublic)
              KryoSerializer.forAsync[IO](registrar).use { implicit _kryoPool =>
                Metrics.forAsync[IO](Seq(("application", name))).use { implicit _metrics =>
                  SignallingRef.of[IO, Unit](()).flatMap { _restartSignal =>
                    def mkSDK =
                      for {
                        _whitelisting <- method.whitelistingPath
                          .fold(none[Set[PeerId]].pure[IO])(WhitelistingLoader.make[IO].load(_).map(_.some))
                          .asResource
                        _ <- _whitelisting
                          .map(_.size)
                          .fold(logger.info(s"Whitelisting disabled.")) { size =>
                            logger.info(s"Whitelisting enabled. Allowed nodes: $size")
                          }
                          .asResource
                        storages <- SdkStorages.make[IO](clusterId, cfg).asResource
                        res <- SdkResources.make[IO](cfg, _keyPair.getPrivate(), storages.session, selfId)
                        session = Session.make[IO](storages.session, storages.node, storages.cluster)
                        p2pClient = SdkP2PClient.make[IO](res.client, session)
                        queues <- SdkQueues.make[IO].asResource
                        services <- SdkServices
                          .make[IO](cfg, selfId, _keyPair, storages, queues, session, _whitelisting, _restartSignal)
                          .asResource

                        programs <- SdkPrograms
                          .make[IO](cfg, storages, services, p2pClient.cluster, p2pClient.sign, _whitelisting, selfId)
                          .asResource

                        sdk = new SDK[IO] {
                          val random = _random
                          val securityProvider = _securityProvider
                          val kryoPool = _kryoPool
                          val metrics = _metrics

                          val keyPair = _keyPair
                          val whitelisting = _whitelisting

                          val sdkResources = res
                          val sdkP2PClient = p2pClient
                          val sdkQueues = queues
                          val sdkStorages = storages
                          val sdkServices = services
                          val sdkPrograms = programs

                          def restartSignal = _restartSignal
                        }

                        _ <- logger.info(s"Self peerId: ${selfId.show}").asResource
                      } yield sdk

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
