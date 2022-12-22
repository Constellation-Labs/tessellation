package org.tessellation.schema

import cats.data.NonEmptySet

import scala.collection.immutable.SortedSet

import org.tessellation.generators._
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.cluster.SessionToken
import org.tessellation.schema.generation.Generation
import org.tessellation.schema.node.NodeState
import org.tessellation.schema.peer._
import org.tessellation.schema.security.generators._
import org.tessellation.schema.security.hash.Hash
import org.tessellation.schema.security.hex.Hex
import org.tessellation.schema.security.signature.Signed
import org.tessellation.schema.security.signature.signature.{Signature, SignatureProof}
import org.tessellation.schema.transaction._

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.api.{RefType, Validate}
import eu.timepit.refined.refineV
import eu.timepit.refined.scalacheck.numeric._
import eu.timepit.refined.types.numeric.{NonNegLong, PosLong}
import org.scalacheck.Gen.Choose
import org.scalacheck.{Arbitrary, Gen}

object generators {

  def chooseNumRefined[F[_, _], T: Numeric: Choose, P](min: F[T, P], max: F[T, P], specials: F[T, P]*)(
    implicit rt: RefType[F],
    v: Validate[T, P]
  ): Gen[F[T, P]] =
    Gen
      .chooseNum(rt.unwrap(min), rt.unwrap(max), specials.map(rt.unwrap): _*)
      .filter(v.isValid)
      .map(rt.unsafeWrap)

  val peerIdGen: Gen[PeerId] =
    nesGen(str => PeerId(Hex(str)))

  val peerResponsivenessGen: Gen[PeerResponsiveness] =
    Gen.oneOf(Responsive, Unresponsive)

  val idGen: Gen[Id] =
    nesGen(str => Id(Hex(str)))

  val hostGen: Gen[Host] =
    for {
      a <- Gen.chooseNum(1, 255)
      b <- Gen.chooseNum(1, 255)
      c <- Gen.chooseNum(1, 255)
      d <- Gen.chooseNum(1, 255)
    } yield Host.fromString(s"$a.$b.$c.$d").get

  val portGen: Gen[Port] =
    Gen.chooseNum(1, 65535).map(Port.fromInt(_).get)

  val nodeStateGen: Gen[NodeState] =
    Gen.oneOf(NodeState.all)

  val generationGen: Gen[Generation] =
    Arbitrary.arbitrary[PosLong].map(Generation(_))

  val peerGen: Gen[Peer] =
    for {
      i <- peerIdGen
      h <- hostGen
      p <- portGen
      p2 <- portGen
      s <- generationGen.map(SessionToken.apply)
      st <- nodeStateGen
      r <- peerResponsivenessGen
    } yield Peer(i, h, p, p2, s, st, r)

  def peersGen(n: Option[Int] = None): Gen[Set[Peer]] =
    n.map(Gen.const).getOrElse(Gen.chooseNum(1, 20)).flatMap { n =>
      Gen.sequence[Set[Peer], Peer](Array.tabulate(n)(_ => peerGen))
    }

  val addressGen: Gen[Address] =
    for {
      end <- Gen.stringOfN(36, base58CharGen)
      par = end.filter(_.isDigit).map(_.toString.toInt).sum % 9
    } yield Address(refineV[DAGAddressRefined].unsafeFrom(s"DAG$par$end"))

  val balanceGen: Gen[Balance] =
    Arbitrary.arbitrary[NonNegLong].map(Balance(_))

  val transactionAmountGen: Gen[TransactionAmount] = Arbitrary.arbitrary[PosLong].map(TransactionAmount(_))
  val transactionFeeGen: Gen[TransactionFee] = Arbitrary.arbitrary[NonNegLong].map(TransactionFee(_))
  val transactionOrdinalGen: Gen[TransactionOrdinal] = Arbitrary.arbitrary[NonNegLong].map(TransactionOrdinal(_))

  val transactionReferenceGen: Gen[TransactionReference] =
    for {
      ordinal <- transactionOrdinalGen
      hash <- Arbitrary.arbitrary[Hash]
    } yield TransactionReference(ordinal, hash)

  val transactionSaltGen: Gen[TransactionSalt] = Gen.long.map(TransactionSalt(_))

  val transactionGen: Gen[Transaction] =
    for {
      src <- addressGen
      dst <- addressGen
      txnAmount <- transactionAmountGen
      txnFee <- transactionFeeGen
      txnReference <- transactionReferenceGen
      txnSalt <- transactionSaltGen
    } yield Transaction(src, dst, txnAmount, txnFee, txnReference, txnSalt)

  val signatureGen: Gen[Signature] = nesGen(str => Signature(Hex(str)))

  val signatureProofGen: Gen[SignatureProof] =
    for {
      id <- idGen
      signature <- signatureGen
    } yield SignatureProof(id, signature)

  def signedOf[A](valueGen: Gen[A]): Gen[Signed[A]] =
    for {
      txn <- valueGen
      signatureProof <- Gen.listOfN(3, signatureProofGen).map(l => NonEmptySet.fromSetUnsafe(SortedSet.from(l)))
    } yield Signed(txn, signatureProof)

  val signedTransactionGen: Gen[Signed[Transaction]] = signedOf(transactionGen)

}
