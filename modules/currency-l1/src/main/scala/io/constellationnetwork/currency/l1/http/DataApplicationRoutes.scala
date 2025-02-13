package io.constellationnetwork.currency.l1.http

import cats.data.NonEmptyList
import cats.data.Validated.{Invalid, Valid}
import cats.effect.Async
import cats.effect.std.{Queue, Supervisor}
import cats.syntax.all._

import io.constellationnetwork.currency.dataApplication.DataTransaction.DataTransactions
import io.constellationnetwork.currency.dataApplication.FeeTransaction._
import io.constellationnetwork.currency.dataApplication._
import io.constellationnetwork.currency.l1.domain.error._
import io.constellationnetwork.currency.schema.EstimatedFee
import io.constellationnetwork.currency.schema.currency.{CurrencyIncrementalSnapshot, CurrencySnapshotInfo}
import io.constellationnetwork.currency.validations.DataTransactionsValidator.validateDataTransactionsL1
import io.constellationnetwork.ext.http4s.error._
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.domain.cluster.storage.L0ClusterStorage
import io.constellationnetwork.node.shared.domain.queue.ViewableQueue
import io.constellationnetwork.node.shared.domain.snapshot.storage.LastSnapshotStorage
import io.constellationnetwork.routes.internal._
import io.constellationnetwork.schema.{GlobalIncrementalSnapshot, GlobalSnapshotInfo}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.{Hashed, Hasher, SecurityProvider}

import eu.timepit.refined.auto._
import io.circe.syntax.EncoderOps
import io.circe.{Decoder, Encoder}
import org.http4s.circe.CirceEntityCodec.{circeEntityDecoder, circeEntityEncoder}
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

final case class DataApplicationRoutes[F[_]: Async: Hasher: JsonSerializer: SecurityProvider](
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
      def handleDataUpdates(dataRequest: DataRequest): F[Response[F]] = {
        val dataTransactions: NonEmptyList[Signed[DataTransaction]] = dataRequest match {
          case SingleDataUpdateRequest(dataUpdate)   => NonEmptyList.one(dataUpdate)
          case DataTransactionsRequest(transactions) => transactions
        }

        validateDataTransactions(dataTransactions) {
          dataTransactions.traverse { signedTransaction =>
            signedTransaction.value match {
              case dataUpdate: DataUpdate =>
                Signed(dataUpdate, signedTransaction.proofs)
                  .toHashedWithSignatureCheck[F](dataApplication.serializeUpdate _)
                  .map(_.widen[Hashed[DataTransaction]])
                  .map(_.leftMap(_.widen[DataTransaction]))

              case feeTransaction: FeeTransaction =>
                Signed(feeTransaction, signedTransaction.proofs)
                  .toHashedWithSignatureCheck[F](serialize[F] _)
                  .map(_.widen[Hashed[DataTransaction]])
                  .map(_.leftMap(_.widen[DataTransaction]))
            }
          }.flatMap { results =>
            val dataTransactionsValidation: Either[DataApplicationValidationError, NonEmptyList[Hashed[DataTransaction]]] =
              results.toList.partitionMap(identity) match {
                case (invalidSignatures, _) if invalidSignatures.nonEmpty => Errors.InvalidSignature.asLeft
                case (_, Nil)                                             => Errors.NoValidDataTransactions.asLeft
                case (_, txns)                                            => NonEmptyList.fromListUnsafe(txns).asRight
              }

            dataTransactionsValidation.fold(
              error => dataApplication.postDataTransactionsResponseEncoder(dataRequest, error.asLeft),
              txs =>
                dataTransactionsQueue
                  .offer(txs.map(_.signed))
                  .flatMap(_ => dataApplication.postDataTransactionsResponseEncoder(dataRequest, txs.asRight))
            )
          }
        }
      }

      dataApplication
        .postDataTransactionsRequestDecoder(req)
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
