package org.tessellation

import cats.effect.{ExitCode, IO, IOApp}
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.blaze.BlazeClientBuilder
import org.tessellation.config.Config
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.http.HttpServer
import org.tessellation.http.HttpClient
import org.tessellation.implicits._
import cats.syntax._
import cats.implicits._
import org.tessellation.metrics.Metric._
import org.http4s.metrics.prometheus.Prometheus
import org.tessellation.consensus.L1Pipeline
import org.tessellation.metrics.{Metric, Metrics}
import org.tessellation.snapshot.L0Pipeline

import scala.concurrent.duration._
import scala.util.Random

object App extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val generateTxEvery = 2.seconds
    val maxTxs = 1000
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

      l1Pipeline = new L1Pipeline(node, httpClient, metrics).pipeline
      l0Pipeline = new L0Pipeline(metrics).pipeline

      _ <- if (config.startOwnConsensusRounds) {
        httpServer
          .run()
          .merge(
            Stream
              .repeatEval(node.enoughPeersForConsensus)
              .map(hasFacilitatorsForConsensus => hasFacilitatorsForConsensus)
              .dropWhile(!_)
              .evalMap(_ => node.txGenerator.generateRandomTransaction())
              .evalTap(tx => logger.debug(s"$tx"))
              .metered(generateTxEvery)
              .take(maxTxs)
              .through { txs =>
                l0Pipeline.compose(l1Pipeline)(txs)
              }
          )
      } else {
        httpServer.run()
      }
    } yield ()

    app.handleErrorWith(e => Stream.eval(logger.error(e)("ERROR!"))).compile.drain.as(ExitCode.Success)
  }
}
