package org.tessellation.trust

import cats.effect.IO
import cats.effect.std.Random

import org.tessellation.infrastructure.trust.{SelfAvoidingWalk, TrustEdge}

import weaver.SimpleIOSuite
import weaver.scalacheck.Checkers

object SybilAttackSuite extends SimpleIOSuite with Checkers {
  test("Simple positive double network with weak edge sybil attack") {
    val numNodes = 30

    val maliciousInviteNodeId = 0
    val maliciousEdgeId = 40

    val generator = Random.scalaUtilRandom[IO].map { implicit rnd =>
      new DataGenerator[IO]
    }
    for {
      gen <- generator
      data <- gen.generateData(numNodes = numNodes, edgeLogic = gen.randomPositiveEdge()).map { nodes =>
        nodes.map { node =>
          // Add a single strong edge between networks.
          if (node.id == maliciousInviteNodeId)
            node.copy(edges = node.edges.appended(TrustEdge(node.id, maliciousEdgeId, 1.0, isLabel = true)))
          else node
        }
      }
      attackData <- gen.generateData(numNodes = numNodes).map { nodes =>
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

      val defensiveFactor = relativeLocalTrust / relativeMaliciousTrust
      expect(defensiveFactor > 2)
    }
  }
}
