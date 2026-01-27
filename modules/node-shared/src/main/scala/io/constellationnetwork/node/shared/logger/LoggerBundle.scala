package io.constellationnetwork.node.shared.logger

/** Groups all loggers used by the application.
  */
trait LoggerBundle[F[_]] {
  def app: AppLogger[F]
  def consensus: ConsensusLogger[F]
}

object LoggerBundle {
  def apply[F[_]](appLogger: AppLogger[F], consensusLogger: ConsensusLogger[F]): LoggerBundle[F] =
    new LoggerBundle[F] {
      val app: AppLogger[F] = appLogger
      val consensus: ConsensusLogger[F] = consensusLogger
    }
}
