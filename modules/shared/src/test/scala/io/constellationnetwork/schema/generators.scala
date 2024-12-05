package io.constellationnetwork.schema

import cats.data.NonEmptySet
import cats.syntax.all._

import scala.collection.immutable.SortedSet

import io.constellationnetwork.generators.nesGen
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema.address.{Address, DAGAddressRefined}
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.cluster.{ClusterSessionToken, SessionToken}
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.generation.Generation
import io.constellationnetwork.schema.node.NodeState
import io.constellationnetwork.schema.peer._
import io.constellationnetwork.schema.swap.CurrencyId
import io.constellationnetwork.schema.tokenLock._
import io.constellationnetwork.schema.transaction._
import io.constellationnetwork.security.generators._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.api.{RefType, Validate}
import eu.timepit.refined.auto._
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
    hexGen(128).map(Id(_))

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
      cs <- generationGen.map(ClusterSessionToken.apply)
      s <- generationGen.map(SessionToken.apply)
      st <- nodeStateGen
      r <- peerResponsivenessGen
      j <- Arbitrary.arbitrary[Hash]
    } yield Peer(i, h, p, p2, cs, s, st, r, j)

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
  val tokenLockAmountGen: Gen[TokenLockAmount] = Arbitrary.arbitrary[PosLong].map(TokenLockAmount(_))

  // total supply is lower than Long.MaxValue so generated fee needs to be limited to avoid cases which won't happen
  val feeMaxVal: TransactionFee = TransactionFee(NonNegLong(99999999_00000000L))

  val transactionFeeGen: Gen[TransactionFee] =
    chooseRefinedNum(NonNegLong(0L), feeMaxVal.value).map(TransactionFee(_))

  val transactionOrdinalGen: Gen[TransactionOrdinal] = Arbitrary.arbitrary[NonNegLong].map(TransactionOrdinal(_))
  val tokenLockOrdinalGen: Gen[TokenLockOrdinal] = Arbitrary.arbitrary[NonNegLong].map(TokenLockOrdinal(_))

  val transactionReferenceGen: Gen[TransactionReference] =
    for {
      ordinal <- transactionOrdinalGen
      hash <- Arbitrary.arbitrary[Hash]
    } yield TransactionReference(ordinal, hash)

  val tokenLockReferenceGen: Gen[TokenLockReference] =
    for {
      ordinal <- tokenLockOrdinalGen
      hash <- Arbitrary.arbitrary[Hash]
    } yield TokenLockReference(ordinal, hash)

  val transactionSaltGen: Gen[TransactionSalt] = Gen.long.map(TransactionSalt(_))

  def hexGen(n: Int): Gen[Hex] = Gen.stringOfN(n, Gen.hexChar).map(Hex(_))

  val transactionGen: Gen[Transaction] =
    for {
      src <- addressGen
      dst <- addressGen
      txnAmount <- transactionAmountGen
      txnFee <- transactionFeeGen
      txnReference <- transactionReferenceGen
      txnSalt <- transactionSaltGen
    } yield Transaction(src, dst, txnAmount, txnFee, txnReference, txnSalt)

  val tokenLockGen: Gen[TokenLock] =
    for {
      src <- addressGen
      metagraphAddress <- addressGen
      tokenLockAmount <- tokenLockAmountGen
      tokenLockReference <- tokenLockReferenceGen
      currencyId = CurrencyId(metagraphAddress).some
    } yield TokenLock(src, tokenLockAmount, tokenLockReference, currencyId, EpochProgress.MaxValue)

  val signatureGen: Gen[Signature] =
    /* BouncyCastle encodes ECDSA with ASN.1 DER which which is variable length. That generator should be changed to
       fixed length instead of range when we switch to SHA512withPLAIN-ECDSA.
     */
    Gen
      .chooseNum(140, 144)
      .flatMap(hexGen)
      .map(Signature(_))

  val signatureProofGen: Gen[SignatureProof] =
    for {
      id <- idGen
      signature <- signatureGen
    } yield SignatureProof(id, signature)

  def signatureProofN(n: Int): Gen[NonEmptySet[SignatureProof]] =
    Gen.listOfN(n, signatureProofGen).map(l => NonEmptySet.fromSetUnsafe(SortedSet.from(l)))

  def signedOf[A](valueGen: Gen[A]): Gen[Signed[A]] =
    for {
      txn <- valueGen
      signatureProof <- signatureProofN(3)
    } yield Signed(txn, signatureProof)

  def signedOfN[A](valueGen: Gen[A], minProofs: Int, maxProofs: Int): Gen[Signed[A]] =
    for {
      value <- valueGen
      numProofs <- Gen.chooseNum(minProofs, maxProofs)
      proofs <- signatureProofN(numProofs)
    } yield Signed(value, proofs)

  val signedTransactionGen: Gen[Signed[Transaction]] = signedOf(transactionGen)
  val signedTokenLockGen: Gen[Signed[TokenLock]] = signedOf(tokenLockGen)

  val snapshotOrdinalGen: Gen[SnapshotOrdinal] =
    Arbitrary.arbitrary[NonNegLong].map(SnapshotOrdinal(_))
}
