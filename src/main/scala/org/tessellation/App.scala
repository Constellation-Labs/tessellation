package org.tessellation

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.HttpRoutes
import org.tessellation.config.Config
import org.tessellation.consensus.DAGStateChannel
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.eth.ETHStateChannel
import org.tessellation.http.HttpServer
import org.tessellation.implicits.PipeArrow._
import org.tessellation.metrics.Metric._
import org.tessellation.metrics.{Metric, Metrics}
import org.tessellation.node.Node
import org.tessellation.snapshot.L0Pipeline

object App extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val logger = Slf4jLogger.getLogger[IO]

    val app = for {
      config <- Stream.eval(Config.load())
      metrics <- Metrics.init(config.ip)
      randomTransactionGenerator = RandomTransactionGenerator(config.ip, Some(config.generatorSrc))
      node = Node(config.ip, metrics, randomTransactionGenerator, ip = config.ip)

      _ <- Stream.eval(metrics.incrementMetricAsync[IO](Metric.LoadConfig.success))

      // L0
      l0 = new L0Pipeline(metrics).pipeline

      // L1 (DAG)
      dag <- DAGStateChannel.init(node, metrics)
      dagPipeline = (dag.l1 >>> l0)(dag.l1Input)

      // L1 (ETH)
      eth <- ETHStateChannel.init(config.ethereumBlockchainUrl)
      ethPipeline = (eth.l1 >>> l0)(eth.l1Input)

      // Adding L1 (DAG) and L1 (ETH) together
      pipelines = ethPipeline.merge(dagPipeline)
      routes = eth.routes <+> dag.routes

      httpServer = HttpServer(routes, HttpRoutes.empty, metrics)
      _ <- httpServer.run().merge(pipelines)
    } yield ()

    app.handleErrorWith(e => Stream.eval(logger.error(e)("ERROR!"))).compile.drain.as(ExitCode.Success)
  }
}
