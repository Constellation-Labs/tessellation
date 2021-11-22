package org.tessellation.infrastructure.logs

import cats.effect.{Async, Resource}
import cats.syntax.flatMap._

import scala.io.Source

import org.tessellation.config.AppEnvironment.{Dev, Mainnet, Testnet}
import org.tessellation.config.types.AppConfig

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import org.slf4j.LoggerFactory
import org.xml.sax.InputSource

object LoggerConfigurator {

  def configureLogger[F[_]: Async](cfg: AppConfig): F[Unit] = {
    val loggerContext = LoggerFactory.getILoggerFactory.asInstanceOf[LoggerContext]
    loggerContext.reset()
    val configurator = new JoranConfigurator()

    val logbackConfigSource = cfg.environment match {
      case Dev     => "logback.xml"
      case Testnet => "logback-testnet.xml"
      case Mainnet => "logback-mainnet.xml"
    }

    Resource
      .fromAutoCloseable(Async[F].delay {
        Source.fromResource(logbackConfigSource).bufferedReader()
      })
      .use { reader =>
        Async[F].delay(configurator.setContext(loggerContext)) >>
          Async[F].delay(configurator.doConfigure(new InputSource(reader)))
      }
  }

}
