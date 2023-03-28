package org.tessellation.merkletree

import cats.data.NonEmptyList
import cats.syntax.eq._

import org.tessellation.security.hash.Hash

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers

object MerkleTreeSuite extends SimpleIOSuite with Checkers {

  implicit val hashesGen = Gen.nonEmptyListOf[Hash](Hash.arbitrary.arbitrary).map(NonEmptyList.fromListUnsafe)

  implicit val fixedHashesGen = Gen.listOfN(10, Hash.arbitrary.arbitrary).map(NonEmptyList.fromListUnsafe)

  pureTest("tree created from one hash is possible") {
    val hash = Hash("a")
    val mt = MerkleTree.from(NonEmptyList.one(hash))

    val expected = MerkleRoot(mt.leafCount, MerkleTree.hashLeaf(hash))

    expect(mt.getRoot === expected)
  }

  pureTest("cannot find path for one leaf") {
    val mt = MerkleTree.from(NonEmptyList.one(Hash("a")))

    expect.eql(true, mt.findPath(0).isEmpty)
  }

  test("can find path when tree has many leaves") {
    forall(fixedHashesGen) {
      case hashes =>
        val mt = MerkleTree.from(hashes)

        expect.eql(true, mt.findPath(0).isDefined)
    }
  }

  test("cannot find path for index out of range") {
    forall(fixedHashesGen) {
      case hashes =>
        val mt = MerkleTree.from(hashes)

        expect.eql(true, mt.findPath(11).isEmpty)
        expect.eql(true, mt.findPath(-1).isEmpty)
    }
  }

  test("can verify good path") {
    forall(fixedHashesGen) {
      case hashes =>
        val mt = MerkleTree.from(hashes)

        expect.eql(Some(true), mt.findPath(0).map(_.verify(hashes.head)))
    }
  }

  test("veryfing bad path fails") {
    forall(fixedHashesGen) {
      case hashes =>
        val mt = MerkleTree.from(hashes)

        expect.eql(Some(false), mt.findPath(0).map(_.verify(Hash("dummy"))))
    }
  }

  pureTest("forgery attack with duplicated hashes") {
    val hashes = NonEmptyList.fromListUnsafe(('a' to 'd').map(_.toString).map(Hash(_)).toList)
    val doubled = hashes ++ List(Hash("c"), Hash("d"))

    val mt1 = MerkleTree.from(hashes)
    val mt2 = MerkleTree.from(doubled)

    expect(mt1 =!= mt2)
    expect(mt1.getRoot =!= mt2.getRoot)
  }

  pureTest("forgery attack with duplicated last hash to make balanced tree") {
    val hashes = NonEmptyList.fromListUnsafe(('a' to 'c').map(_.toString).map(Hash(_)).toList)
    val doubled = hashes ++ List(Hash("c"))

    val mt1 = MerkleTree.from(hashes)
    val mt2 = MerkleTree.from(doubled)

    expect(mt1 =!= mt2)
  }

  pureTest("second preimage attack") {
    val hashes = NonEmptyList.of(Hash("a"), Hash("b"), Hash("c"), Hash("d"))

    val mt1 = MerkleTree.from(hashes)

    val n1 = mt1.nodes.toList(4)
    val n2 = mt1.nodes.toList(5)

    val mt2 = MerkleTree.from(NonEmptyList.of(n1, n2))

    expect(mt1 =!= mt2)
    expect(mt1.getRoot =!= mt2.getRoot)
  }

  pureTest("ensure that calculation is stable") {
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

    val result = MerkleTree.from(hashes)

    expect(result === expected)
  }
}
