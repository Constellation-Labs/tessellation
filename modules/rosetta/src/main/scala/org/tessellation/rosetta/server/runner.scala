package org.tessellation.rosetta.server
import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPublicKeySpec

import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO}
import cats.implicits.{toFunctorOps, toSemigroupKOps}
import cats.{MonadThrow, Order}

import scala.collection.immutable.SortedSet
import scala.util.Try

import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo.KryoRegistrationId
import org.tessellation.kryo.KryoSerializer
import org.tessellation.rosetta.server.MockData.mockup
import org.tessellation.rosetta.server.examples.proofs
import org.tessellation.rosetta.server.model._
import org.tessellation.rosetta.server.model.dag.schema._
import org.tessellation.schema.address
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.transaction.{Transaction => DAGTransaction, _}
import org.tessellation.sdk.config.types.HttpServerConfig
import org.tessellation.sdk.resources.MkHttpServer
import org.tessellation.sdk.resources.MkHttpServer.ServerName
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.key.ops.PublicKeyOps
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{Hashable, SecurityProvider}
import org.tessellation.shared.sharedKryoRegistrar

import com.comcast.ip4s.{Host, Port}
import eu.timepit.refined.numeric.Interval
import eu.timepit.refined.refineV
import eu.timepit.refined.types.all.PosLong
import eu.timepit.refined.types.numeric.NonNegLong
import io.circe.Decoder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECNamedCurveSpec
import org.bouncycastle.jce.{ECNamedCurveTable, ECPointUtil}
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpApp, HttpRoutes, Response, _}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import SignatureProof._
import Signed._

// TODO: https://github.com/coinbase/rosetta-ethereum/blob/master/rosetta-cli-conf/testnet/ethereum.ros

case class LastTransactionResponse(
  constructionMetadataResponseMetadata: Option[ConstructionMetadataResponseMetadata],
  suggestedFee: Option[Long]
)

case class SnapshotInfo(
  hash: String,
  height: Long,
  timestamp: Long
)

// Mockup of real client
class DagL1APIClient[F[_]: Async] {

//  var currentBlock

  def queryCurrentSnapshotHashAndHeight(): F[Either[String, SnapshotInfo]] =
    Async[F].pure(
      Right(
        SnapshotInfo(
          MockData.mockup.currentBlockHash.value,
          MockData.mockup.height.value.value,
          MockData.mockup.currentTimeStamp
        )
      )
    )

  def queryGenesisSnapshotHash(): Either[String, String] =
    Right(MockData.mockup.genesisHash)

  def queryPeerIds(): Either[String, List[String]] =
    Right(List(examples.sampleHash))

  def queryNetworkStatus(): F[Either[String, NetworkStatusResponse]] =
    // TODO: Monadic for
//    for {
//      peerIds <- queryPeerIds()
//
//    }
    queryPeerIds() match {
      case Left(x) => Async[F].pure(Left(x))
      case Right(peerIds) =>
        queryGenesisSnapshotHash() match {
          case Left(x) => Async[F].pure(Left(x))
          case Right(genesisHash) =>
            queryCurrentSnapshotHashAndHeight().map {
              case Left(x) => Left(x)
              case Right(SnapshotInfo(snapshotHash, snapshotHeight, timestamp)) =>
                Right(
                  NetworkStatusResponse(
                    BlockIdentifier(snapshotHeight, snapshotHash),
                    timestamp,
                    BlockIdentifier(0, genesisHash),
                    Some(BlockIdentifier(0, genesisHash)),
                    // TODO: check if node status is downloading, if so provide info.
                    Some(SyncStatus(Some(snapshotHeight), Some(snapshotHeight), Some("synced"), Some(true))),
                    peerIds.map { id =>
                      Peer(id, None)
                    }
                  )
                )
            }
        }
    }

  def queryVersion(): Either[String, String] =
    Right("2.0.0")

  def submitTransaction[Q[_]: KryoSerializer](stx: Signed[DAGTransaction]): Either[String, Unit] =
    mockup.acceptTransactionIncrementBlock(stx)
  //Right(())
  //Either.cond(test = true, println("Submitting transaction " + stx), "")

  def requestSuggestedFee(): Either[String, Option[Long]] =
    Right(Some(123))

  def requestLastTransactionMetadataAndFee(
    addressActual: address.Address
  ): Either[String, Option[ConstructionPayloadsRequestMetadata]] =
    Right(
      Some(
        ConstructionPayloadsRequestMetadata(
          addressActual.value.value,
          "emptylasttxhashref",
          0L,
          0L,
          None
        )
      )
    )
//    Either.cond(
//      true,//addressActual.value.value == examples.address,
//      LastTransactionResponse(
//        Some(ConstructionMetadataResponseMetadata(examples.sampleHash, 123)),
//        Some(456)
//      ),
//      "exception from l1 client query"
//    )

  def queryMempool(): List[String] =
    List(examples.sampleHash)

  def queryMempoolTransaction(hash: String): Option[Signed[DAGTransaction]] =
    if (hash == examples.sampleHash) Some(examples.transaction)
    else None

}

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

// Mockup of real client

class BlockIndexClient(val endpoint: String) {

  def searchBlocks(blockSearchRequest: BlockSearchRequest): Either[String, BlockSearchResponse] =
    Right(BlockSearchResponse(List(TransactionWithBlockHash(examples.transaction, examples.sampleHash, 0)), 1, None))

  def queryBlockEvents(limit: Option[Long], offset: Option[Long]): Either[String, List[GlobalSnapshot]] =
    Either.cond(offset.contains(0L), List(examples.snapshot), "err from block service")

  // This also has to request from L1 in case the indexer doesn't have it
  def requestLastTransactionMetadata(
    addressActual: address.Address
  ): Either[String, Option[ConstructionPayloadsRequestMetadata]] =
    Right(
      Some(
        ConstructionPayloadsRequestMetadata(
          addressActual.value.value,
          "emptylasttxhashref",
          0L,
          0L,
          None
        )
      )
    )
//    Either.cond(
//      addressActual.value.value == examples.address,
//      Some(ConstructionMetadataResponseMetadata(examples.sampleHash, 123)),
//      "exception from l1 client query"
//    )

  def queryBlockTransaction(blockIdentifier: BlockIdentifier): Either[String, Option[Signed[DAGTransaction]]] =
    Either.cond(
      test = blockIdentifier.index == 0 ||
        blockIdentifier.hash.contains(examples.sampleHash),
      Some(examples.transaction),
      "err"
    )

  def queryBlock(blockIdentifier: PartialBlockIdentifier): Either[String, Option[GlobalSnapshot]] = {
    println("Query block " + blockIdentifier)
    val maybeSnapshot = findBlock(blockIdentifier).map(_._1)
    println("maybeSnapshot " + maybeSnapshot)
    Right(maybeSnapshot)
  }

  def findBlock(pbi: PartialBlockIdentifier): Option[(GlobalSnapshot, Long)] = {
    val block = mockup.allBlocks.zipWithIndex.find {
      case (b, i) =>
        val h = mockup.blockToHash.get(b)
        pbi.index.contains(i) || pbi.hash.exists(hh => h.exists(_.value == hh))
    }
    block.map { case (k, v) => k -> v.toLong }
  }

  def queryAccountBalance(
    address: String,
    blockIndex: Option[PartialBlockIdentifier]
  ): Either[String, Option[AccountBlockResponse]] = {
    val maybeBalance = blockIndex.flatMap { pbi =>
      val block = findBlock(pbi)
      block.flatMap {
        case (s, i) =>
          s.info.balances.map { case (k, v) => k.value.value -> v }.toMap.get(address).map { b =>
            val h = mockup.blockToHash(s)
            AccountBlockResponse(b.value.value, h.value, i)
          }
      }
    }
    Right(maybeBalance.orElse {
      mockup.balances.get(address).map { v =>
        AccountBlockResponse(v, mockup.currentBlockHash.value, mockup.currentBlock.height.value.value)
      }
    })
//    mockup.currentBlock.info.balances
//    Either.cond(
//      test = address == examples.address,
//      Some(AccountBlockResponse(123457890, examples.sampleHash, 1)),
//      "some error"
//    )
  }

}
import cats.syntax.flatMap._

//import io.circe.generic.extras.Configuration
//import io.circe.generic.extras.auto._
import org.tessellation.rosetta.server.model.dag.decoders._

import java.security.{PublicKey => JPublicKey}

object Rosetta {

  // This works from:
  //  https://stackoverflow.com/questions/26159149/how-can-i-get-a-publickey-object-from-ec-public-key-bytes
  def getPublicKeyFromBytes(pubKey: Array[Byte]) = {
    val spec = ECNamedCurveTable.getParameterSpec("secp256k1")
    val kf = KeyFactory.getInstance("ECDSA", new BouncyCastleProvider)
    val params = new ECNamedCurveSpec("secp256k1", spec.getCurve, spec.getG, spec.getN)
    val point = ECPointUtil.decodePoint(params.getCurve, pubKey)
    val pubKeySpec = new ECPublicKeySpec(point, params)
    val pk = kf.generatePublic(pubKeySpec).asInstanceOf[ECPublicKey]
    pk
  }

  val dagBlockchainId = "dag"

  def reduceListEither[L, R](eitherList: List[Either[L, List[R]]]): Either[L, List[R]] =
    eitherList.reduce { (l: Either[L, List[R]], r: Either[L, List[R]]) =>
      l match {
        case x @ Left(_) => x
        case Right(y) =>
          r match {
            case xx @ Left(_) => xx
            case Right(yy)    => Right(y ++ yy)
          }
      }
    }

  // block_added or block_removed
  def convertSnapshotsToBlockEvents[F[_]: KryoSerializer: SecurityProvider: Async](
    gs: List[GlobalSnapshot],
    blockEventType: String
  ): Either[String, List[BlockEvent]] = {
    val value = gs.map(s => s.hash.map(h => List(h -> s)))
    val hashed = reduceListEither(value).left.map(_.getMessage)
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
  ): F[Either[Error, NonEmptyList[SignatureProof]]] = {
    val value = signature.map { s =>
      // TODO: Handle hex validation here
      val value1 = Async[F].delay(getPublicKeyFromBytes(Hex(s.publicKey.hexBytes).toBytes))
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
    val zero: Either[Error, List[SignatureProof]] = Right[Error, List[SignatureProof]](List())
    value
      .foldLeft(Async[F].delay(zero)) {
        case (agga, nextFEither) =>
          val inner2 = agga.flatMap { agg =>
            val inner = nextFEither.map { eitherErrSigProof =>
              val res = eitherErrSigProof.map { sp =>
                agg.map(ls => ls ++ List(sp))
              }
              var rett: Either[Error, List[SignatureProof]] = null
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

  def makeErrorCode(code: Int, retriable: Boolean = true, details: Option[ErrorDetails] = None): Error = {
    val (message, description) = errorCodes(code)
    Error(
      code,
      message,
      Some(description),
      retriable,
      details
    )
  }

  def makeErrorCodeMsg(code: Int, message: String, retriable: Boolean = true): Error =
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
  ): Either[Error, Transaction] = {
    // Is this the correct hash reference here? Are we segregating the signature data here?
    import org.tessellation.ext.crypto._

    val tx = dagTransaction
    tx.hash.left.map(_ => makeErrorCode(0)).map { hash =>
      model.Transaction(
        TransactionIdentifier(hash.value),
        translateDAGTransactionToOperations(tx, status, includeNegative),
        None,
        None
      )
    }
  }
}

import org.tessellation.rosetta.server.Rosetta._

/**
  * The data model for these routes was code-genned according to openapi spec using
  * the scala-scalatra generator. This generates the least amount of additional
  * model artifacts with minimal dependencies compared to the other scala code
  * generators.
  *
  * They are consistent with  rosetta "version":"1.4.12",
  *
  * Enum types do not generate properly (in any of the Scala code generators.)
  * circe json decoders were manually generated separately
  */
final case class RosettaRoutes[F[_]: Async: KryoSerializer: SecurityProvider](val networkId: String = "mainnet")
    extends Http4sDsl[F] {

  implicit val logger = Slf4jLogger.getLogger[F]

  private val testRoutes: HttpRoutes[F] = HttpRoutes.of[F] {
    case GET -> Root / "test" =>
      Ok("test")
  }

  def error(code: Int, retriable: Boolean = true, details: Option[ErrorDetails] = None): F[Response[F]] =
    InternalServerError(makeErrorCode(code, retriable, details))

  def errorMsg(code: Int, message: String, retriable: Boolean = true): F[Response[F]] =
    InternalServerError(
      makeErrorCode(code, retriable, Some(ErrorDetails(List(ErrorDetailKeyValue("exception", message)))))
    )

  // TODO: Change signature to pass network identifier directly
  def validateNetwork[T](t: T, NRA: T => NetworkIdentifier, f: (String) => F[Response[F]]): F[Response[F]] = {
    val networkIdentifier = NRA(t)
    networkIdentifier.blockchain match {
      case "dag" =>
        networkIdentifier.network match {
          case x if endpoints.contains(x) =>
            f(endpoints(x))
          case _ =>
            InternalServerError(makeErrorCode(1))
        }
      case _ => InternalServerError(makeErrorCode(1))
      // TODO: Error for invalid subnetwork unsupported.
    }
  }

  // TODO: Error for invalid subnetwork unsupported.
  def validateNetwork2(networkIdentifier: NetworkIdentifier): Either[F[Response[F]], Unit] = {
    if (networkIdentifier.subNetworkIdentifier.isDefined) {
      return Left(errorMsg(1, "Subnetworks not supported"))
    }
    networkIdentifier.blockchain match {
      case Rosetta.dagBlockchainId =>
        networkIdentifier.network match {
          case x if x == networkId =>
            Right(())
          case _ =>
            Left(errorMsg(1, "Invalid network id"))
        }
      case _ => Left(errorMsg(1, "Invalid blockchain id"))
    }
  }

  def validateAddress[T](request: T, requestToAddress: T => String, f: (String) => F[Response[F]]): F[Response[F]] = {
    val address = requestToAddress(request)
    if (DAGAddressRefined.addressCorrectValidate.isValid(address)) {
      f(address)
    } else errorMsg(4, address)
  }

  def validateCurveType(curveType: String): Either[F[Response[F]], Unit] =
    curveType match {
      case "secp256k1" => Right(())
      case _           => Left(errorMsg(12, curveType, retriable = false))
    }

  def validateHex(hexInput: String): Either[F[Response[F]], Hex] =
    Try { Hex(hexInput).toBytes }.toEither.left
      .map(e => errorMsg(11, s"hexInput: ${hexInput}, error: ${e.getMessage}", retriable = false))
      .map(_ => Hex(hexInput))

  def convertRosettaPublicKeyToJPublicKey(rosettaPublicKey: PublicKey): Either[F[Response[F]], F[JPublicKey]] =
    validateCurveType(rosettaPublicKey.curveType) match {
      case Left(e) => Left(e)
      case Right(_) =>
        validateHex(rosettaPublicKey.hexBytes) match {
          case Left(e) => Left(e)
          case Right(v) =>
            Right(
              Async[F].delay(getPublicKeyFromBytes(v.toBytes).asInstanceOf[JPublicKey])
              //  v.toPublicKey(Async[F], SecurityProvider[F])
            )
        }
    }

  private val routes: HttpRoutes[F] = HttpRoutes.of[F] {

    case req @ POST -> Root / "account" / "balance" => {
      req.decodeRosetta[AccountBalanceRequest] { r =>
        validateNetwork[AccountBalanceRequest](
          r,
          _.networkIdentifier, { x =>
            validateAddress[AccountBalanceRequest](
              r, // TODO: Error on accountIdentifier subaccount not supported.
              _.accountIdentifier.address, { address =>
                new BlockIndexClient(x)
                  .queryAccountBalance(address, r.blockIdentifier)
                  .left
                  .map(e => errorMsg(5, e))
                  .map(
                    o =>
                      o.map(
                          a =>
                            Ok(
                              AccountBalanceResponse(
                                BlockIdentifier(a.height, a.snapshotHash),
                                // TODO: Enum
                                List(Amount(a.amount.toString, DagCurrency, None)),
                                None
                              )
                            )
                        )
                        .getOrElse(errorMsg(6, address))
                  )
                  .fold(identity, identity)
              }
            )
          }
        )
      }
    }

    case req @ POST -> Root / "account" / "coins" => {
      errorMsg(0, "UTXO endpoints not implemented")
    }

    case req @ POST -> Root / "block" => {
      req.decodeRosetta[BlockRequest] { br =>
        validateNetwork[BlockRequest](
          br,
          _.networkIdentifier, { (x) =>
            val value = new BlockIndexClient(x).queryBlock(br.blockIdentifier).map { ogs =>
              val inner = ogs.map {
                gs =>
                  gs.hash.left
                    .map(t => errorMsg(0, "Hash calculation on snapshot failure: " + t.getMessage))
                    .map { gsHash =>
                      val translatedTransactions = extractTransactions(gs)
                      if (translatedTransactions.exists(_.isLeft)) {
                        errorMsg(0, "Internal transaction translation failure")
                      } else {
                        val txs = translatedTransactions.map(_.toOption.get)
                        Ok(
                          BlockResponse(
                            Some(
                              Block(
                                BlockIdentifier(gs.height.value.value, gsHash.value),
                                BlockIdentifier(
                                  Math.max(gs.height.value.value - 1, 0),
                                  if (gs.height.value.value > 0) gs.lastSnapshotHash.value
                                  else gsHash.value
                                ),
                                // TODO: Timestamp??
                                mockup.timestamps(gs.height.value.value),
                                txs,
                                None
                              )
                            ),
                            None
                          )
                        )
                      }
                    }
                    .merge
              }
              inner.getOrElse(Ok(BlockResponse(None, None)))
            }
            val response = value.left.map(e => errorMsg(5, e)).merge
            response.map { r =>
              println("response: " + r.asJson.toString)
              r
            }
          }
        )
      }
    }

    case req @ POST -> Root / "block" / "transaction" => {
      req.decodeRosetta[BlockTransactionRequest] { br =>
        validateNetwork[BlockTransactionRequest](
          br,
          _.networkIdentifier, { (x) =>
            val value = new BlockIndexClient(x).queryBlockTransaction(br.blockIdentifier)
            value.left
              .map(errorMsg(5, _))
              .map(
                t =>
                  t.map(tt => Ok(BlockTransactionResponse(translateTransaction(tt).toOption.get))).getOrElse(error(7))
              )
              .merge
          }
        )
      }
    }

    case req @ POST -> Root / "call" => {
      req.decodeRosetta[CallRequest] { br =>
        validateNetwork[CallRequest](
          br,
          _.networkIdentifier, { (x) =>
            // TODO: is any implementation here required yet?
            // this doesn't need to be required yet unless we have custom logic
            // Ok(CallResponse(CallResponseActual(), idempotent = true))
            error(8)
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "combine" => {
      req.decodeRosetta[ConstructionCombineRequest] { br =>
        validateNetwork[ConstructionCombineRequest](
          br,
          _.networkIdentifier, { (x) =>
            // TODO combine multiple signatures yet supported?
            if (br.signatures.size > 1) {
              error(8)
            } else if (br.signatures.isEmpty) {
              errorMsg(3, "No signatures found")
            } else {
              val res = KryoSerializer[F]
                .deserialize[DAGTransaction](
                  Hex(br.unsignedTransaction).toBytes // TODO: Handle error here for invalid hex
                )
                .left
                .map(_ => error(9))
                .map {
                  t: DAGTransaction =>
                    val value = Rosetta.convertSignature(br.signatures)
                    value.flatMap { v =>
                      v.left
                        .map(InternalServerError(_))
                        .map { prf =>
                          val ser = KryoSerializer[F]
                            .serialize(
                              Signed[DAGTransaction](
                                t,
                                NonEmptySet(prf.head, SortedSet(prf.tail: _*))(
                                  Order.fromOrdering(SignatureProof.OrderingInstance)
                                )
                              )
                            )
                            .left
                            .map(e => errorMsg(0, "Serialize transaction failure: " + e.getMessage))
                            .map(s => Ok(ConstructionCombineResponse(Hex.fromBytes(s).value)))
                          ser.merge
                        }
                        .merge
                    }
                }
              res.merge
            }
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "derive" => {
      req.decodeRosetta[ConstructionDeriveRequest] { br =>
        validateNetwork[ConstructionDeriveRequest](
          br,
          _.networkIdentifier, { (x) =>
            convertRosettaPublicKeyToJPublicKey(br.publicKey).map { inner =>
              inner.flatMap { pk =>
                val value = pk.toAddress.value.value
                Ok(ConstructionDeriveResponse(Some(value), Some(AccountIdentifier(value, None, None)), None))
              }
            }.merge
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "hash" => {
      req.decodeRosetta[ConstructionHashRequest] { br =>
        validateNetwork[ConstructionHashRequest](
          br,
          _.networkIdentifier, { (x) =>
            val ser = KryoSerializer[F]
              .deserialize[Signed[DAGTransaction]](Hex(br.signedTransaction).toBytes)
              .left
              .map(_ => error(9))
              .map(
                t =>
                  Hashable
                    .forKryo[F]
                    .hash(t)
                    .left
                    .map(_ => error(0)) // TODO: error code
                    .map(_.value)
                    .map(s => Ok(TransactionIdentifierResponse(TransactionIdentifier(s), None)))
                    .merge
              )
              .merge
            ser
          }
        )
      }
    }
    // TODO:

    case req @ POST -> Root / "construction" / "metadata" => {
      req.decodeRosetta[ConstructionMetadataRequest] { br =>
        validateNetwork[ConstructionMetadataRequest](
          br,
          _.networkIdentifier, { endpoint =>
            if (br.options.isDefined) {
              errorMsg(8, "Custom options not supported")
            }
            br.publicKeys match {
              case x if x.isEmpty  => errorMsg(8, "Must provide public key")
              case x if x.size > 1 => errorMsg(8, "Multiple public keys not supported")
              case Some(x) => {
                val key = x.head
                convertRosettaPublicKeyToJPublicKey(key).map {
                  inner =>
                    val res = inner.flatMap {
                      pk =>
                        val address = pk.toAddress
                        val resp = new DagL1APIClient[F]()
                          .requestLastTransactionMetadataAndFee(address)
                          .left
                          .map(e => errorMsg(13, e))
                        val withFallback = resp.map {
                          l =>
                            val done = l match {
                              case Some(_) => Right(l)
                              case None =>
                                new BlockIndexClient(endpoint)
                                  .requestLastTransactionMetadata(address)
                                  .left
                                  .map(e => errorMsg(5, e))
                                  .map(cmrm => l.orElse(cmrm))
                            }
                            done.map { r =>
                              r.map(
                                  cmrm =>
                                    Ok(
                                      ConstructionMetadataResponse(
                                        cmrm,
                                        Some(List(Amount(cmrm.fee.toString, DagCurrency, None)))
                                      )
                                    )
                                )
                                .getOrElse(
                                  errorMsg(6, "Unable to find reference to prior transaction in L1 or block index")
                                )
                            }.merge
                        }.merge
                        withFallback
                    }
                    res
                }
              }.merge
              case None => errorMsg(8, "No public keys provided, required for construction")
            }
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "parse" => {
      req.decodeRosetta[ConstructionParseRequest] { br =>
        validateNetwork[ConstructionParseRequest](
          br,
          _.networkIdentifier, { _ =>
            validateHex(br.transaction).map {
              h =>
                val bytes = h.toBytes
                if (br.signed) {
                  KryoSerializer[F]
                    .deserialize[Signed[DAGTransaction]](bytes)
                    .left
                    .map(t => errorMsg(14, t.getMessage))
                    .map {
                      stx =>
                        val res =
                          stx.proofs.toNonEmptyList.toList.map(s => s.id.hex.toPublicKey.map(_.toAddress.value.value))
                        val folded = res.foldLeft(Async[F].delay(List[String]())) {
                          case (agg, next) =>
                            next.flatMap(n => agg.map(ls => ls.appended(n)))
                        }
                        folded.map { addresses =>
                          Ok(
                            ConstructionParseResponse(
                              // TODO: Verify what this status needs to be, because we don't know it at this point
                              // Do we need an API call here?
                              translateDAGTransactionToOperations(
                                stx.value,
                                ChainObjectStatus.Unknown.toString,
                                ignoreStatus = true
                              ),
                              Some(addresses),
                              Some(addresses.map { a =>
                                AccountIdentifier(a, None, None)
                              }),
                              None
                            )
                          )
                        }.flatten
                    }
                    .merge
                } else {
                  KryoSerializer[F]
                    .deserialize[DAGTransaction](bytes)
                    .left
                    .map(t => errorMsg(14, t.getMessage))
                    .map { tx =>
                      Ok(
                        ConstructionParseResponse(
                          // TODO: Verify what this status needs to be, because we don't know it at this point
                          // Do we need an API call here?
                          translateDAGTransactionToOperations(
                            tx,
                            ChainObjectStatus.Unknown.toString,
                            ignoreStatus = true
                          ),
                          None,
                          None,
                          None
                        )
                      )
                    }
                    .merge
                }
            }.merge
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "payloads" => {
      req.decodeRosetta[ConstructionPayloadsRequest] { br =>
        validateNetwork[ConstructionPayloadsRequest](
          br,
          _.networkIdentifier, { (x) =>
            br.metadata match {
              case None =>
                errorMsg(15, "Missing metadata containing last transaction parent reference", retriable = false)
              case Some(meta) => {
                for {
                  unsignedTx <- Rosetta
                    .operationsToDAGTransaction(
                      meta.srcAddress,
                      meta.fee,
                      br.operations,
                      meta.lastTransactionHashReference,
                      meta.lastTransactionOrdinalReference,
                      meta.salt
                    )
                    .left
                    .map(e => errorMsg(0, e))
                  serializedTx <- KryoSerializer[F]
                    .serialize(unsignedTx)
                    .left
                    .map(t => errorMsg(0, "Kryo serialization failure of unsigned transaction: " + t.getMessage))
                  serializedTxHex = Hex.fromBytes(serializedTx)
                  unsignedTxHash <- unsignedTx.hash.left.map(e => errorMsg(0, e.getMessage))
                  toSignBytes = unsignedTxHash.value
                  payloads = List(
                    SigningPayload(
                      Some(meta.srcAddress),
                      Some(AccountIdentifier(meta.srcAddress, None, None)),
                      toSignBytes,
                      Some(signatureTypeEcdsa)
                    )
                  )
                } yield {
                  Ok(ConstructionPayloadsResponse(serializedTxHex.value, payloads))
                }
              }.merge
            }
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "preprocess" => {
      req.decodeRosetta[ConstructionPreprocessRequest] { br =>
        validateNetwork[ConstructionPreprocessRequest](
          br,
          _.networkIdentifier, { (x) =>
            // TODO: Multisig for multi-accounts here potentially.
            val sourceOperations = br.operations.filter(_.amount.exists(_.value.toLong < 0))
            if (sourceOperations.isEmpty) {
              errorMsg(0, "Missing source operation with outgoing transaction amount")
            } else {
              val requiredPublicKeys = Some(sourceOperations.flatMap { _.account }).filter(_.nonEmpty)
              // TODO: Right now the /metadata endpoint is actually grabbing the options directly
              // that logic should be moved here, but it doesn't matter much initially.
              val options = None
              Ok(ConstructionPreprocessResponse(options, requiredPublicKeys))
            }
          }
        )
      }
    }

    case req @ POST -> Root / "construction" / "submit" => {
      req.decodeRosetta[ConstructionSubmitRequest] { br =>
        validateNetwork[ConstructionSubmitRequest](
          br,
          _.networkIdentifier, { (x) =>
            validateHex(br.signedTransaction).map {
              hex =>
                // TODO: Common func for kryo deser.
                KryoSerializer[F]
                  .deserialize[Signed[DAGTransaction]](hex.toBytes)
                  .left
                  .map(t => errorMsg(14, t.getMessage))
                  .map { stx =>
                    stx.hash.left
                      .map(e => errorMsg(0, "Hash calculation failure: " + e.getMessage))
                      .map { hash =>
                        new DagL1APIClient()
                          .submitTransaction(stx)
                          // TODO block service error meessage
                          .left
                          .map(e => errorMsg(0, e))
                          .map { _ =>
                            Ok(TransactionIdentifierResponse(TransactionIdentifier(hash.value), None))
                          }
                          .merge
                      }
                      .merge
                  }
                  .merge
            }.merge
          }
        )
      }
    }

    case req @ POST -> Root / "events" / "blocks" => {
      req.decodeRosetta[EventsBlocksRequest] { br =>
        validateNetwork[EventsBlocksRequest](
          br,
          _.networkIdentifier, { (x) =>
            new BlockIndexClient(x)
              .queryBlockEvents(br.limit, br.offset)
              // TODO: error code and abstract
              .left
              .map(e => errorMsg(0, "blockevents query failure: " + e))
              .map { gs =>
                // TODO: handle block_removed
                Rosetta
                  .convertSnapshotsToBlockEvents(gs, "block_added")
                  .left
                  .map(e => errorMsg(0, e))
                  .map { lsbe =>
                    val maxSeq = lsbe.map(_.sequence).max
                    Ok(EventsBlocksResponse(maxSeq, lsbe))
                  }
                  .merge
              }
              .merge
          }
        )
      }
    }

    case req @ POST -> Root / "mempool" => {
      req.decodeRosetta[NetworkRequest] { NR =>
        println("Mempool request " + NR)
        validateNetwork[NetworkRequest](NR, _.networkIdentifier, { (x) =>
          val value = new DagL1APIClient()
            .queryMempool()
            .map(v => TransactionIdentifier(v))
          Ok(MempoolResponse(value))
        })
      }(NetworkRequestDecoder)
    }
    case req @ POST -> Root / "mempool" / "transaction" => {
      req.decodeRosetta[MempoolTransactionRequest] { NR =>
        validateNetwork[MempoolTransactionRequest](
          NR,
          _.networkIdentifier, { (x) =>
            val value = new DagL1APIClient().queryMempoolTransaction(NR.transactionIdentifier.hash)
            value.map { v =>
              // TODO: Enum
              val t: Either[Error, Transaction] =
                Rosetta.translateTransaction(v, status = ChainObjectStatus.Pending.toString)
              t.map { tt =>
                Ok(MempoolTransactionResponse(tt, None))
              }.getOrElse(error(0))
            }.getOrElse(error(2))
          }
        )
      }
    }
    case req @ POST -> Root / "network" / "list" => {
      req.decodeRosetta[MetadataRequest] { br =>
        println("network list request " + br)

        Ok(
          NetworkListResponse(
            List(
              NetworkIdentifier("dag", "mainnet", None),
              NetworkIdentifier("dag", "testnet", None)
            )
          )
        )
      }
    }
    case req @ POST -> Root / "network" / "options" => {
      req.decodeRosetta[NetworkRequest] { br =>
        println("network options request " + br)

        validateNetwork[NetworkRequest](
          br,
          _.networkIdentifier, { (x) =>
            new DagL1APIClient()
              .queryVersion()
              // TODO: err
              .left
              .map(e => errorMsg(0, "err"))
              .map(
                v =>
                  Ok(
                    NetworkOptionsResponse(
                      Version("1.4.12", v, None, None),
                      Allow(
                        List(
                          OperationStatus(ChainObjectStatus.Pending.toString, successful = false),
                          OperationStatus(ChainObjectStatus.Unknown.toString, successful = false),
                          OperationStatus(ChainObjectStatus.Accepted.toString, successful = true)
                        ),
                        List(dagCurrencyTransferType),
                        errorCodes.map {
                          case (code, (descr, extended)) =>
                            // TODO: fix retriable
                            Error(code, descr, Some(extended), retriable = true, None)
                        }.toList,
                        // TODO: set historical balance lookup if block indexer provides, can be optional.
                        false,
                        None,
                        List(),
                        // TODO: Verify no balance exemptions
                        List(),
                        false,
                        Some("lower_case"),
                        Some("lower_case")
                      )
                    )
                  )
              )
              .merge
          }
        )
      }
    }

    case req @ POST -> Root / "network" / "status" => {
      req.decodeRosetta[NetworkRequest] { br =>
        println("network status request " + br)

        new DagL1APIClient()
          .queryNetworkStatus()
          .flatMap { statusF =>
            val res = for {
              _ <- validateNetwork2(br.networkIdentifier)
              status <- statusF.left
                .map(e => errorMsg(0, e))
            } yield {
              Ok(status)
            }
            res.merge
          }
//        validateNetwork[NetworkRequest](br, _.networkIdentifier, { (x) =>
//          new DagL1APIClient(x)
//            .queryNetworkStatus()
//            // TODO: err
//
//            .map(Ok(_))
//            .merge
//        })
      }
    }

    case req @ POST -> Root / "search" / "transactions" => {
      req.decodeRosetta[SearchTransactionsRequest] { br =>
        validateNetwork[SearchTransactionsRequest](
          br,
          _.networkIdentifier, { (x) =>
            val client = new BlockIndexClient(x)
            // TODO: Enum
            val isOr = br.operator.contains("or")
            val isAnd = br.operator.contains("and")
            // TODO: Throw error on subaccountidentifier not supported.
            val account2 = br.accountIdentifier.map(_.address)
            val accountOpt = Seq(br.address, account2).filter(_.nonEmpty).head
            // TODO: Throw error on `type` not supported, coin, currency not supported
            val networkStatus = br.status
            client
              .searchBlocks(
                BlockSearchRequest(
                  isOr,
                  isAnd,
                  accountOpt,
                  networkStatus,
                  br.limit,
                  br.offset,
                  br.transactionIdentifier.map(_.hash),
                  br.maxBlock
                )
              )
              .left
              .map(e => errorMsg(0, e))
              .map { res =>
                val value = res.transactions.map { tx =>
                  translateTransaction(tx.transaction).left
                    .map(InternalServerError(_))
                    .map(txR => List(BlockTransaction(BlockIdentifier(tx.blockIndex, tx.blockHash), txR)))
                }
                reduceListEither(value).map { bt =>
                  Ok(SearchTransactionsResponse(bt, res.total, res.nextOffset))
                }.merge
              }
              .merge
          }
        )
      }
    }
  }

  private def extractTransactions(gs: GlobalSnapshot) = {
    import eu.timepit.refined.auto._
    import org.tessellation.schema.transaction._

    val genesisTxs = if (gs.height.value.value == 0L) {
      gs.info.balances.toList.map {
        case (a, b) =>
          // TODO: Empty transaction translator
          Signed(
            Transaction(
              Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
              a,
              TransactionAmount(PosLong.from(b.value).toOption.get),
              TransactionFee(0L),
              TransactionReference(
                TransactionOrdinal(0L),
                Hash(examples.sampleHash)
              ),
              TransactionSalt(0L)
            ),
            proofs
          )
      }
    } else List()
    val blockTxs = gs.blocks.toList
      .flatMap(x => x.block.value.transactions.toNonEmptyList.toList)
    val rewardTxs = gs.rewards.toList.map { rw =>
      Signed(
        Transaction(
          Address("DAG2EUdecqFwEGcgAcH1ac2wrsg8acrgGwrQabcd"),
          rw.destination,
          rw.amount,
          TransactionFee(0L),
          TransactionReference(
            TransactionOrdinal(0L),
            Hash(examples.sampleHash)
          ),
          TransactionSalt(0L)
        ),
        proofs
      )
    }
    val allTxs = blockTxs.map(t => translateTransaction(t)) ++
      (rewardTxs ++ genesisTxs).map(t => translateTransaction(t, includeNegative = false))

    allTxs
  }

  val allRoutes: HttpRoutes[F] = testRoutes <+> routes

}

// Temporary
object runner {

  import eu.timepit.refined.auto._

  type RunnerKryoRegistrationIdRange = Interval.Closed[300, 399]

  type RunnerKryoRegistrationId = KryoRegistrationId[RunnerKryoRegistrationIdRange]

  val runnerKryoRegistrar: Map[Class[_], RunnerKryoRegistrationId] = Map(
    classOf[RewardTransaction] -> 389,
    classOf[Signed[Transaction]] -> 390,
    SignatureProof.OrderingInstance.getClass -> 391
    //classOf[Signed[Transaction]] -> 390,
  )

  def main(args: Array[String]): Unit = {

    implicit val logger: SelfAwareStructuredLogger[IO] = Slf4jLogger.getLogger[IO]
    import org.tessellation.ext.kryo._
    val registrar = org.tessellation.dag.dagSharedKryoRegistrar.union(sharedKryoRegistrar).union(runnerKryoRegistrar)
    val res = SecurityProvider
      .forAsync[IO]
      .flatMap { implicit sp =>
        KryoSerializer
          .forAsync[IO](registrar)
          .flatMap { implicit kryo =>
            println(
              "Example hex unsigned transaction: " + Hex
                .fromBytes(kryo.serialize(examples.transaction.value).toOption.get)
                .value
            )
//            Future{
//              while (true) {
//                Thread.sleep(5000)
//                println("Background future running")
//                println(mockup.mkNewTestTransaction())
//              }
//            }(scala.concurrent.ExecutionContext.global)
            val value = MockData.mockup.genesis.hash.toOption.get
            MockData.mockup.genesisHash = value.value
            MockData.mockup.currentBlockHash = value
            MockData.mockup.blockToHash(mockup.genesis) = value
            val value1 = kryo.serialize(examples.transaction)
            value1.left.map(t => throw t)
            println(
              "Example hex signed transaction: " + Hex
                .fromBytes(value1.toOption.get)
                .value
            )
            val http = new RosettaRoutes[IO]()(Async[IO], kryo, sp)
            val publicApp: HttpApp[IO] = http.allRoutes.orNotFound
            //loggers(openRoutes.orNotFound)
            MkHttpServer[IO].newEmber(
              ServerName("public"),
              HttpServerConfig(Host.fromString("0.0.0.0").get, Port.fromInt(8080).get),
              publicApp
            )
          }
      }
    res.useForever
      .unsafeRunSync()
  }
}
