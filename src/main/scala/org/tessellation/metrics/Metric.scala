package org.tessellation.metrics

object Metric extends Enumeration {
  type Metric = String
  val LoadConfig = "LoadConfig"
  val L1StartPipeline = "L1StartPipeline"
  val L1StartConsensus = "L1StartConsensus"
  val L1ParticipateInConsensus = "L1ParticipateInConsensus"
  val L1SemaphorePutToCellCache = "L1SemaphorePutToCellCache"

  implicit class MetricStatus(val metric: Metric) extends AnyVal {
    def success = s"${metric}_success"

    def failure = s"${metric}_failure"
  }
}
