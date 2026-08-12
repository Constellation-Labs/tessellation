package io.constellationnetwork.node.shared.logger

import scala.jdk.CollectionConverters._

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.status.Status
import weaver.FunSuite

object LogbackConfigurationSuite extends FunSuite {

  test("production logback configuration resolves custom appenders") {
    val context = new LoggerContext()

    try
      Option(getClass.getClassLoader.getResource("logback.xml")) match {
        case None => failure("Unable to find production logback.xml")
        case Some(config) =>
          val configurator = new JoranConfigurator()
          configurator.setContext(context)
          configurator.doConfigure(config)

          val errors = context.getStatusManager.getCopyOfStatusList.asScala.filter(_.getLevel == Status.ERROR)

          if (errors.isEmpty) success
          else failure(errors.map(_.toString).mkString("Logback configuration errors:\n", "\n", ""))
      }
    finally context.stop()
  }
}
