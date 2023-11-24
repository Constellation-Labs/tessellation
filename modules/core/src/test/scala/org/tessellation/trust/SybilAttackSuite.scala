package org.tessellation.trust

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.applicative._

import scala.io.Source

import org.tessellation.infrastructure.trust._

import io.circe._
import io.circe.generic.semiauto._
import io.circe.parser.decode
import io.circe.syntax._
import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

case class ModelBenchmark(
  sawDefenseFactor: Double,
  dattDefenseFactor: Double,
  eigenTrustDefenseFactor: Double,
  modelDefenseFactor: Double
)

object SybilAttackSuite extends SimpleIOSuite with Checkers {

  lazy implicit val edgeDecoder: Decoder[TrustEdge] = deriveDecoder
  lazy implicit val edgeEncoder: Encoder[TrustEdge] = deriveEncoder

  lazy implicit val nodeDecoder: Decoder[TrustNode] = deriveDecoder
  lazy implicit val nodeEncoder: Encoder[TrustNode] = deriveEncoder

  val random: util.Random = new scala.util.Random(0L)

  def benchmark(allData: List[TrustNode], filterGood: Int => Boolean): ModelBenchmark = {

    val scores = SelfAvoidingWalk.runWalkFeedbackUpdateSingleNode(
      0,
      allData
    )
    val relativeLocalTrust = scores.edges.filter(e => filterGood(e.dst)).map(_.trust).sum
    val relativeMaliciousTrust = scores.edges.filter(e => !filterGood(e.dst)).map(_.trust).sum

    val eigenTrustComparisonScores = EigenTrust.calculate(allData)
    val eigenTrustDefenseFactor = eigenTrustComparisonScores.filter(e => filterGood(e._1)).values.sum /
      eigenTrustComparisonScores.filter(e => !filterGood(e._1)).values.sum

    val defensiveFactor = relativeLocalTrust / relativeMaliciousTrust

    val dattScores = DATT.calculate(DATT.convert(allData), 0)

    val dattDefenseFactor = dattScores.filter(e => filterGood(e._1)).values.sum /
      dattScores.filter(e => !filterGood(e._1)).values.sum

    val modelScore = TrustModel.calculateTrust(allData, 0)

    val modelDefenseFactor = modelScore.filter(e => filterGood(e._1)).values.sum /
      modelScore.filter(e => !filterGood(e._1)).values.sum

    ModelBenchmark(
      sawDefenseFactor = defensiveFactor,
      dattDefenseFactor = dattDefenseFactor,
      eigenTrustDefenseFactor = eigenTrustDefenseFactor,
      modelDefenseFactor = modelDefenseFactor
    )
  }

  def averageBenchmark(benchmarks: List[ModelBenchmark]): ModelBenchmark =
    ModelBenchmark(
      benchmarks.map(_.sawDefenseFactor).sum / benchmarks.size,
      benchmarks.map(_.dattDefenseFactor).sum / benchmarks.size,
      benchmarks.map(_.eigenTrustDefenseFactor).sum / benchmarks.size,
      benchmarks.map(_.modelDefenseFactor).sum / benchmarks.size
    )

  test("Data generator") {

    val numNodes = 100
    val numNetworks = 10

    val generator = Random.scalaUtilRandomSeedInt[IO](0).map { implicit rnd =>
      new DataGenerator[IO]
    }
    for {
      _ <- ignore("Comment to modify data generation").unlessA(false)
      gen <- generator
      networkData <- List
        .fill(numNetworks)(gen.generateData(numNodes = numNodes, edgeLogic = gen.randomPositiveEdge()))
        .foldLeft(IO.pure(List.empty[List[TrustNode]])) {
          case (col, next) =>
            next.flatMap { n =>
              col.map(_ :+ n)
            }
        }
      ser = networkData.flatMap(_.map(_.asJson.noSpaces))
      _ <- IO.delay(
        Files
          .write(
            Paths.get("./src/test/resources/trust_100x10.json"),
            ser.mkString("\n").getBytes(StandardCharsets.UTF_8)
          )
      )
    } yield {
      val res = decode[TrustNode](ser.head)
      expect(res.isRight)
    }
  }

  def loadSerialized(): IO[List[List[TrustNode]]] = {
    val serializedNetworks = IO.delay {
      Source.fromResource("trust_100x10.json").getLines()
    }
    for {
      ser <- serializedNetworks
      decoded = ser.map(decode[TrustNode]).toList.flatMap(_.toOption).grouped(100).toList
    } yield decoded
  }

  test("Simple positive double network with weak edge sybil attack") {
    loadSerialized().map { allNetworks =>
      val numNodes = allNetworks.head.size
      val maliciousInviteNodeId = 10
      val maliciousEdgeId = numNodes + 40

      val benchmarks = allNetworks.grouped(2).toList.map { networks =>
        val data = networks.head.map { node =>
          // Add a single strong edge between networks.
          if (node.id == maliciousInviteNodeId)
            node.copy(edges = node.edges.appended(TrustEdge(node.id, maliciousEdgeId, 1.0, isLabel = true)))
          else node
        }
        val attackData = networks(1).map { tn =>
          val node = tn.copy(
            tn.id + numNodes,
            edges = tn.edges.map { e =>
              e.copy(src = e.src + numNodes, dst = e.dst + numNodes)
            }
          )
          // Add a single strong edge between networks.
          if (node.id == maliciousEdgeId)
            node.copy(edges = node.edges.appended(TrustEdge(node.id, maliciousInviteNodeId, 1.0, isLabel = true)))
          else node
        }
        val allData = data ++ attackData
        val b = benchmark(allData, _ < data.size)
        b
      }

      val benchAvg = averageBenchmark(benchmarks)

      expect.all(
        benchAvg.sawDefenseFactor > 2,
        benchAvg.eigenTrustDefenseFactor < 1.1,
        benchAvg.dattDefenseFactor > 8,
        benchAvg.modelDefenseFactor > 7
      )
    }
  }
  test("Proximity attack on immediate neighbors") {
    loadSerialized().map { allNetworks =>
      val benchmarks = allNetworks.map { network =>
        val self = network.filter(_.id == 0).head
        val badIdSize: Int = self.edges.size / 2
        val badIds = random.shuffle(self.edges.toList).take(badIdSize).map(_.dst)
        val b = benchmark(network, !badIds.contains(_))
        b
      }

      val benchAvg = averageBenchmark(benchmarks)

      expect.all(
        benchAvg.sawDefenseFactor < 10,
        benchAvg.eigenTrustDefenseFactor > 5,
        benchAvg.dattDefenseFactor > 10,
        benchAvg.modelDefenseFactor > 4
      )
    }
  }
  test("Proximity attack on nearby neighbors") {
    loadSerialized().map { allNetworks =>
      val benchmarks = allNetworks.map { network =>
        val self = network.filter(_.id == 0).head
        val edges = network.map(tn => tn.id -> tn.edges).toMap
        val badIdSize: Int = self.edges.size / 2
        val badIds = random.shuffle(self.edges.toList).take(badIdSize).map(_.dst)
        val nextBadIdSample = badIds.flatMap(edges.apply).map(_.dst)
        val nextBadIds = random.shuffle(nextBadIdSample).take(nextBadIdSample.size / 2)
        val allBadIds = nextBadIds ++ badIds
        val b = benchmark(network, !allBadIds.contains(_))
        b
      }

      val benchAvg = averageBenchmark(benchmarks)

      expect.all(
        benchAvg.sawDefenseFactor < 3,
        benchAvg.eigenTrustDefenseFactor > 1,
        benchAvg.dattDefenseFactor > 1,
        benchAvg.modelDefenseFactor > 1
      )
    }
  }

  test("Double network with multi-edge sybil attack + compromise half") {
    loadSerialized().map { allNetworks =>
      val numNodes = allNetworks.head.size
      val maliciousInviteNodeId = 10
      val maliciousEdgeId = numNodes + 40

      val benchmarks = allNetworks.grouped(2).toList.map { networks =>
        def withEdge(nodes: List[TrustNode], src: Int, dst: Int): List[TrustNode] =
          nodes.map { node =>
            // Add a single strong edge between networks.
            if (node.id == src)
              node.copy(edges = node.edges.appended(TrustEdge(node.id, dst, 1.0, isLabel = true)))
            else if (node.id == dst)
              node.copy(edges = node.edges.appended(TrustEdge(node.id, src, 1.0, isLabel = true)))
            else node
          }

        val regularAndAttack = List(
          networks.head,
          networks(1).map { tn =>
            tn.copy(
              tn.id + numNodes,
              edges = tn.edges.map { e =>
                e.copy(src = e.src + numNodes, dst = e.dst + numNodes)
              }
            )
          }
        )

        val allData = regularAndAttack.map { n =>
          withEdge(n, maliciousInviteNodeId, maliciousEdgeId)
        }.map { n =>
          withEdge(n, maliciousInviteNodeId, maliciousEdgeId + 1)
        }.map { n =>
          withEdge(n, maliciousInviteNodeId, maliciousEdgeId + 2)
        }.flatMap { n =>
          withEdge(n, maliciousInviteNodeId, maliciousEdgeId + 3)
        }

        val goodIds = random.shuffle(List.tabulate(numNodes)(identity)).take(numNodes / 2) :+ 0

        val b = benchmark(allData, goodIds.contains(_))
        b
      }

      val benchAvg = averageBenchmark(benchmarks)

      expect.all(
        benchAvg.sawDefenseFactor > 0.1,
        benchAvg.dattDefenseFactor > 0.4,
        benchAvg.eigenTrustDefenseFactor > 0.15,
        benchAvg.modelDefenseFactor > 0.4
      )
    }
  }
}
