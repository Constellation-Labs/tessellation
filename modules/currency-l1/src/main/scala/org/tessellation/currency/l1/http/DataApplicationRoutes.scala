package org.tessellation.currency.l1.http

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import org.tessellation.currency.dataApplication.DataTransaction.DataTransactions
import org.tessellation.currency.dataApplication.FeeTransaction._
import org.tessellation.currency.dataApplication._
import org.tessellation.currency.l1.domain.error._
import org.tessellation.currency.schema.EstimatedFee
import org.tessellation.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import org.tessellation.currency.validations.DataTransactionsValidator.validateDataTransactionsL1
import org.tessellation.ext.http4s.error._
import org.tessellation.node.shared.domain.cluster.storage.L0ClusterStorage
import org.tessellation.node.shared.domain.queue.ViewableQueue
import org.tessellation.node.shared.domain.snapshot.storage.LastSnapshotStorage
import org.tessellation.routes.internal._
import org.tessellation.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import org.tessellation.security.signature.Signed
import org.tessellation.security.signature.Signed.InvalidSignatureForHash
import org.tessellation.security.{Hashed, Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe.shapes._
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder, Json}
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.{EntityDecoder, HttpRoutes, Response}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import shapeless._
import shapeless.syntax.singleton._

final case class DataApplicationRoutes[F[_]: Async: Hasher: SecurityProvider](
  dataApplicationPeerConsensusInput: Queue[F, Signed[ConsensusInput.PeerConsensusInput]],
  l0ClusterStorage: L0ClusterStorage[F],
  dataApplication: BaseDataApplicationL1Service[F],
  dataTransactionsQueue: ViewableQueue[F, DataTransactions],
  lastGlobalSnapshotStorage: LastSnapshotStorage[F, GlobalIncrementalSnapshot, GlobalSnapshotInfo],
  lastCurrencySnapshotStorage: LastSnapshotStorage[F, CurrencyIncrementalSnapshot, CurrencySnapshotInfo]
)(implicit S: Supervisor[F], nodeContext: L1NodeContext[F])
    extends Http4sDsl[F]
    with PublicRoutes[F]
    with P2PRoutes[F] {

  def logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  protected val prefixPath: InternalUrlPrefix = "/"

  private def validateDataUpdate(unsignedUpdate: DataUpdate)(onValid: F[Response[F]]): F[Response[F]] =
    dataApplication.validateUpdate(unsignedUpdate).flatMap {
      case Valid(_)   => onValid
      case Invalid(e) => BadRequest(InvalidDataUpdate(e.toString).toApplicationError)
    }

  private def validateDataTransactions(
    updates: DataTransactions
  )(onValid: F[Response[F]]): F[Response[F]] =
    lastGlobalSnapshotStorage.getOrdinal.flatMap {
      case Some(ordinal) =>
        nodeContext.getLastCurrencySnapshotCombined.map(_.map { case (_, snapshotInfo) => snapshotInfo.balances }).flatMap {
          case Some(balances) =>
            validateDataTransactionsL1(updates, dataApplication, balances, ordinal).flatMap {
              case Valid(_)   => onValid
              case Invalid(e) => BadRequest(InvalidDataUpdate(e.toString).toApplicationError)
            }
          case None =>
            InternalServerError(CurrencySnapshotUnavailable.toApplicationError)
        }

      case None =>
        InternalServerError(GL0SnapshotOrdinalUnavailable.toApplicationError)
    }

  protected val public: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "data" / "estimate-fee" =>
      implicit val decoder: Decoder[DataUpdate] = dataApplication.dataDecoder
      req
        .asR[DataUpdate] { update =>
          validateDataUpdate(update) {
            lastGlobalSnapshotStorage.getOrdinal.flatMap {
              case None => InternalServerError(GL0SnapshotOrdinalUnavailable.toApplicationError)
              case Some(ordinal) =>
                dataApplication
                  .estimateFee(ordinal)(update)
                  .flatMap { estimatedFee =>
                    EstimatedFee
                      .getUpdateHash(update, dataApplication.serializeUpdate)
                      .map(EstimatedFeeResponse(estimatedFee, _))
                  }
                  .map(_.asJson.dropNullValues)
                  .flatMap(Ok(_))
            }
          }
        }
        .handleUnknownError

    case GET -> Root / "data" =>
      implicit val dataUpdateEncoder: Encoder[DataUpdate] = dataApplication.dataEncoder
      dataTransactionsQueue.view.flatMap(Ok(_))

    case req @ POST -> Root / "data" =>
      def parseMultipleDataTransactions: F[DataTransactions] = {
        implicit val dataTransactionRequest: EntityDecoder[F, DataTransactions] = dataApplication.requestDecoder
        req.as[DataTransactions]
      }

      def parseSingleDataTransaction: F[Signed[DataUpdate]] = {
        implicit val decoder: EntityDecoder[F, Signed[DataUpdate]] = dataApplication.signedDataEntityDecoder
        req
          .as[Signed[DataUpdate]]
      }

      def handleDataUpdates(dataTransactions: DataTransactions): F[Response[F]] =
        validateDataTransactions(dataTransactions) {
          dataTransactions.traverse { signedTransaction =>
            signedTransaction.value match {
              case dataUpdate: DataUpdate =>
                Signed(dataUpdate, signedTransaction.proofs)
                  .toHashedWithSignatureCheck[F](dataApplication.serializeUpdate _)
                  .map(_.leftMap(_.widen[DataTransaction]))
                  .map(_.widen[Hashed[DataTransaction]])

              case feeTransaction: FeeTransaction =>
                Signed(feeTransaction, signedTransaction.proofs)
                  .toHashedWithSignatureCheck[F](serialize[F] _)
                  .map(_.leftMap(_.widen[DataTransaction]))
                  .map(_.widen[Hashed[DataTransaction]])
            }
          }.flatMap { results =>
            val (errors, validTransactions) = results.toList.partitionMap(identity) // Separate errors from valid transactions
            if (errors.nonEmpty) {
              BadRequest(
                Json.obj("error" -> Json.fromString("Invalid signature in data transactions"))
              )
            } else {
              NonEmptyList.fromList(validTransactions) match {
                case None =>
                  BadRequest(Json.obj("error" -> Json.fromString("No valid data transactions found")))
                case Some(txs) =>
                  dataTransactionsQueue
                    .offer(txs.map(_.signed))
                    .flatMap(_ => mkResponse(txs)) // Use mkResponse to create the response
              }
            }
          }
        }

      def mkResponse(transactions: NonEmptyList[Hashed[DataTransaction]]): F[Response[F]] = {
        val (dataUpdateHashes, feeTransactionHashes) = transactions.toList.partition(_.signed.value.isInstanceOf[DataUpdate])

        (dataUpdateHashes, feeTransactionHashes) match {
          case (data, Nil) =>
            Ok(("hash" ->> data.map(_.hash.value)) :: HNil)

          case (data, fees) =>
            Ok(
              ("hash" ->> data.map(_.hash.value)) ::
                ("feeHash" ->> fees.map(_.hash.value)) ::
                HNil
            )
        }
      }

      parseMultipleDataTransactions.handleErrorWith { _ =>
        logger.warn("Could not parse multiple data updates, attempting single") >>
          parseSingleDataTransaction.map(dataUpdate => NonEmptyList.one(dataUpdate.widen))
      }
        .flatMap(handleDataUpdates)
        .handleUnknownError

    case GET -> Root / "l0" / "peers" =>
      l0ClusterStorage.getPeers.flatMap(Ok(_))
  }

  protected val p2p: HttpRoutes[F] = HttpRoutes.of[F] {
    case req @ POST -> Root / "consensus" / "data-application" =>
      implicit val dataUpdateDecoder: Decoder[DataUpdate] = dataApplication.dataDecoder
      implicit val decoder: Decoder[ConsensusInput.Proposal] = ConsensusInput.Proposal.decoder(DataTransaction.decoder)

      req
        .as[Signed[ConsensusInput.Proposal]]
        .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])
        .handleErrorWith { _ =>
          req
            .as[Signed[ConsensusInput.SignatureProposal]]
            .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])
            .handleErrorWith { _ =>
              req
                .as[Signed[ConsensusInput.CancelledCreationRound]]
                .map(_.asInstanceOf[Signed[ConsensusInput.PeerConsensusInput]])
            }
        }
        .flatMap { consensusInput =>
          S.supervise(dataApplicationPeerConsensusInput.offer(consensusInput))
        }
        .flatMap(_ => Ok())
        .handleErrorWith { err =>
          logger.error(err)(s"An error occurred") >> InternalServerError()
        }
  }
}
