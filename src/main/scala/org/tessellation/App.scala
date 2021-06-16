package org.tessellation

import cats.effect.{ExitCode, IO, IOApp}
import fs2.Stream
import io.chrisdavenport.log4cats.slf4j.Slf4jLogger
import org.http4s.client.blaze.BlazeClientBuilder
import org.tessellation.config.Config
import org.tessellation.consensus.transaction.RandomTransactionGenerator
import org.tessellation.http.HttpServer
import org.tessellation.http.HttpClient

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

      randomTransactionGenerator = RandomTransactionGenerator(config.ip, Some(config.generatorSrc))
      node = Node(config.ip, randomTransactionGenerator, ip = config.ip)

      blazeClient <- Stream.resource {
        BlazeClientBuilder[IO](scala.concurrent.ExecutionContext.global) // TODO: Use unbounded
          .withConnectTimeout(30.seconds)
          .withMaxTotalConnections(1024)
          .withMaxWaitQueueLimit(512)
          .resource
      }
      httpClient = HttpClient(node, blazeClient)
      httpServer = HttpServer(node, httpClient)

      _ <- Stream.eval(logger.debug(s"Created http server and client"))

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
              .through(node.l1ConsensusPipeline(httpClient))
          )
      } else {
        httpServer.run()
      }
    } yield ()

    app.handleErrorWith(e => Stream.eval(logger.error(e)("ERROR!"))).compile.drain.as(ExitCode.Success)
  }
}
