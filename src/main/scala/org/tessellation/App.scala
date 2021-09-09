package org.tessellation

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.tessellation.aci.endpoint.StateChannelHandler
import org.tessellation.aci.{ClasspathScanner, RuntimeLoader}
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
import org.tessellation.utils.streamLiftK

object App extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val logger = Slf4jLogger.getLogger[IO].mapK(streamLiftK)

    val app = for {
      config <- Stream.eval(Config.load())
      metrics <- Metrics.init(config.ip)
      randomTransactionGenerator = RandomTransactionGenerator(config.ip, Some(config.generatorSrc))
      node = Node(config.ip, metrics, randomTransactionGenerator, ip = config.ip)

      _ <- Stream.eval(metrics.incrementMetricAsync[IO](Metric.LoadConfig.success))

      runtimeLoader = new RuntimeLoader[IO]()
      classpathScanner = new ClasspathScanner[IO](runtimeLoader)
      runtimeCache <- classpathScanner.scanClasspath

      // L0
      l0 = new L0Pipeline(metrics).pipeline

      // L1 (any)
      handler <- StateChannelHandler.init[IO](runtimeCache)
      handlerPipeline = (handler.l1 >>> l0)(handler.l1Input)

      // L1 (DAG)
      dag <- DAGStateChannel.init(node, metrics)
      dagPipeline = (dag.l1 >>> l0)(dag.l1Input)

      // L1 (ETH)
      eth <- ETHStateChannel.init(config.ethereumBlockchainUrl, config.ethereumLiquidityPoolAddress)
      ethPipeline = (eth.l1 >>> l0)(eth.l1Input)

      // Adding L1 (DAG) and L1 (ETH) together
      pipelines = ethPipeline.merge(dagPipeline).merge(handlerPipeline)
      routes = eth.routes <+> dag.routes <+> handler.routes

      httpServer = HttpServer(routes, handler.routes, metrics)
      _ <- httpServer.run().merge(pipelines)
    } yield ()

    app.handleErrorWith(e => logger.error(e)("ERROR!")).compile.drain.as(ExitCode.Success)
  }
}
