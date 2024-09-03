package io.constellationnetwork.node.shared.infrastructure.logs

import cats.effect.{Resource, Sync}
import cats.syntax.flatMap._

import scala.io.{Codec, Source}

import io.constellationnetwork.env.AppEnvironment
import io.constellationnetwork.env.AppEnvironment._

import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.{ClassicConstants, LoggerContext}
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource

object LoggerConfigurator {

  def configureLogger[F[_]: Sync](environment: AppEnvironment): F[Unit] = {
    val envDefaultConfigSource = environment match {
      case Dev            => "logback.xml"
      case Testnet        => "logback-testnet.xml"
      case Integrationnet => "logback-integrationnet.xml"
      case Mainnet        => "logback-mainnet.xml"
    }

    Resource.fromAutoCloseable {
      Sync[F].delay(System.getProperty(ClassicConstants.CONFIG_FILE_PROPERTY)).flatMap { configFileProperty =>
        if (configFileProperty != null)
          Sync[F].delay(Source.fromFile(configFileProperty)(Codec.UTF8).bufferedReader())
        else
          Sync[F].delay(Source.fromResource(envDefaultConfigSource).bufferedReader())
      }
    }.use { reader =>
      Sync[F].delay(LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]).flatMap { loggerContext =>
        val configurator = new JoranConfigurator()
        Sync[F].delay(loggerContext.reset()) >>
          Sync[F].delay(configurator.setContext(loggerContext)) >>
          Sync[F].delay(configurator.doConfigure(new InputSource(reader)))
      }
    }
  }

}
