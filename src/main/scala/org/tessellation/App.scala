package org.tessellation

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.HttpRoutes
import org.http4s.client.blaze.BlazeClientBuilder
import org.tessellation.aci.endpoint.StateChannelHandler
import org.tessellation.aci.{ClasspathScanner, RuntimeLoader}
import org.tessellation.config.Config
import org.tessellation.consensus.DAGStateChannel
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.eth.ETHStateChannel
import org.tessellation.http.{HttpClient, HttpServer}
import org.tessellation.implicits.PipeArrow._
import org.tessellation.majority.SnapshotStorage
import org.tessellation.majority.SnapshotStorage.MajorityHeight
import org.tessellation.metrics.Metric._
import org.tessellation.metrics.{Metric, Metrics}
import org.tessellation.node.Node
import org.tessellation.snapshot.L0Pipeline
import org.tessellation.utils.streamLiftK

import scala.concurrent.duration.DurationInt

object App extends IOApp {

  override def run(args: List[String]): IO[ExitCode] = {
    val logger = Slf4jLogger.getLogger[IO].mapK(streamLiftK)

    val app = for {
      config <- Stream.eval(Config.load())
      metrics <- Metrics.init(config.ip)
      randomTransactionGenerator = RandomTransactionGenerator(config.ip, Some(config.generatorSrc))
      node = Node(config.ip, metrics, randomTransactionGenerator, ip = config.ip)
      snapshotStorage = SnapshotStorage()
      // TODO: temporary
      _ <- Stream.eval(snapshotStorage.activeBetweenHeights.modify(_ => (MajorityHeight(0L.some, none).some, ())))
      blazeClient <- Stream.resource {
        BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global) // TODO: Use unbounded
          .withConnectTimeout(30.seconds)
          .withMaxTotalConnections(1024)
          .withMaxWaitQueueLimit(512)
          .resource
      }
      httpClient = HttpClient(node, blazeClient)

      _ <- Stream.eval(metrics.incrementMetricAsync[IO](Metric.LoadConfig.success))

      runtimeLoader = new RuntimeLoader[IO]()
      classpathScanner = new ClasspathScanner[IO](runtimeLoader)
      runtimeCache <- classpathScanner.scanClasspath

      // L0
      l0 <- L0Pipeline.init(metrics, node, snapshotStorage, httpClient)

      // L1 (any)
      handler <- StateChannelHandler.init[IO](runtimeCache)
      handlerPipeline = handler.l1(handler.l1Input)

      // L1 (DAG)
      dag = DAGStateChannel(node, snapshotStorage, httpClient, metrics)
      dagPipeline = dag.l1(dag.l1Input)

      // L1 (ETH)
      eth <- ETHStateChannel.init(config.ethereumBlockchainUrl, config.ethereumLiquidityPoolAddress)
      ethPipeline = eth.l1(eth.l1Input)

      // Adding L1 (DAG) and L1 (ETH) together
      l1Pipelines = ethPipeline merge dagPipeline merge handlerPipeline

      pipelines = l0.majorityPipeline((l1Pipelines through l0.blockToProposalPipeline through l0.sendProposalPipeline) merge l0.l0PeerProposalInput)
      routes = eth.routes <+> dag.routes <+> handler.routes <+> l0.routes

      httpServer = HttpServer(routes, handler.routes, metrics)
      _ <- httpServer.run().merge(pipelines)
    } yield ()

    app.handleErrorWith(e => logger.error(e)("ERROR!")).compile.drain.as(ExitCode.Success)
  }
}
