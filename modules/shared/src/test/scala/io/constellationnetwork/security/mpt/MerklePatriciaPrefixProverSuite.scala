package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.prover.MerklePatriciaPrefixProver
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.syntax._
import org.scalacheck.Gen
import weaver.scalacheck.Checkers
import weaver.{MutableIOSuite, SimpleIOSuite}

object MerklePatriciaPrefixProverSuite extends MutableIOSuite with Checkers {

  type Res = HasherSelector[IO]

  def sharedResource: Resource[IO, HasherSelector[IO]] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit kryo =>
      JsonSerializer.forAsync[IO].asResource.map { implicit json =>
        HasherSelector.forSync[IO](
          Hasher.forJson[IO],
          Hasher.forKryo[IO],
          hashSelect = new HashSelect { def select(ordinal: SnapshotOrdinal): HashLogic = KryoHash }
        )
      }
    }

  test("prefix match returns all entries with that prefix") { implicit res =>
    res.withCurrent { implicit hasher =>
      val commonPrefix = "abcd"

      for {
        prefixedEntries <- (1 to 10).toList.traverse { i =>
          val suffix = f"$i%02d" + "0" * 58
          val prefixedKey = Hex(commonPrefix + suffix)
          hasher.hash(s"value_$i").map(_ => prefixedKey -> s"value_$i")
        }
        otherEntries <- (1 to 5).toList.traverse { i =>
          hasher.hash(s"other_$i").map(hash => Hex(hash.value) -> s"other_$i")
        }

        trie <- MerklePatriciaTrie.make((prefixedEntries ++ otherEntries).toMap)
        prover = MerklePatriciaPrefixProver.make[IO](trie)
        proof <- prover.attestPrefix(Hex(commonPrefix)).flatMap(IO.fromEither)
      } yield
        expect.all(
          proof.paths.size == 10,
          proof.paths.forall(_.value.startsWith(commonPrefix)),
          proof.witness.nonEmpty
        )
    }
  }

  test("prefix with no matches returns error") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaPrefixProver.make[IO](trie)
        proofEither <- prover.attestPrefix(Hex("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"))
      } yield expect(proofEither.isLeft)
    }
  }

  test("hierarchical prefix matching") { implicit res =>
    res.withCurrent { implicit hasher =>
      val level1Prefix = "aa"
      val level2Prefix = level1Prefix + "bb"

      for {
        level1Entries <- (1 to 5).toList.traverse { i =>
          val key = Hex(level1Prefix + "cc" + s"$i".padTo(58, '0').mkString)
          hasher.hash(s"level1_$i").map(_ => key -> s"level1_$i")
        }
        level2Entries <- (1 to 3).toList.traverse { i =>
          val key = Hex(level2Prefix + s"$i".padTo(58, '0').mkString)
          hasher.hash(s"level2_$i").map(_ => key -> s"level2_$i")
        }
        otherEntries <- (1 to 5).toList.traverse { i =>
          hasher.hash(s"other_$i").map(hash => Hex(hash.value) -> s"other_$i")
        }

        trie <- MerklePatriciaTrie.make((level1Entries ++ level2Entries ++ otherEntries).toMap)
        prover = MerklePatriciaPrefixProver.make[IO](trie)

        level1Proof <- prover.attestPrefix(Hex(level1Prefix)).flatMap(IO.fromEither)
        level2Proof <- prover.attestPrefix(Hex(level2Prefix)).flatMap(IO.fromEither)
      } yield
        expect.all(
          level1Proof.paths.size == 8,
          level2Proof.paths.size == 3,
          level1Proof.paths.forall(_.value.startsWith(level1Prefix)),
          level2Proof.paths.forall(_.value.startsWith(level2Prefix))
        )
    }
  }

  test("single-character prefix returns many entries") { implicit res =>
    res.withCurrent { implicit hasher =>
      val singleCharPrefix = "a"

      for {
        prefixedEntries <- (1 to 20).toList.traverse { i =>
          val suffix = f"$i%02d" + "0" * 61
          val key = Hex(singleCharPrefix + suffix)
          hasher.hash(s"value_$i").map(_ => key -> s"value_$i")
        }
        otherEntries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"other_$i").map(hash => Hex("b" + hash.value.drop(1)) -> s"other_$i")
        }

        trie <- MerklePatriciaTrie.make((prefixedEntries ++ otherEntries).toMap)
        prover = MerklePatriciaPrefixProver.make[IO](trie)
        proof <- prover.attestPrefix(Hex(singleCharPrefix)).flatMap(IO.fromEither)
      } yield
        expect.all(
          proof.paths.size == 20,
          proof.paths.forall(_.value.startsWith(singleCharPrefix))
        )
    }
  }

  test("full key as prefix returns single entry") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }
        trie <- MerklePatriciaTrie.make(entries.toMap)
        prover = MerklePatriciaPrefixProver.make[IO](trie)

        fullKey = entries.head._1
        proof <- prover.attestPrefix(fullKey).flatMap(IO.fromEither)
      } yield
        expect.all(
          proof.paths.size == 1,
          proof.paths.head == fullKey
        )
    }
  }

  test("large trie prefix query") { implicit res =>
    val numEntries = 1000
    val numMatching = 100
    val matchingPrefix = "abc"

    res.withCurrent { implicit hasher =>
      for {
        matchingEntries <- (1 to numMatching).toList.traverse { i =>
          val suffix = f"$i%03d" + "0" * 58
          val key = Hex(matchingPrefix + suffix)
          hasher.hash(s"matching_$i").map(_ => key -> s"matching_$i")
        }
        otherEntries <- (1 to (numEntries - numMatching)).toList.traverse { i =>
          hasher.hash(s"other_$i").map(hash => Hex("d" + hash.value.drop(1)) -> s"other_$i")
        }

        trie <- MerklePatriciaTrie.make((matchingEntries ++ otherEntries).toMap)
        prover = MerklePatriciaPrefixProver.make[IO](trie)
        proof <- prover.attestPrefix(Hex(matchingPrefix)).flatMap(IO.fromEither)
      } yield
        expect.all(
          proof.paths.size == numMatching,
          proof.paths.forall(_.value.startsWith(matchingPrefix)),
          proof.witness.nonEmpty
        )
    }
  }

  test("prefix proof for token lock balances of specific token address") { implicit res =>
    res.withCurrent { implicit hasher =>
      val tokenPrefix = "abc123def456"

      for {
        tokenLockEntries <- (1 to 10).toList.traverse { i =>
          val key = Hex(tokenPrefix + f"$i%02d" + "0" * 50)
          hasher.hash(s"lock_$i").map(_ => key -> s"lock_$i")
        }
        otherEntries <- (1 to 3).toList.traverse { i =>
          val key = Hex("fedcba987654" + f"$i%02d" + "0" * 50)
          hasher.hash(s"balance_$i").map(_ => key -> s"balance_$i")
        }

        trie <- MerklePatriciaTrie.make((tokenLockEntries ++ otherEntries).toMap)
        prover = MerklePatriciaPrefixProver.make[IO](trie)

        proof <- prover.attestPrefix(Hex(tokenPrefix)).flatMap(IO.fromEither)
      } yield
        expect.all(
          proof.paths.size == 10,
          proof.paths.forall(_.value.startsWith(tokenPrefix))
        )
    }
  }
}
