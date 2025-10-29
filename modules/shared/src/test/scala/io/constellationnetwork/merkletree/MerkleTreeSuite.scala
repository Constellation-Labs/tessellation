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
        MerkleTree.from[IO](hashes).map { mt =>
          val path = mt.findPath(0)
          expect.eql(true, path.isDefined)
        }
    }
  }

  test("cannot find path for index out of range") {
    forall(fixedHashesGen) {
      case hashes =>
        MerkleTree.from[IO](hashes).map { mt =>
          expect.eql(true, mt.findPath(11).isEmpty).and(expect.eql(true, mt.findPath(-1).isEmpty))
        }
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
}
