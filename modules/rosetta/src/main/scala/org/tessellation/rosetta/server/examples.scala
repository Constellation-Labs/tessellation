package org.tessellation.rosetta.server

import java.math.BigInteger
import java.security.{KeyFactory, PrivateKey}
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.Async

import scala.collection.immutable.{SortedMap, SortedSet}
import scala.collection.mutable
import scala.util.Random
import org.tessellation.dag.domain.block.{BlockReference, DAGBlock}
import org.tessellation.dag.snapshot._
import org.tessellation.ext.cats.syntax.next.catsSyntaxNext
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.examples.{proofs, sampleHash}
import org.tessellation.schema.ID.Id
import org.tessellation.schema.address.Address
import org.tessellation.schema.balance.Balance
import org.tessellation.schema.height.{Height, SubHeight}
import org.tessellation.schema.peer.PeerId
import org.tessellation.schema.transaction.TransactionAmount.toAmount
import org.tessellation.schema.transaction._
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.{Signature, SignatureProof}
import org.tessellation.security.{SecurityProvider, hash}
import eu.timepit.refined.auto._
import eu.timepit.refined.types.numeric.NonNegLong
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPrivateKeySpec
import Transaction._
import cats.Order
/*
key 0:
public hex: bb2c6883916fc5fd703a69dbb8685cb3db1f6cbef131afe9e8fbd72faf7f6129e068f1ea48f2e4dd9f1297a5ce0acfe7b570b7a22a3452ef182f2533188270e6
private hex: d27337b4cf6b2badacead599ed7e4fa0a6436e0254631c91df0b28b69e9e4996
address: DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS

key 1:
public hex: c0cec83ddd60b3acbba15384c0774575e5003365dd8278c825b4ad8f9cd3272d5931dfea11e9f33bb3f57eb0840e455e31f31425b2b964b9b90ca7169d72e0c0
private hex: 373806942290579f36dc3bf553ab46b22ef388ce7c33a1a8d8350294c4aef6ca
address: DAG3JxRL8KuYAcdc58o4JdGAmNjJCN5woNFDsWEA
 */

class MockupData() {

  val genesis: GlobalSnapshot = mkGenesis(
    SortedMap(
      // DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS ) has balance (0), less than the minimum balance (10000000000000000)
      Address("DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS") -> Balance(NonNegLong(1000000000000000000L)),
      Address("DAG3JxRL8KuYAcdc58o4JdGAmNjJCN5woNFDsWEA") -> Balance(NonNegLong(1000000000000000000L)),
      Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh") -> Balance(NonNegLong(1000000000000000000L))
    )
  )
  var genesisHash = ""

  var currentBlock: GlobalSnapshot = genesis
  var currentBlockHash = Hash("")
  var height = Height(NonNegLong(0))
  var currentTimeStamp = System.currentTimeMillis()
  var timestamps: mutable.Map[Long, Long] = mutable.Map(0L -> currentTimeStamp)
  var allBlocks = List(genesis)
  var blockToHash: mutable.Map[GlobalSnapshot, Hash] = mutable.Map()
  var balances: mutable.Map[String, Long] = mutable.Map()
  genesis.info.balances.foreach { case (a, b) => balances(a.value.value) = b.value.value }

  // TODO: decimals on balance.
  def mkGenesis(balances: SortedMap[Address, Balance]): GlobalSnapshot =
    GlobalSnapshot(
      SnapshotOrdinal.MinValue,
      Height.MinValue,
      SubHeight.MinValue,
      Hash(sampleHash),
      SortedSet.empty,
      SortedMap.empty,
      SortedSet.empty,
      NonEmptyList.of(PeerId(Hex("peer1"))), // TODO
      GlobalSnapshotInfo(SortedMap.empty, SortedMap.empty, balances),
      GlobalSnapshotTips(SortedSet.empty[DeprecatedTip], SortedSet.empty[ActiveTip])
    )

  def mkNewTestTransaction[F[_]: KryoSerializer: SecurityProvider: Async](): Either[String, Unit] = {

    /*
    key 0:
public hex: bb2c6883916fc5fd703a69dbb8685cb3db1f6cbef131afe9e8fbd72faf7f6129e068f1ea48f2e4dd9f1297a5ce0acfe7b570b7a22a3452ef182f2533188270e6
private hex: d27337b4cf6b2badacead599ed7e4fa0a6436e0254631c91df0b28b69e9e4996
address: DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS

     */
    val priv = getPrivateKeyFromBytes("d27337b4cf6b2badacead599ed7e4fa0a6436e0254631c91df0b28b69e9e4996")
    val pub = Hex(
      "bb2c6883916fc5fd703a69dbb8685cb3db1f6cbef131afe9e8fbd72faf7f6129e068f1ea48f2e4dd9f1297a5ce0acfe7b570b7a22a3452ef182f2533188270e6"
    ).toPublicKey
    // TODO: Sign this?
    val tx = Signed(
      Transaction(
        Address("DAG3k3VihUWMjse9LE93jRqZLEuwGd6a5Ypk4zYS"),
        Address("DAG3JxRL8KuYAcdc58o4JdGAmNjJCN5woNFDsWEA"),
        TransactionAmount(100L),
        TransactionFee(0L),
        TransactionReference(
          TransactionOrdinal(0L),
          Hash(examples.sampleHash)
        ),
        TransactionSalt(Random.nextLong())
      ),
      proofs
    )
    acceptTransactionIncrementBlock(tx)
  }

  import Height._

  def acceptTransactionIncrementBlock[F[_]: KryoSerializer](newTransaction: Signed[Transaction]): Either[String, Unit] =
    currentBlock.hash.left.map(_.getMessage).flatMap { cbHash =>
      currentBlockHash = cbHash
      val newHeight = height.next
      val balances1 = currentBlock.info.balances
      val sourceBalanceOriginal = balances1(newTransaction.source)
      val desiredSpend = newTransaction.amount
      val value = sourceBalanceOriginal.minus(desiredSpend)
      println(s"Submit transaction values sourceBalance $sourceBalanceOriginal desiredSpend $desiredSpend minus $value")
      if (value.isLeft) {
        return Left(value.swap.toOption.get.getMessage)
      }
      val srcB = value.toOption.get
      val destinationBalance = balances1
        .getOrElse(newTransaction.destination, Balance(0L))

      val dstBRaw = destinationBalance
        .plus(desiredSpend)
      println(s"Submit transaction values destinationBalance $destinationBalance dstBRaw $dstBRaw")

      if (dstBRaw.isLeft) {
        return Left(dstBRaw.swap.toOption.get.getMessage)
      }

      val dstB = dstBRaw.toOption.get
      val newBalances = balances1.updated(newTransaction.source, srcB).updated(newTransaction.destination, dstB)
      val snapshot = GlobalSnapshot(
        SnapshotOrdinal(NonNegLong(0L)),
        newHeight,
        SubHeight.MinValue,
        cbHash,
        SortedSet(
          BlockAsActiveTip(
            Signed(
              DAGBlock(
                NonEmptyList(BlockReference(newHeight, hash.ProofsHash("")), List.empty),
                NonEmptySet(newTransaction, SortedSet.empty[Signed[Transaction]])(
                  Order.fromOrdering(new Signed.SignedOrdering[Transaction]())
                )
              ),
              examples.proofs
            ),
            NonNegLong(0)
          )
        ),
        SortedMap.empty,
        SortedSet(RewardTransaction(Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh"), TransactionAmount(100L))),
        NonEmptyList.of(PeerId(Hex("peer1"))),
        currentBlock.info.copy(balances = newBalances),
        genesis.tips
      )
      snapshot.info.balances.foreach { case (a, b) => balances(a.value.value) = b.value.value }

      snapshot.hash.map { h =>
        height = newHeight
        currentBlock = snapshot
        currentBlockHash = h
        currentTimeStamp = System.currentTimeMillis()
        timestamps(height.value) = currentTimeStamp
        allBlocks = allBlocks.appended(currentBlock)
        blockToHash(currentBlock) = h
        println(s"Accepted transaction with new height {$height} ${h.value}")
      }.left.map(_.getMessage)
    }

  def getPrivateKeyFromBytes(privkeyHex: String) = {
    val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
    val kf = KeyFactory.getInstance("ECDSA", new BouncyCastleProvider)
    val priv = new BigInteger(privkeyHex, 16)
    val pubKeySpec = new ECPrivateKeySpec(priv, spec)
    val pk = kf.generatePrivate(pubKeySpec).asInstanceOf[PrivateKey]
    pk
  }

}

// TODO: Move this into class passed into rosetta api for dependencies
object MockData {
  val mockup = new MockupData()
}

object examples {

//  import SignatureProof._
//  import Signed._



  // workaround to deal with ambiguous implicits
  val spOrder = Order.fromOrdering(SignatureProof.OrderingInstance)

  val proofs: NonEmptySet[SignatureProof] =
    NonEmptySet(SignatureProof(Id(Hex("a")), Signature(Hex("a"))), SortedSet.empty[SignatureProof])(
      spOrder
  )

  val address = "DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh"

  val transaction = Signed(
    Transaction(
      Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
      Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQefgh"),
      TransactionAmount(10L),
      TransactionFee(3L),
      TransactionReference(
        TransactionOrdinal(2L),
        Hash("someHash")
      ),
      TransactionSalt(1234L)
    ),
    proofs
  )

  val genesis = GlobalSnapshot.mkGenesis(Map.empty)

  val sampleHash = "f0e4c2f76c58916ec258f246851bea091d14d4247a2fc3e18694461b1816e13b"

  val snapshot = GlobalSnapshot(
    SnapshotOrdinal(NonNegLong(0L)),
    Height.MinValue,
    SubHeight.MinValue,
    Hash(""),
    SortedSet(
      BlockAsActiveTip(
        Signed(
          DAGBlock(
            NonEmptyList(BlockReference(Height(NonNegLong(0L)), hash.ProofsHash("")), List.empty),
            NonEmptySet(transaction, SortedSet.empty[Signed[Transaction]])(
              Order.fromOrdering(new Signed.SignedOrdering[Transaction]())
            )
          ),
          proofs
        ),
        NonNegLong(0)
      )
    ),
    SortedMap.empty,
    SortedSet.empty,
    NonEmptyList.of(PeerId(Hex("peer1"))),
    genesis.info,
    genesis.tips
  )

}
