package org.tessellation.merkletree

import cats.data.NonEmptyList
import cats.syntax.eq._

import org.tessellation.security.hash.Hash

import org.scalacheck.Gen
import weaver._
import weaver.scalacheck.Checkers

object MerkleTreeSuite extends SimpleIOSuite with Checkers {

  implicit val hashesGen = Gen.nonEmptyListOf[Hash](Hash.arbitrary.arbitrary).map(NonEmptyList.fromListUnsafe)

  implicit val fixedHashesGen = Gen.listOfN(10, Hash.arbitrary.arbitrary).map(NonEmptyList.fromListUnsafe)

  pureTest("tree created from one hash is possible") {
    val hash = Hash("a")
    val mt = MerkleTree.from(NonEmptyList.one(hash))

    val expected = MerkleTree.hashLeaf(hash)

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
}
