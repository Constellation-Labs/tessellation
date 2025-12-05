package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.prover.{MerklePatriciaBatchInclusionProver, MerklePatriciaSingleInclusionProver}
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object MerklePatriciaBatchInclusionProverSuite extends MutableIOSuite with Checkers {

  type Res = HasherSelector[IO]

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit kryo =>
      JsonSerializer.forAsync[IO].asResource.map { implicit json =>
        HasherSelector.forSync[IO](
          Hasher.forJson[IO],
          Hasher.forKryo[IO],
          hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
        )
      }
    }

  test("batch prover produces proof for multiple paths") { implicit res =>
    forall(Gen.listOfN(32, Gen.long).flatMap { list =>
      Gen.choose(3, list.size).flatMap { subsetSize =>
        Gen.pick(subsetSize, list).map(subset => (list, subset.toList))
      }
    }) {
      case (fullList, subset) =>
        res.withCurrent { implicit hasher =>
          for {
            leafPairs <- fullList.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el))
            trie <- MerklePatriciaTrie.make(leafPairs.toMap)
            prover = MerklePatriciaBatchInclusionProver.make[IO](trie)

            paths <- subset.traverse(el => hasher.hash(el).map(hash => Hex(hash.value)))
            proof <- prover.attestPaths(paths).flatMap(IO.fromEither)
          } yield
            expect.all(
              proof.paths.size == subset.size,
              proof.witness.nonEmpty,
              proof.paths.sorted == proof.paths
            )
        }
    }
  }

  test("batch proof has smaller witness than individual proofs combined") { implicit res =>
    res.withCurrent { implicit hasher =>
      val entries = (1 to 10).toList

      for {
        leafPairs <- entries.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el))
        trie <- MerklePatriciaTrie.make(leafPairs.toMap)

        paths = leafPairs.take(5).map(_._1)

        batchProver = MerklePatriciaBatchInclusionProver.make[IO](trie)
        singleProver = MerklePatriciaSingleInclusionProver.make[IO](trie)

        batchProof <- batchProver.attestPaths(paths).flatMap(IO.fromEither)
        individualProofs <- paths.traverse(p => singleProver.attestPath(p).flatMap(IO.fromEither))

        totalIndividualWitnessSize = individualProofs.flatMap(_.witness).size
        batchWitnessSize = batchProof.witness.size
      } yield expect(batchWitnessSize < totalIndividualWitnessSize)
    }
  }

  test("batch prover fails when all paths are missing") { implicit res =>
    forall(Gen.listOfN(10, Gen.long)) { list =>
      res.withCurrent { implicit hasher =>
        for {
          leafMap <- list.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el)).map(_.toMap)
          trie <- MerklePatriciaTrie.make(leafMap)
          prover = MerklePatriciaBatchInclusionProver.make[IO](trie)

          missingPaths = List(
            Hex("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
            Hex("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB")
          )

          proofEither <- prover.attestPaths(missingPaths)
        } yield expect(proofEither.isLeft)
      }
    }
  }

  test("batch prover handles empty path list") { implicit res =>
    forall(Gen.listOfN(10, Gen.long)) { list =>
      res.withCurrent { implicit hasher =>
        for {
          leafMap <- list.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el)).map(_.toMap)
          trie <- MerklePatriciaTrie.make(leafMap)
          prover = MerklePatriciaBatchInclusionProver.make[IO](trie)
          proofEither <- prover.attestPaths(List.empty)
        } yield expect(proofEither.isLeft)
      }
    }
  }

  test("batch prover automatically sorts paths") { implicit res =>
    res.withCurrent { implicit hasher =>
      val values = List(100L, 200L, 300L, 400L)

      for {
        leafPairs <- values.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el))
        trie <- MerklePatriciaTrie.make(leafPairs.toMap)
        prover = MerklePatriciaBatchInclusionProver.make[IO](trie)

        paths = leafPairs.map(_._1)
        shuffledPaths = new scala.util.Random(42).shuffle(paths)

        proof <- prover.attestPaths(shuffledPaths).flatMap(IO.fromEither)
      } yield expect(proof.paths == paths.sorted)
    }
  }

  test("batch prover deduplicates witness commitments") { implicit res =>
    res.withCurrent { implicit hasher =>
      val entries = (1 to 20).toList

      for {
        leafPairs <- entries.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el))
        trie <- MerklePatriciaTrie.make(leafPairs.toMap)
        prover = MerklePatriciaBatchInclusionProver.make[IO](trie)

        paths = leafPairs.map(_._1)
        proof <- prover.attestPaths(paths).flatMap(IO.fromEither)

        uniqueCommitments = proof.witness.distinct
      } yield expect(proof.witness == uniqueCommitments)
    }
  }

  test("batch prover handles adjacent paths efficiently") { implicit res =>
    res.withCurrent { implicit hasher =>
      val adjacentValues = (0 to 15).toList

      for {
        leafPairs <- adjacentValues.traverse(el => hasher.hash(el).map(hash => Hex(hash.value) -> el))
        trie <- MerklePatriciaTrie.make(leafPairs.toMap)
        prover = MerklePatriciaBatchInclusionProver.make[IO](trie)

        paths = leafPairs.take(10).map(_._1)
        proof <- prover.attestPaths(paths).flatMap(IO.fromEither)
      } yield
        expect.all(
          proof.paths.size == 10,
          proof.witness.nonEmpty
        )
    }
  }
}
