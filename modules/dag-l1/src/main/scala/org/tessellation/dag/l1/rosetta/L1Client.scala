package org.tessellation.dag.l1.rosetta

import cats.effect.Async
import cats.implicits.toFunctorOps
import org.tessellation.kryo.KryoSerializer
import MockData.mockup
import org.tessellation.schema.address
import org.tessellation.security.signature.Signed
import org.tessellation.schema.transaction.{Transaction => DAGTransaction, _}
import org.tessellation.rosetta.server.model.dag.schema._
import org.tessellation.rosetta.server.model._

// Mockup of real client
class L1Client[F[_]: Async] {

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
