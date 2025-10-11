package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.generators.addressGen
import io.constellationnetwork.schema.mpt.{GlobalStateFieldId, GlobalStateKey}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.prover.MerklePatriciaRangeProver
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import io.circe.syntax._
import org.scalacheck.Gen
import weaver.scalacheck.Checkers
import weaver.{MutableIOSuite, SimpleIOSuite}

object MerklePatriciaRangeProverSuite extends MutableIOSuite with Checkers {

  type Res = HasherSelector[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit kryo =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        HasherSelector.forSync[IO](
          Hasher.forJson[IO],
          Hasher.forKryo[IO],
          hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
        )
      }
    }

  test("range with multiple entries") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "3000"
      val end = "8000"

      for {
        inRangeEntries <- List("4000", "5000", "6000", "7000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }
        outsideEntries <- List("1000", "2000", "9000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }

        trie   <- MerklePatriciaTrie.make((inRangeEntries ++ outsideEntries).toMap)
        prover  = MerklePatriciaRangeProver.make[IO](trie)
        proof  <- prover.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)
      } yield expect.all(
        proof.inclusionProofs.size == 4,
        proof.inclusionProofs.forall(p => p.path.value >= start && p.path.value <= end),
        proof.exclusionBoundaries.isDefined
      )
    }
  }

  test("empty range with boundaries") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "5000"
      val end = "5500"

      for {
        entries <- List("1000", "2000", "6000", "7000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }

        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaRangeProver.make[IO](trie)
        proof  <- prover.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)
      } yield expect.all(
        proof.inclusionProofs.isEmpty,
        proof.exclusionBoundaries.isDefined,
        proof.exclusionBoundaries.flatMap(_.leftBoundary).isDefined,
        proof.exclusionBoundaries.flatMap(_.rightBoundary).isDefined
      )
    }
  }

  test("range boundary validation") { implicit res =>
    res.withCurrent { implicit hasher =>
      val start = "5000"
      val end = "7000"

      for {
        entries <- List("3000", "4000", "5500", "6000", "8000", "9000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }

        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaRangeProver.make[IO](trie)
        proof  <- prover.attestRange(Hex(start.padTo(64, '0')), Hex(end.padTo(64, '0'))).flatMap(IO.fromEither)

        leftBoundaryPath = proof.exclusionBoundaries.flatMap(_.leftBoundary).map(_.path)
        rightBoundaryPath = proof.exclusionBoundaries.flatMap(_.rightBoundary).map(_.path)
      } yield expect.all(
        leftBoundaryPath.exists(_.value < start.padTo(64, '0')),
        rightBoundaryPath.exists(_.value > end.padTo(64, '0'))
      )
    }
  }

  test("full range query returns all entries") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 20).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaRangeProver.make[IO](trie)

        minKey = entries.map(_._1).min(Ordering.by[Hex, String](_.value))
        maxKey = entries.map(_._1).max(Ordering.by[Hex, String](_.value))

        proof <- prover.attestRange(minKey, maxKey).flatMap(IO.fromEither)
      } yield expect.all(
        proof.inclusionProofs.size == 20,
        proof.exclusionBoundaries.isEmpty
      )
    }
  }

  test("single entry in range") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- List("1000", "2000", "5000", "8000", "9000").traverse { key =>
          val paddedKey = Hex(key.padTo(64, '0'))
          hasher.hash(s"value_$key").map(_ => paddedKey -> s"value_$key")
        }

        trie   <- MerklePatriciaTrie.make(entries.toMap)
        prover  = MerklePatriciaRangeProver.make[IO](trie)

        start = Hex("4900".padTo(64, '0'))
        end = Hex("5100".padTo(64, '0'))
        proof <- prover.attestRange(start, end).flatMap(IO.fromEither)
      } yield expect.all(
        proof.inclusionProofs.size == 1,
        proof.inclusionProofs.head.path.value.startsWith("5000"),
        proof.exclusionBoundaries.isDefined
      )
    }
  }

  test("invalid range (start > end) returns error") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        trie        <- MerklePatriciaTrie.make(entries.toMap)
        prover       = MerklePatriciaRangeProver.make[IO](trie)
        proofEither <- prover.attestRange(Hex("9000".padTo(64, '0')), Hex("1000".padTo(64, '0')))
      } yield expect(proofEither.isLeft)
    }
  }

  test("paginated queries across large dataset") { implicit res =>
    val pageSize = 10
    val totalEntries = 50

    res.withCurrent { implicit hasher =>
      for {
        entries <- (0 until totalEntries).toList.traverse { i =>
          val suffix = f"${i * 100}%04d" + "0" * 60
          val key = Hex(suffix)
          hasher.hash(s"value_$i").map(_ => key -> s"value_$i")
        }

        sortedEntries = entries.sortBy(_._1.value)
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaRangeProver.make[IO](trie)

        page1Start = sortedEntries(0)._1
        page1End = sortedEntries(pageSize - 1)._1
        page1Proof <- prover.attestRange(page1Start, page1End).flatMap(IO.fromEither)

        page2Start = sortedEntries(pageSize)._1
        page2End = sortedEntries(2 * pageSize - 1)._1
        page2Proof <- prover.attestRange(page2Start, page2End).flatMap(IO.fromEither)

        allPage1Paths = page1Proof.inclusionProofs.map(_.path).toSet
        allPage2Paths = page2Proof.inclusionProofs.map(_.path).toSet
      } yield expect.all(
        page1Proof.inclusionProofs.size == pageSize,
        page2Proof.inclusionProofs.size == pageSize,
        (allPage1Paths & allPage2Paths).isEmpty
      )
    }
  }

  test("range proof for paginated balance queries with addresses") { implicit res =>
    forall(Gen.listOfN(20, addressGen)) { addresses =>
      res.withCurrent { implicit hasher =>
        for {
          keys <- addresses.traverse { addr =>
            GlobalStateKey.toHex[IO](
              GlobalStateKey(None, GlobalStateFieldId.Balances, Some(addr), None)
            )
          }
          keyValuePairs <- keys.zipWithIndex.traverse {
            case (key, idx) => Balance((idx + 1) * 1000L).asJson.pure[IO].map(key -> _)
          }

          trie   <- MerklePatriciaTrie.make(keyValuePairs.toMap)
          prover  = MerklePatriciaRangeProver.make[IO](trie)

          sortedKeys = keys.sorted(Ordering.by[Hex, String](_.value))
          startKey   = sortedKeys(5)
          endKey     = sortedKeys(14)

          proof <- prover.attestRange(startKey, endKey).flatMap(IO.fromEither)
        } yield expect.all(
          proof.inclusionProofs.size == 10,
          proof.exclusionBoundaries.isDefined,
          proof.inclusionProofs.map(_.path).forall(p => p.value >= startKey.value && p.value <= endKey.value)
        )
      }
    }
  }
}