package org.tessellation

import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import fs2.{Pipe, Stream}
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.blaze.BlazeClientBuilder
import org.http4s.metrics.prometheus.Prometheus
import org.tessellation.config.Config
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.consensus.{DAGStateChannel, L1Block}
import org.tessellation.eth.ETHStateChannel
import org.tessellation.http.{HttpClient, HttpServer}
import org.tessellation.metrics.Metric._
import org.tessellation.metrics.{Metric, Metrics}
import org.tessellation.node.Node
import org.tessellation.snapshot.{L0Pipeline, Snapshot}
import org.tessellation.implicits.PipeArrow._

import scala.concurrent.duration._

object App extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val logger = Slf4jLogger.getLogger[IO]

    val app = for {
      config <- Stream.eval(Config.load())
      _ <- Stream.eval(logger.debug(s"Loaded config $config"))

      registry <- Stream.resource(Prometheus.collectorRegistry[IO])
      unbounded = executionContext
      metrics = new Metrics(registry, unboundedExecutionContext = unbounded, nodeID = config.ip)

      _ <- Stream.eval(metrics.incrementMetricAsync[IO](Metric.LoadConfig.success))
      randomTransactionGenerator = RandomTransactionGenerator(config.ip, Some(config.generatorSrc))
      node = Node(config.ip, metrics, randomTransactionGenerator, ip = config.ip)

      blazeClient <- Stream.resource {
        BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global) // TODO: Use unbounded
          .withConnectTimeout(30.seconds)
          .withMaxTotalConnections(1024)
          .withMaxWaitQueueLimit(512)
          .resource
      }
      httpClient = HttpClient(node, blazeClient)
      httpServer = HttpServer(node, httpClient, metrics)

      _ <- Stream.eval(logger.debug(s"Created http server and client"))

      stateChannels = {
        val DAG = new DAGStateChannel(node, httpClient, metrics)
        val ETH = new ETHStateChannel()
        val L0: Pipe[IO, L1Block, Snapshot] = new L0Pipeline(metrics).pipeline

        val dag = (DAG.L1 >>> L0)(DAG.l1Input)
        val eth = (ETH.L1 >>> L0)(ETH.l1Input)

        dag.merge(eth)
      }

      _ <- httpServer.run().merge(stateChannels)
    } yield ()

    app.handleErrorWith(e => Stream.eval(logger.error(e)("ERROR!"))).compile.drain.as(ExitCode.Success)
  }
}
