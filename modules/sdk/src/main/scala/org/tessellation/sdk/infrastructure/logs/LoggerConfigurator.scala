package org.tessellation.sdk.infrastructure.logs

import cats.effect.{Resource, Sync}
import cats.syntax.flatMap._

import scala.io.Source

import org.tessellation.sdk.config.AppEnvironment
import org.tessellation.sdk.config.AppEnvironment.{Dev, Mainnet, Testnet}

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource

object LoggerConfigurator {

  def configureLogger[F[_]: Sync](environment: AppEnvironment): F[Unit] = {
    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    loggerContext.reset()
    val configurator = new JoranConfigurator()

    val logbackConfigSource = environment match {
      case Dev     => "logback.xml"
      case Testnet => "logback-testnet.xml"
      case Mainnet => "logback-mainnet.xml"
    }

    Resource
      .fromAutoCloseable(Sync[F].delay {
        Source.fromResource(logbackConfigSource).bufferedReader()
      })
      .use { reader =>
        Sync[F].delay(configurator.setContext(loggerContext)) >>
          Sync[F].delay(configurator.doConfigure(new InputSource(reader)))
      }
  }

}
