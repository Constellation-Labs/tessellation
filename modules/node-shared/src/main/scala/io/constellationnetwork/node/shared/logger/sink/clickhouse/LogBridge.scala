package io.constellationnetwork.node.shared.logger.sink.clickhouse

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

/** A single WARN/ERROR logback event captured for forwarding to ClickHouse. */
case class BridgedLog(
  level: String,
  logger: String,
  message: String,
  thread: String,
  stack: Option[String]
)

object BridgedLog {
  implicit val encoder: Encoder[BridgedLog] = deriveEncoder
}

/** Process-wide hand-off between the synchronous logback world (ClickHouseLogbackAppender) and the cats-effect drain fiber (started in
  * ClickHouseLoggerBundle).
  *
  * Why a static singleton: a logback appender is instantiated by logback, not by the cats-effect runtime, so it cannot be handed the
  * IO-constructed ClickHouseSink. The appender only enqueues here; the drain fiber owns the actual ClickHouse write via the existing,
  * already-hardened sink.
  *
  * Bounded by `capacity` and dropping on overflow so the logging thread never blocks and the queue never leaks when no drain is running
  * (e.g. ClickHouse not configured, or pre-startup).
  */
object LogBridge {

  private val capacity = 16384
  private val queue = new ConcurrentLinkedQueue[BridgedLog]()
  private val size = new AtomicInteger(0)

  def offer(entry: BridgedLog): Unit =
    if (size.get() < capacity) {
      queue.offer(entry)
      size.incrementAndGet()
      ()
    } else ()

  def drain(max: Int): List[BridgedLog] = {
    val buf = List.newBuilder[BridgedLog]
    var taken = 0
    var continue = true
    while (continue && taken < max) {
      val e = queue.poll()
      if (e == null) continue = false
      else {
        size.decrementAndGet()
        buf += e
        taken += 1
      }
    }
    buf.result()
  }
}
