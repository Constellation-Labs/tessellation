package org.tessellation.trust

import cats.effect.IO
import cats.effect.std.Random
import cats.syntax.applicative._

import org.tessellation.infrastructure.trust._

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object SybilAttackSuite extends SimpleIOSuite with Checkers {
  test("Simple positive double network with weak edge sybil attack") {
    val numNodes = 30

    val maliciousInviteNodeId = 10
    val maliciousEdgeId = 40

    val generator = Random.scalaUtilRandomSeedInt[IO](0).map { implicit rnd =>
      new DataGenerator[IO]
    }
    for {
      _ <- ignore("Non-deterministic unit test!").unlessA(false)
      gen <- generator
      data <- gen.generateData(numNodes = numNodes, edgeLogic = gen.randomPositiveEdge()).map { nodes =>
        nodes.map { node =>
          // Add a single strong edge between networks.
          if (node.id == maliciousInviteNodeId)
            node.copy(edges = node.edges.appended(TrustEdge(node.id, maliciousEdgeId, 1.0, isLabel = true)))
          else node
        }
      }
      attackData <- gen.generateData(numNodes = numNodes, edgeLogic = gen.randomPositiveEdge()).map { nodes =>
        nodes.map { tn =>
          val node = tn.copy(tn.id + numNodes, edges = tn.edges.map { e =>
            e.copy(src = e.src + numNodes, dst = e.dst + numNodes)
          })
          // Add a single strong edge between networks.
          if (node.id == maliciousEdgeId)
            node.copy(edges = node.edges.appended(TrustEdge(node.id, maliciousInviteNodeId, 1.0, isLabel = true)))
          else node
        }
      }
    } yield {
      val allData = data ++ attackData

      val scores = SelfAvoidingWalk.runWalkFeedbackUpdateSingleNode(
        0,
        allData
      )
      val relativeLocalTrust = scores.edges.filter(_.dst < numNodes).map(_.trust).sum
      val relativeMaliciousTrust = scores.edges.filter(_.dst >= numNodes).map(_.trust).sum

      val eigenTrustComparisonScores = EigenTrust.calculate(allData)
      val eigenTrustDefenseFactor = eigenTrustComparisonScores.filter(_._1 < numNodes).values.sum /
        eigenTrustComparisonScores.filter(_._1 >= numNodes).values.sum

      val defensiveFactor = relativeLocalTrust / relativeMaliciousTrust

      val dattScores = DATT.calculate(DATT.convert(allData), 0)

      val dattDefenseFactor = dattScores.filter(_._1 < numNodes).values.sum /
        dattScores.filter(_._1 >= numNodes).values.sum

      val modelScore = TrustModel.calculateTrust(allData, 0)

      val modelDefenseFactor = modelScore.filter(_._1 < numNodes).values.sum /
        modelScore.filter(_._1 >= numNodes).values.sum

      expect.all(
        defensiveFactor > 2,
        eigenTrustDefenseFactor < 1.1,
        dattDefenseFactor > 2,
        modelDefenseFactor > 2
      )
    }
  }
}
