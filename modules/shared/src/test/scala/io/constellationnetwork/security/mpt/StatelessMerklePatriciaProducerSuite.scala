package io.constellationnetwork.security.mpt

import cats.effect.{IO, Resource}
import cats.syntax.all._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.security._
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.producer.StatelessMerklePatriciaProducer
import io.constellationnetwork.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import weaver.{MutableIOSuite, SimpleIOSuite}

object StatelessMerklePatriciaProducerSuite extends MutableIOSuite {

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

  test("create trie from non-empty map") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        trie <- producer.create(entries.toMap)
      } yield expect(trie.rootNode.digest.value.nonEmpty)
    }
  }

  test("insert single entry into existing trie") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        initialEntries <- (1 to 5).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        initialTrie <- producer.create(initialEntries.toMap)
        initialRoot = initialTrie.rootNode.digest

        newKey <- hasher.hash("new_value").map(hash => Hex(hash.value))
        newEntry = Map(newKey -> "new_value")

        updatedTrieEither <- producer.insert(initialTrie, newEntry)
        updatedTrie <- IO.fromEither(updatedTrieEither)
      } yield expect(updatedTrie.rootNode.digest != initialRoot)
    }
  }

  test("insert multiple entries") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        initialEntries <- (1 to 5).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        initialTrie <- producer.create(initialEntries.toMap)

        newEntries <- (6 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        updatedTrieEither <- producer.insert(initialTrie, newEntries.toMap)
        updatedTrie <- IO.fromEither(updatedTrieEither)
      } yield expect(updatedTrie.rootNode.digest.value.nonEmpty)
    }
  }

  test("insert duplicate key updates value") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        key <- hasher.hash("key").map(hash => Hex(hash.value))
        entries = Map(key -> "initial_value")

        producer = StatelessMerklePatriciaProducer[IO]
        initialTrie <- producer.create(entries)
        initialRoot = initialTrie.rootNode.digest

        updateEntry = Map(key -> "updated_value")
        updatedTrieEither <- producer.insert(initialTrie, updateEntry)
        updatedTrie <- IO.fromEither(updatedTrieEither)
      } yield expect(updatedTrie.rootNode.digest != initialRoot)
    }
  }

  test("remove single entry") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        initialTrie <- producer.create(entries.toMap)
        initialRoot = initialTrie.rootNode.digest

        keyToRemove = entries.head._1
        updatedTrieEither <- producer.remove(initialTrie, List(keyToRemove))
        updatedTrie <- IO.fromEither(updatedTrieEither)
      } yield expect(updatedTrie.rootNode.digest != initialRoot)
    }
  }

  test("remove multiple entries") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        initialTrie <- producer.create(entries.toMap)

        keysToRemove = entries.take(3).map(_._1)
        updatedTrieEither <- producer.remove(initialTrie, keysToRemove)
        updatedTrie <- IO.fromEither(updatedTrieEither)
      } yield expect(updatedTrie.rootNode.digest.value.nonEmpty)
    }
  }

  test("remove non-existent key does not fail") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 5).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        initialTrie <- producer.create(entries.toMap)
        initialRoot = initialTrie.rootNode.digest

        nonExistentKey = Hex("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF")
        updatedTrieEither <- producer.remove(initialTrie, List(nonExistentKey))
        updatedTrie <- IO.fromEither(updatedTrieEither)
      } yield expect(updatedTrie.rootNode.digest == initialRoot)
    }
  }

  test("get prover returns valid prover") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 10).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        trie <- producer.create(entries.toMap)
        prover <- producer.getProver(trie)

        targetPath = entries.head._1
        proof <- prover.attestPath(targetPath)
      } yield expect(proof.isRight)
    }
  }

  test("deterministic root hash for same data") { implicit res =>
    res.withCurrent { implicit hasher =>
      for {
        entries <- (1 to 20).toList.traverse { i =>
          hasher.hash(s"value_$i").map(hash => Hex(hash.value) -> s"value_$i")
        }

        producer = StatelessMerklePatriciaProducer[IO]
        trie1 <- producer.create(entries.toMap)
        trie2 <- producer.create(entries.toMap)
      } yield expect(trie1.rootNode.digest == trie2.rootNode.digest)
    }
  }
}
