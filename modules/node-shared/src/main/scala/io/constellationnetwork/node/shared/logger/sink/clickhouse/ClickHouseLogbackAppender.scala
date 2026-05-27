package io.constellationnetwork.node.shared.logger.sink.clickhouse

import ch.qos.logback.classic.spi.{ILoggingEvent, IThrowableProxy, ThrowableProxyUtil}
import ch.qos.logback.core.AppenderBase

/** Logback appender that forwards events into [[LogBridge]] for shipping to ClickHouse.
  *
  * The whole codebase logs diagnostics (GlobalArtifactMismatch, stalls, round abandonment) through raw log4cats/slf4j, which only reaches
  * the console. Attaching this appender to the root logger (gated by a ThresholdFilter at WARN in logback.xml) captures every WARN/ERROR —
  * including third-party libraries — without touching any call site.
  *
  * `append` only enqueues and must never block or throw: a failure on the logging path must not disturb consensus. The actual ClickHouse
  * write happens off-thread in the drain fiber.
  */
class ClickHouseLogbackAppender extends AppenderBase[ILoggingEvent] {

  override def append(event: ILoggingEvent): Unit =
    try
      LogBridge.offer(
        BridgedLog(
          level = event.getLevel.toString,
          logger = event.getLoggerName,
          message = event.getFormattedMessage,
          thread = event.getThreadName,
          stack = stackOf(event.getThrowableProxy)
        )
      )
    catch { case _: Throwable => () }

  private def stackOf(proxy: IThrowableProxy): Option[String] =
    Option(proxy).map(ThrowableProxyUtil.asString)
}
