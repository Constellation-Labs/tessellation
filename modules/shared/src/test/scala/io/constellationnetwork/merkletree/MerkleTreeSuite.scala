package io.constellationnetwork.merkletree

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.traverse._

import io.constellationnetwork.security.hash.Hash

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers

object MerkleTreeSuite extends SimpleIOSuite with Checkers {

  implicit val hashesGen: Gen[NonEmptyList[Hash]] = Gen.nonEmptyListOf[Hash](Hash.arbitrary.arbitrary).map(NonEmptyList.fromListUnsafe)

  implicit val fixedHashesGen: Gen[NonEmptyList[Hash]] = Gen.listOfN(10, Hash.arbitrary.arbitrary).map(NonEmptyList.fromListUnsafe)

  test("tree created from one hash is possible") {
    val hash = Hash("a")
    for {
      mt <- MerkleTree.from[IO](NonEmptyList.one(hash))
      expectedHash <- MerkleTree.hashLeaf[IO](hash)
      expected = MerkleRoot(mt.leafCount, expectedHash)
    } yield expect(mt.getRoot === expected)
  }

  test("path for one leaf is the leaf itself") {
    for {
      mt <- MerkleTree.from[IO](NonEmptyList.one(Hash("a")))
      leaf <- MerkleTree.hashLeaf[IO](Hash("a"))
      path <- mt.findPath[IO](Hash("a"))
    } yield expect.eql(Some(Proof(NonEmptyList.one(ProofEntry(leaf, Right(leaf))))), path)
  }

  test("can find path when tree has many leaves") {
    forall(fixedHashesGen) {
      case hashes =>
        for {
          mt <- MerkleTree.from[IO](hashes)
          path <- mt.findPath(0)
        } yield expect.eql(true, path.isDefined)
    }
  }

  test("cannot find path for index out of range") {
    forall(fixedHashesGen) {
      case hashes =>
        for {
          mt <- MerkleTree.from[IO](hashes)
          path11 <- mt.findPath(11)
          path_1 <- mt.findPath(-1)
        } yield expect.eql(true, path11.isEmpty).and(expect.eql(true, path_1.isEmpty))
    }
  }

  test("can verify path for one leaf") {
    for {
      mt <- MerkleTree.from[IO](NonEmptyList.one(Hash("a")))
      path <- mt.findPath[IO](Hash("a"))
      verified <- path.get.verify[IO](Hash("a"))
    } yield expect.eql(true, verified)
  }

  test("can verify good path") {
    forall(fixedHashesGen) {
      case hashes =>
        for {
          mt <- MerkleTree.from[IO](hashes)
          path <- mt.findPath[IO](hashes.head)
          verified <- path.traverse(_.verify[IO](hashes.head))
        } yield expect.eql(Some(true), verified)
    }
  }

  test("veryfing bad path fails") {
    forall(fixedHashesGen) {
      case hashes =>
        for {
          mt <- MerkleTree.from[IO](hashes)
          path <- mt.findPath[IO](hashes.head)
          verified <- path.traverse(_.verify[IO](Hash("dummy")))
        } yield expect.eql(Some(false), verified)
    }
  }

  test("forgery attack with duplicated hashes") {
    val hashes = NonEmptyList.fromListUnsafe(('a' to 'd').map(_.toString).map(Hash(_)).toList)
    val doubled = hashes ++ List(Hash("c"), Hash("d"))

    for {
      mt1 <- MerkleTree.from[IO](hashes)
      mt2 <- MerkleTree.from[IO](doubled)
    } yield expect(mt1 =!= mt2).and(expect(mt1.getRoot =!= mt2.getRoot))
  }

  test("forgery attack with duplicated last hash to make balanced tree") {
    val hashes = NonEmptyList.fromListUnsafe(('a' to 'c').map(_.toString).map(Hash(_)).toList)
    val doubled = hashes ++ List(Hash("c"))

    for {
      mt1 <- MerkleTree.from[IO](hashes)
      mt2 <- MerkleTree.from[IO](doubled)
    } yield expect(mt1 =!= mt2).and(expect(mt1.getRoot =!= mt2.getRoot))
  }

  test("second preimage attack") {
    val hashes = NonEmptyList.of(Hash("a"), Hash("b"), Hash("c"), Hash("d"))

    for {
      mt1 <- MerkleTree.from[IO](hashes)
      n1 = mt1.nodes.toList(4)
      n2 = mt1.nodes.toList(5)
      mt2 <- MerkleTree.from[IO](NonEmptyList.of(n1, n2))
    } yield expect(mt1 =!= mt2).and(expect(mt1.getRoot =!= mt2.getRoot))
  }

  test("ensure that calculation is stable") {
    val hashes = NonEmptyList.of(Hash("a"), Hash("b"), Hash("c"), Hash("d"))
    val expected = MerkleTree(
      4,
      NonEmptyList.of(
        Hash("022a6979e6dab7aa5ae4c3e5e45f7e977112a7e63593820dbec1ec738a24f93c"),
        Hash("57eb35615d47f34ec714cacdf5fd74608a5e8e102724e80b24b287c0c27b6a31"),
        Hash("597fcb31282d34654c200d3418fca5705c648ebf326ec73d8ddef11841f876d8"),
        Hash("d070dc5b8da9aea7dc0f5ad4c29d89965200059c9a0ceca3abd5da2492dcb71d"),
        Hash("4c64254e6636add7f281ff49278beceb26378bd0021d1809974994e6e233ec35"),
        Hash("40e2511a6323177e537acb2e90886e0da1f84656fd6334b89f60d742a3967f09"),
        Hash("9dc1674ae1ee61c90ba50b6261e8f9a47f7ea07d92612158edfe3c2a37c6d74c")
      )
    )

    MerkleTree.from[IO](hashes).map { result =>
      expect(result === expected)
    }
  }

  test("stack safety with large input (10,000 leaves)") {
    val largeHashes = NonEmptyList.fromListUnsafe(
      (1 to 10000).map(i => Hash(s"hash_$i")).toList
    )

    for {
      startTime <- IO(System.currentTimeMillis())
      mt <- MerkleTree.from[IO](largeHashes)
      endTime <- IO(System.currentTimeMillis())
      duration = endTime - startTime
    } yield
      expect(mt.leafCount.value === 10000)
        .and(expect(mt.nodes.length > 10000)) // Should have more nodes than leaves
        .and(expect(duration < 30000)) // Should complete within 30 seconds
  }

  /*
  test("stack safety with very large input (100,000 leaves)") {
    val veryLargeHashes = NonEmptyList.fromListUnsafe(
      (1 to 100000).map(i => Hash(s"hash_$i")).toList
    )

    for {
      startTime <- IO(System.currentTimeMillis())
      mt <- MerkleTree.from[IO](veryLargeHashes)
      endTime <- IO(System.currentTimeMillis())
      duration = endTime - startTime
    } yield
      expect(mt.leafCount.value === 100000)
        .and(expect(mt.nodes.length > 100000)) // Should have more nodes than leaves
        .and(expect(duration < 300000)) // Should complete within 5 minutes
  }

  test("stack safety with extremely large input (1,000,000 leaves)") {
    val extremelyLargeHashes = NonEmptyList.fromListUnsafe(
      (1 to 1000000).map(i => Hash(s"hash_$i")).toList
    )

    for {
      startTime <- IO(System.currentTimeMillis())
      mt <- MerkleTree.from[IO](extremelyLargeHashes)
      endTime <- IO(System.currentTimeMillis())
      duration = endTime - startTime
    } yield
      expect(mt.leafCount.value === 1000000)
        .and(expect(mt.nodes.length > 1000000)) // Should have more nodes than leaves
        .and(expect(duration < 1800000)) // Should complete within 30 minutes
  }

  test("performance comparison with different tree sizes") {
    val sizes = List(100, 1000, 10000, 100000)

    def testSize(size: Int): IO[(Int, Long)] = {
      val hashes = NonEmptyList.fromListUnsafe(
        (1 to size).map(i => Hash(s"hash_$i")).toList
      )

      for {
        startTime <- IO(System.currentTimeMillis())
        _ <- MerkleTree.from[IO](hashes)
        endTime <- IO(System.currentTimeMillis())
      } yield (size, endTime - startTime)
    }

    for {
      results <- sizes.traverse(testSize)
    } yield
      // Verify all sizes completed successfully
      expect(results.length === 4)
        .and(expect(results.forall(_._2 > 0))) // All took some time
        .and(expect(results.forall(_._2 < 300000))) // None took more than 5 minutes
  }
   */
}
