package io.constellationnetwork.node.shared.logger

/** Abstraction for where log entries are written. Implementations handle the actual persistence (ClickHouse, Slf4j, etc.)
  */
trait LogSink[F[_]] {
  def write(entry: LogEntry): F[Unit]
}
