package org.tessellation.sdk.app

import java.security.KeyPair

import cats.effect._
import cats.effect.std.Random
import cats.syntax.show._

import org.tessellation.cli.env.{KeyAlias, Password, StorePath}
import org.tessellation.ext.cats.effect._
import org.tessellation.ext.kryo._
import org.tessellation.keytool.KeyStoreUtils
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.peer.PeerId
import org.tessellation.sdk.cli.CliMethod
import org.tessellation.sdk.http.p2p.SdkP2PClient
import org.tessellation.sdk.infrastructure.cluster.services.Session
import org.tessellation.sdk.infrastructure.logs.LoggerConfigurator
import org.tessellation.sdk.modules._
import org.tessellation.sdk.resources.SdkResources
import org.tessellation.sdk.{sdkKryoRegistrar, _}
import org.tessellation.security.SecurityProvider

import com.monovore.decline.Opts
import com.monovore.decline.effect.CommandIOApp
import eu.timepit.refined.api.Refined
import eu.timepit.refined.boolean.Or
import fs2.Stream
import fs2.concurrent.SignallingRef
import org.typelevel.log4cats.slf4j.Slf4jLogger

abstract class TessellationIOApp[A <: CliMethod](
  name: String,
  header: String,
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
        Random.scalaUtilRandom[IO].flatMap { _random =>
          SecurityProvider.forAsync[IO].use { implicit _securityProvider =>
            loadKeyPair[IO](keyStore, alias, password).flatMap { _keyPair =>
              KryoSerializer.forAsync[IO](registrar).use { implicit _kryoPool =>
                val selfId = PeerId.fromPublic(_keyPair.getPublic)

                SignallingRef.of[IO, Unit](()).flatMap { _restartSignal =>
                  def startup: Resource[IO, Unit] =
                    (for {
                      storages <- SdkStorages.make[IO](cfg).asResource
                      res <- SdkResources.make[IO](cfg, _keyPair.getPrivate(), storages.session, selfId)
                      session = Session.make[IO](storages.session, storages.node, storages.cluster)
                      p2pClient = SdkP2PClient.make[IO](res.client, session)
                      queues <- SdkQueues.make[IO].asResource
                      services <- SdkServices
                        .make[IO](cfg, selfId, _keyPair, storages, queues, session, _restartSignal)
                        .asResource

                      programs <- SdkPrograms
                        .make[IO](cfg, storages, services, p2pClient.cluster, p2pClient.sign, selfId)
                        .asResource

                      sdk = new SDK[IO] {
                        val random = _random
                        val securityProvider = _securityProvider
                        val kryoPool = _kryoPool

                        val keyPair = _keyPair

                        val sdkResources = res
                        val sdkP2PClient = p2pClient
                        val sdkQueues = queues
                        val sdkStorages = storages
                        val sdkServices = services
                        val sdkPrograms = programs

                        def restartSignal = _restartSignal
                      }

                      _ <- logger.info(s"Self peerId: ${selfId.show}").asResource

                      _ <- run(method, sdk).handleErrorWith { (e: Throwable) =>
                        (logger.error(e)(s"Unhandled exception during runtime.") >> IO.raiseError[Unit](e)).asResource
                      }
                    } yield ())

                  _restartSignal.discrete.switchMap { _ =>
                    Stream.eval(startup.useForever)
                  }.compile.drain.as(ExitCode.Success)
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
