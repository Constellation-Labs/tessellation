package io.constellationnetwork.node.shared.infrastructure.consensus

import cats.effect.IO
import cats.syntax.all._

import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.types.numeric.PosInt
import weaver.SimpleIOSuite

object ValidationErrorStorageSuite extends SimpleIOSuite {
  test("evicts older entries based on size when adding entries one by one") {
    for {
      storage <- ValidationErrorStorage.make[IO, Int, String](maxSize = PosInt(3), extractTransactions)
      _ <- storage.add(1, "one")
      _ <- storage.add(2, "two")
      _ <- storage.add(3, "three")
      _ <- storage.add(4, "four")
      all <- storage.get
      rejectedArtifacts = all.map(x => (x.consensusEvent, x.validationError))
      rejectedTransactions <- getRejectedTransactions(
        storage,
        Hash("11"),
        Hash("12"),
        Hash("21"),
        Hash("22"),
        Hash("23"),
        Hash("31"),
        Hash("41"),
        Hash("42"),
        Hash("51")
      )
    } yield
      expect
        .same(IndexedSeq((4, "four"), (3, "three"), (2, "two")), rejectedArtifacts)
        .and(
          expect.same(
            List(None, None, Some("two"), Some("two"), Some("two"), Some("three"), Some("four"), Some("four"), None),
            rejectedTransactions
          )
        )
  }

  test("evicts older entries based on size when adding a non-empty list") {
    for {
      storage <- ValidationErrorStorage.make[IO, Int, String](maxSize = PosInt(3), extractTransactions)
      errors = List((1, "one"), (2, "two"), (3, "three"), (4, "four"), (5, "five"))
      _ <- storage.add(errors)
      all <- storage.get
      rejectedArtifacts = all.map(x => (x.consensusEvent, x.validationError))
      rejectedTransactions <- getRejectedTransactions(
        storage,
        Hash("11"),
        Hash("12"),
        Hash("21"),
        Hash("22"),
        Hash("23"),
        Hash("31"),
        Hash("41"),
        Hash("42"),
        Hash("51")
      )
    } yield
      expect
        .same(IndexedSeq((5, "five"), (4, "four"), (3, "three")), rejectedArtifacts)
        .and(expect.same(List(None, None, None, None, None, Some("three"), Some("four"), Some("four"), Some("five")), rejectedTransactions))
  }

  test("correctly adds an empty list of entries") {
    for {
      storage <- ValidationErrorStorage.make[IO, Int, String](maxSize = PosInt(3), extractTransactions)
      errors = List.empty[(Int, String)]
      _ <- storage.add(errors)
      all <- storage.get
      rejectedArtifacts = all.map(x => (x.consensusEvent, x.validationError))
      rejectedTransactions <- getRejectedTransactions(
        storage,
        Hash("11"),
        Hash("12"),
        Hash("21"),
        Hash("22"),
        Hash("23"),
        Hash("31"),
        Hash("41"),
        Hash("42"),
        Hash("51")
      )
    } yield
      expect
        .same(IndexedSeq(), rejectedArtifacts)
        .and(expect.same(List(None, None, None, None, None, None, None, None, None), rejectedTransactions))
  }

  private def getRejectedTransactions(storage: ValidationErrorStorage[IO, Int, String], txns: Hash*): IO[List[Option[String]]] =
    txns.toList.traverse(storage.getTransaction)

  private def extractTransactions(artifact: Int): IO[List[Hash]] = IO.pure {
    artifact match {
      case 1 =>
        List(
          Hash("11"),
          Hash("12")
        )
      case 2 =>
        List(
          Hash("21"),
          Hash("22"),
          Hash("23")
        )
      case 3 =>
        List(
          Hash("31")
        )
      case 4 =>
        List(
          Hash("41"),
          Hash("42")
        )
      case 5 =>
        List(
          Hash("51")
        )
    }
  }
}
