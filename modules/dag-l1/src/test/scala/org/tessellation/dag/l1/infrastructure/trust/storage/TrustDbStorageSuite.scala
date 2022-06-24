package org.tessellation.dag.l1.infrastructure.trust.storage

import cats.effect.{IO, Resource}

import org.tessellation.dag.l1.config.types.DBConfig
import org.tessellation.dag.l1.domain.trust.storage.TrustStorage
import org.tessellation.dag.l1.infrastructure.db.Database
import org.tessellation.dag.l1.infrastructure.trust.storage.TrustDbValuesGenerator.genTrustDbValues

import ciris.Secret
import eu.timepit.refined.auto._
import weaver._
import weaver.scalacheck._

object TrustDbStorageSuite extends SimpleIOSuite with Checkers {

  val dbConfig = DBConfig("org.sqlite.JDBC", "jdbc:sqlite::memory:", "sa", Secret(""))

  def testResource: Resource[IO, TrustStorage[IO]] = Database.forAsync[IO](dbConfig).map { implicit db =>
    TrustDbStorage.make[IO]
  }

  test("Test writing trust values to the database - score") {
    forall(genTrustDbValues) { trustDbValues =>
      val peerId = trustDbValues.peerId

      testResource.use { trustStorage =>
        trustStorage.updateTrustValues(Map(peerId -> trustDbValues)) >>
          trustStorage.getScore(peerId).map(expect.same(_, trustDbValues.score))
      }
    }
  }

  test("Test writing trust values to the database - rating") {
    forall(genTrustDbValues) { trustDbValues =>
      val peerId = trustDbValues.peerId

      testResource.use { trustStorage =>
        trustStorage.updateTrustValues(Map(peerId -> trustDbValues)) >>
          trustStorage.getRating(peerId).map(expect.same(_, trustDbValues.rating))
      }
    }
  }

  test("Test writing trust values to the database - observationAdjustment") {
    forall(genTrustDbValues) { trustDbValues =>
      val peerId = trustDbValues.peerId

      testResource.use { trustStorage =>
        trustStorage.updateTrustValues(Map(peerId -> trustDbValues)) >>
          trustStorage.getObservationAdjustment(peerId).map(expect.same(_, trustDbValues.observationAdjustment))
      }
    }
  }
}
