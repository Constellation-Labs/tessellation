package org.tessellation.dag.l1.rosetta

import cats.Order
import cats.data.NonEmptySet
import cats.effect.Async
import eu.timepit.refined.types.all.PosLong
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.kryo.KryoSerializer
import MockData.mockup
import Rosetta._
import Util.{getPublicKeyFromBytes, reduceListEither}
import examples.proofs
import org.tessellation.schema.address.{Address, DAGAddressRefined}
import org.tessellation.schema.transaction.{Transaction => DAGTransaction, _}
import org.tessellation.security.hash.Hash
import org.tessellation.security.hex.Hex
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.signature.SignatureProof
import org.tessellation.security.{Hashable, SecurityProvider}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.http4s.circe.CirceEntityCodec.circeEntityEncoder

import scala.collection.immutable.SortedSet
import scala.util.Try
import java.security.{PublicKey => JPublicKey}
import org.tessellation.ext.crypto._

import java.security.KeyFactory
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPublicKeySpec
import cats.data.{NonEmptyList, NonEmptySet}
import cats.effect.unsafe.implicits.global
import cats.effect.{Async, IO}
import cats.implicits.{toFlatMapOps, toFunctorOps, toSemigroupKOps}
import cats.{MonadThrow, Order}

import scala.collection.immutable.SortedSet
import scala.util.Try
import org.tessellation.dag.snapshot.GlobalSnapshot
import org.tessellation.ext.crypto._
import org.tessellation.ext.kryo.KryoRegistrationId
import org.tessellation.kryo.KryoSerializer
import MockData.mockup
import examples.proofs
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
import Util.{getPublicKeyFromBytes, reduceListEither}
import cats.syntax.flatMap._

//import io.circe.generic.extras.Configuration
//import io.circe.generic.extras.auto._


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
final case class RosettaRoutes[F[_]: Async: KryoSerializer: SecurityProvider](val networkId: String = "mainnet",
                                                                              blockIndexClient: BlockIndexClient[F],
                                                                              l1Client: L1Client[F]
                                                                             )
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
      case constants.dagBlockchainId =>
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

  def validateAddress2(address: String): Either[F[Response[F]], Unit] = {
    if (DAGAddressRefined.addressCorrectValidate.isValid(address)) {
      Right(())
    } else Left(errorMsg(4, address))
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
        val resp = for {
          _ <- validateNetwork2(r.networkIdentifier)
          _ <- validateAddress2(
            // TODO: Error on accountIdentifier subaccount not supported.
            r.accountIdentifier.address
          )
          address = r.accountIdentifier.address

        } yield {
          val balanceRes = blockIndexClient.queryAccountBalance(address, r.blockIdentifier)
          val res = balanceRes.flatMap{ b =>
            b.left.map(e => errorMsg(5, e))
              .map(o => o.map(a =>
                Ok(
                  AccountBalanceResponse(
                    BlockIdentifier(a.height, a.snapshotHash),
                    // TODO: Enum
                    List(Amount(a.amount.toString, DagCurrency, None)),
                    None
                  )
                )
              ).getOrElse(errorMsg(6, address))).merge
          }
          res
        }
        resp.merge
      }
    }

    case _ @ POST -> Root / "account" / "coins" => errorMsg(0, "UTXO endpoints not implemented")


    case req @ POST -> Root / "block" => {
      req.decodeRosetta[BlockRequest] { br =>
        val resp = for {
          _ <- validateNetwork2(br.networkIdentifier)
        } yield {
            val value = blockIndexClient.queryBlock(br.blockIdentifier).map { ogs =>
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
        resp.merge
      }
    }

    case req @ POST -> Root / "block" / "transaction" => {
      req.decodeRosetta[BlockTransactionRequest] { br =>
        validateNetwork[BlockTransactionRequest](
          br,
          _.networkIdentifier, { (x) =>
            val value = new BlockIndexClient().queryBlockTransaction(br.blockIdentifier)
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
                        val resp = new L1Client[F]()
                          .requestLastTransactionMetadataAndFee(address)
                          .left
                          .map(e => errorMsg(13, e))
                        val withFallback = resp.map {
                          l =>
                            val done = l match {
                              case Some(_) => Right(l)
                              case None =>
                                new BlockIndexClient()
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
                                Unknown.toString,
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
                        new L1Client()
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
            new BlockIndexClient()
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
          val value = new L1Client()
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
            val value = new L1Client().queryMempoolTransaction(NR.transactionIdentifier.hash)
            value.map { v =>
              // TODO: Enum
              val t: Either[model.Error, Transaction] =
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
            new L1Client()
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
                        historicalBalanceLookup = false,
                        None,
                        List(),
                        // TODO: Verify no balance exemptions
                        List(),
                        mempoolCoins = false,
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

        new L1Client()
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
            val client = new BlockIndexClient()
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

  def extractTransactions(gs: GlobalSnapshot) = {
    import eu.timepit.refined.auto._
    import org.tessellation.schema.transaction._

    val genesisTxs = if (gs.height.value.value == 0L) {
      gs.info.balances.toList.map {
        case (a, b) =>
          // TODO: Empty transaction translator
          Signed(
            DAGTransaction(
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
        DAGTransaction(
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
