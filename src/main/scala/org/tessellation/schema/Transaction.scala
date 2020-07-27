package org.tessellation.schema

import cats.kernel.Semigroup
import cats.SemigroupK
import cats.implicits._
import org.tessellation.schema.EdgeHashType.EdgeHashType

trait Signable {
  def hash: String = ""
}

case class Address(address: String) extends AnyVal

object EdgeHashType extends Enumeration {
  type EdgeHashType = Value
  val AddressHash = Value
}

case class TypedEdgeHash(
  hashReference: String,
  hashType: EdgeHashType,
)

case class ObservationEdge(
  parents: Seq[TypedEdgeHash],
  data: TypedEdgeHash
) extends Signable

case class Id(hex: String)

case class HashSignature(signature: String, id: Id)

case class SignatureBatch(hash: String, signatures: Seq[HashSignature])

object SignatureBatch {
  implicit val semigroupInstance: Semigroup[SignatureBatch] =
    (x: SignatureBatch, y: SignatureBatch) => x.copy(signatures = x.signatures ++ y.signatures)
}

case class SignedObservationEdge(signatureBatch: SignatureBatch) extends Signable

object SignedObservationEdge {
  implicit val semigroupInstance: Semigroup[SignedObservationEdge] =
    (x: SignedObservationEdge, y: SignedObservationEdge) => x.copy(
      signatureBatch = x.signatureBatch |+| y.signatureBatch
    )
}

case class Edge[D](
  observationEdge: ObservationEdge,
  signedObservationEdge: SignedObservationEdge,
  data: D
)

object Edge {
  implicit val semigroupKInstance: SemigroupK[Edge] = new SemigroupK[Edge] {
    override def combineK[A](x: Edge[A], y: Edge[A]): Edge[A] = {
      x.copy(
        signedObservationEdge = x.signedObservationEdge |+| y.signedObservationEdge
      )
    }
  }
}

//trait HyperEdge extends Edge[_] with Signable {
//  val fibers: Seq[Fiber[_, _]]
//}
//
//case class EdgeBundle(fibers: Seq[Fiber[_, _]]) extends HyperEdge

/* TRANSACTION */

case class LastTransactionRef(prevHash: String, ordinal: Long)

case class TransactionEdgeData(amount: Long, lastTxRef: LastTransactionRef, fee: Option[Long] = None) extends Signable

case class Transaction(data: Edge[TransactionEdgeData]) extends Fiber[Edge[TransactionEdgeData], Edge[TransactionEdgeData]] {
  def unit: Hom[Edge[TransactionEdgeData], Edge[TransactionEdgeData]] = null // TODO
}

object Transaction {
  def apply(src: Address, dst: Address, amount: Long, lastTxRef: LastTransactionRef, fee: Option[Long] = None)(keyPair: String): Transaction = {
    val observationEdge = ObservationEdge(Seq(src, dst).map(_.address).map(TypedEdgeHash(_, EdgeHashType.AddressHash)), TypedEdgeHash("", EdgeHashType.AddressHash))
    val signedObservationEdge = SignedObservationEdge(SignatureBatch("abc", Seq(HashSignature("bcd", Id("aa")))))
    val data = TransactionEdgeData(amount, lastTxRef, fee)

    new Transaction(Edge[TransactionEdgeData](observationEdge, signedObservationEdge, data))
  }
}

/* BLOCK */

case class BlockEdgeData(fibers: Seq[Fiber[_, _]]) extends Signable

case class Block(edge: Edge[BlockEdgeData]) extends Bundle[Edge[BlockEdgeData], Edge[BlockEdgeData]](edge) {
  val fibers = edge.data.fibers
}

object Block {
  def apply(txs: List[Transaction]): Block = {
    val oe: ObservationEdge = ObservationEdge(Seq.empty, TypedEdgeHash("", EdgeHashType.AddressHash))
    val soe: SignedObservationEdge = SignedObservationEdge(SignatureBatch("", Seq(HashSignature("sig", Id("foo")))))
    val data = BlockEdgeData(txs)

    new Block(Edge[BlockEdgeData](oe, soe, data))
  }

  // TODO: Consider using monocle
  implicit val semigroupInstance: Semigroup[Block] =
    (x: Block, y: Block) => new Block(
      (x.edge <+> y.edge).copy(
        data = x.edge.data.copy(
          fibers = x.edge.data.fibers ++ y.edge.data.fibers
        )
      )
    )
}

/* SNAPSHOT */

case class Snapshot[A, B, C](convergedState: Seq[Hom[A, B]])
  extends Simplex[A, B, C](convergedState) {
    def combine(x: Snapshot[A, B, _], y: Snapshot[A, B, _]): Snapshot[A, B, C] =
      Snapshot(x.convergedState ++ y.convergedState)
}