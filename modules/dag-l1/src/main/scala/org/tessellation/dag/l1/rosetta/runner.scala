package org.tessellation.dag.l1.rosetta
import cats.MonadThrow
import cats.data.NonEmptyList
import cats.effect.Async
import cats.implicits.toFunctorOps

import scala.util.Try

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.model
import org.tessellation.rosetta.server.model.dag.decoders._
import org.tessellation.rosetta.server.model.dag.schema._
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.transaction.{Transaction => DAGTransaction, _}
import org.tessellation.security.SecurityProvider
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof

import eu.timepit.refined.refineV
import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Decoder
import org.http4s._
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl

import Signed._
import model._

case class LastTransactionResponse(
  constructionMetadataResponseMetadata: Option[ConstructionMetadataResponseMetadata],
  suggestedFee: Option[Long]
)

case class SnapshotInfo(
  hash: String,
  height: Long,
  timestamp: Long
)

case class AccountBlockResponse(
  amount: Long,
  snapshotHash: String,
  height: Long
)

case class BlockSearchRequest(
  isOr: Boolean,
  isAnd: Boolean,
  addressOpt: Option[String],
  networkStatus: Option[String],
  limit: Option[Long],
  offset: Option[Long],
  transactionHash: Option[String],
  maxBlock: Option[Long]
)

case class TransactionWithBlockHash(
  transaction: Signed[DAGTransaction],
  blockHash: String,
  blockIndex: Long
)

case class BlockSearchResponse(
  transactions: List[TransactionWithBlockHash],
  total: Long,
  nextOffset: Option[Long]
)

import cats.syntax.flatMap._

//import io.circe.generic.extras.Configuration
//import io.circe.generic.extras.auto._

object Rosetta {

  // block_added or block_removed
  def convertSnapshotsToBlockEvents[F[_]: KryoSerializer: SecurityProvider: Async](
    gs: List[GlobalSnapshot],
    blockEventType: String
  ): Either[String, List[BlockEvent]] = {
    val value = gs.map(s => s.hash.map(h => List(h -> s)))
    val hashed = Util.reduceListEither(value).left.map(_.getMessage)
    hashed.map { ls =>
      ls.map {
        case (hash, s) =>
          BlockEvent(s.height.value.value, BlockIdentifier(s.height.value.value, hash.value), blockEventType)
      }
    }
  }

  import cats.effect._

  def convertSignature[F[_]: KryoSerializer: SecurityProvider: Async](
    signature: List[Signature]
  ): F[Either[model.Error, NonEmptyList[SignatureProof]]] = {
    val value = signature.map { s =>
      // TODO: Handle hex validation here
      val value1 = Async[F].delay(Util.getPublicKeyFromBytes(Hex(s.publicKey.hexBytes).toBytes))
      // TODO: TRy here .asInstanceOf[JPublicKey])}.toEither
//      .left.map(e => makeErrorCodeMsg(0, e.getMessage))
      value1.map { pk =>
        s.signatureType match {
          case "ecdsa" =>
            Right({
              // TODO, validate curve type.
              SignatureProof(pk.toId, org.tessellation.security.signature.signature.Signature(Hex(s.hexBytes)))
            })
          case x => Left(makeErrorCodeMsg(10, s"${x} not supported"))
        }
      }
    }
    val zero: Either[model.Error, List[SignatureProof]] = Right[model.Error, List[SignatureProof]](List())
    value
      .foldLeft(Async[F].delay(zero)) {
        case (agga, nextFEither) =>
          val inner2 = agga.flatMap { agg =>
            val inner = nextFEither.map { eitherErrSigProof =>
              val res = eitherErrSigProof.map { sp =>
                agg.map(ls => ls ++ List(sp))
              }
              var rett: Either[model.Error, List[SignatureProof]] = null
              // TODO: Better syntax
              if (res.isRight) {
                val value2 = res.toOption.get
                val zz = value2.getOrElse(List())
                if (zz.nonEmpty) {
                  rett = Right(zz)
                } else {
                  rett = Left(value2.swap.toOption.get)
                }
              } else {
                rett = Left(res.swap.toOption.get)
              }
              rett
            }
            inner
          }
          inner2
      }
      .map(_.map(q => NonEmptyList(q.head, q.tail)))
  }

  // TODO: From config / and/or arg
  val endpoints = Map(
    "mainnet" -> "localhost:8080",
    "testnet" -> "localhost:8080"
  )
  val signatureTypeEcdsa = "ecdsa"

  val errorCodes = Map(
    // TODO: Need a 'retriable' field here for network options.
    0 -> ("Unknown internal error", "Unable to determine error type"),
    1 -> ("Unsupported network", "Unable to route request to specified network or network not yet supported"),
    2 -> ("Unknown hash", "Unable to find reference to hash"),
    3 -> ("Invalid request", "Unable to decode request"),
    4 -> ("Malformed address", "Address is invalidly formatted or otherwise un-parseable"),
    5 -> ("Block service failure", "Request to block service failed"),
    6 -> ("Unknown address", "No known reference to address"),
    7 -> ("Unknown transaction", "No known reference to transaction"),
    8 -> ("Unsupported operation", "Operation not supported"),
    9 -> ("Malformed transaction", "Unable to decode transaction"),
    10 -> ("Unsupported signature type", "Cannot translate signature"),
    11 -> ("Hex decode failure", "Unable to parse hex to bytes"),
    12 -> ("Unsupported curve type", "Curve type not available for use"),
    13 -> ("L1 service failure", "Request to L1 service failed"),
    14 -> ("Deserialization failure", "Unable to deserialize class to kryo type"),
    15 -> ("Malformed request", "Missing required information or necessary fields")
  )

  def makeErrorCode(code: Int, retriable: Boolean = true, details: Option[ErrorDetails] = None): model.Error = {
    val (message, description) = errorCodes(code)
    Error(
      code,
      message,
      Some(description),
      retriable,
      details
    )
  }

  def makeErrorCodeMsg(code: Int, message: String, retriable: Boolean = true): model.Error =
    makeErrorCode(code, retriable, Some(ErrorDetails(List(ErrorDetailKeyValue("exception", message)))))

  implicit class RefinedRosettaRequestDecoder[F[_]: JsonDecoder: MonadThrow](req: Request[F]) extends Http4sDsl[F] {
    import cats.syntax.applicativeError._
    import cats.syntax.flatMap._

    def decodeRosetta[A: Decoder](f: A => F[Response[F]]): F[Response[F]] =
      req.asJsonDecode[A].attempt.flatMap {
        case Left(e) =>
          Option(e.getCause) match {
            case Some(c) =>
              InternalServerError(
                makeErrorCode(
                  3,
                  retriable = false,
                  Some(ErrorDetails(List(ErrorDetailKeyValue("exception", c.getMessage))))
                )
              )
            case _ => InternalServerError(makeErrorCode(3, retriable = false))
          }
        case Right(a) => {
          println("Incoming rosetta request " + a)
          // TODO: Wrap either left map in a generic error handle implicit.
          val res = Try { f(a) }.toEither.left
            .map(t => InternalServerError(makeErrorCodeMsg(0, t.getMessage, retriable = true)))
            .merge
            .handleErrorWith(t => {
              InternalServerError(makeErrorCodeMsg(0, t.getMessage, retriable = true))
            })
          res.flatMap { r =>
            r.asJson.map { j =>
              println(j.toString())
              r
            }
          }
        }
      }
  }

  // TODO Confirm 1e8 is correct multiplier
  val DagCurrency: Currency = Currency("DAG", 8, None)

  def reverseTranslateTransaction(t: DAGTransaction): Unit = {}

  val dagCurrencyTransferType = "TRANSFER"

  def operationsToDAGTransaction(
    src: String,
    fee: Long,
    operations: List[Operation],
    parentHash: String,
    parentOrdinal: Long,
    salt: Option[Long]
  ): Either[String, DAGTransaction] = {
    // TODO: Same question here as below, one or two operations?
    val operationPositive = operations.filter(_.amount.exists(_.value.toLong > 0)).head
    val amount = operationPositive.amount.get.value.toLong
    val destination = operationPositive.account.get.address
    // TODO: Validate at higher level
    import DAGAddressRefined._
    /*
    // e1
    .left.map{map the error into a response code type}
    .map{
    e2.map{
    e3.msp{
    e4.map{
    => result_Type(e1a,e2,e3a,0

    }.merge
    .merge
    .merge

     */
    for {
      // Option[String] if the result is None, it won't execute the later maps.
      srcA <- refineV[DAGAddressRefined](src) // Either[String error, address thats been validated]
      destinationA <- refineV[DAGAddressRefined](destination)
      amountA <- PosLong.from(amount) // Either[Error, Long validated amount]
      feeA <- NonNegLong.from(fee)
      parentOrdinalA <- NonNegLong.from(parentOrdinal)
    } yield {
      DAGTransaction(
        Address(srcA),
        Address(destinationA),
        TransactionAmount(amountA),
        TransactionFee(feeA),
        TransactionReference(TransactionOrdinal(parentOrdinalA), Hash(parentHash)),
        TransactionSalt(salt.getOrElse(0L))
      )
    }
  }

  // TODO: Translate function for reward transactions
  def translateDAGTransactionToOperations(
    tx: DAGTransaction,
    status: String,
    includeNegative: Boolean = true,
    ignoreStatus: Boolean = false
  ): List[Operation] = {
    // TODO: Huge question, do we need to represent another operation for the
    // negative side of this transaction?
    // if so remove list type.
    val operation = Operation(
      OperationIdentifier(if (includeNegative) 1 else 0, None),
      None,
      dagCurrencyTransferType, // TODO: Replace with enum
      if (ignoreStatus) None else Some(status), // TODO: Replace with enum
      Some(AccountIdentifier(tx.destination.value.value, None, None)),
      Some(
        Amount(
          tx.amount.value.value.toString,
          DagCurrency,
          None
        )
      ),
      None,
      None
    )
    val operationNegative = Operation(
      OperationIdentifier(0, None),
      None,
      dagCurrencyTransferType, // TODO: Replace with enum
      if (ignoreStatus) None else Some(status), // TODO: Replace with enum
      Some(AccountIdentifier(tx.source.value.value, None, None)),
      Some(
        Amount(
          (-1L * tx.amount.value.value).toString,
          DagCurrency,
          None
        )
      ),
      None,
      None
    )
    if (includeNegative) List(operationNegative, operation) else List(operation)
  }

  def translateTransaction[F[_]: KryoSerializer](
    dagTransaction: Signed[DAGTransaction],
    status: String = ChainObjectStatus.Accepted.toString,
    includeNegative: Boolean = true
    // kryoSerializerReference<T>
    // that object would internally have innerKryoReference<Q>
  ): Either[model.Error, Transaction] = {
    // Is this the correct hash reference here? Are we segregating the signature data here?
    import org.tessellation.ext.crypto._

    val tx = dagTransaction
    tx.hash.left.map(_ => makeErrorCode(0)).map { hash =>
      Transaction(
        TransactionIdentifier(hash.value),
        translateDAGTransactionToOperations(tx, status, includeNegative),
        None,
        None
      )
    }
  }
}
