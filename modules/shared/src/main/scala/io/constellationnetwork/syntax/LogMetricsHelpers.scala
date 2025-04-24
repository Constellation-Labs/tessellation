package io.constellationnetwork.syntax

object LogMetricsHelpers {

  implicit class LoggableMap[K](map: Map[K, Any]) {
    def toLogString: String = map.map { case (k, v) => s"$k=$v" }.mkString(" ")
  }

}
