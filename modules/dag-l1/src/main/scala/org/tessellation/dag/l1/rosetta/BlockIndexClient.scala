package org.tessellation.dag.l1.rosetta

import cats.effect.Async
import org.tessellation.dag.snapshot.GlobalSnapshot
import MockData.mockup
import org.tessellation.schema.address
import org.tessellation.security.signature.Signed
import org.tessellation.schema.transaction.{Transaction => DAGTransaction, _}
import model._
import dag.schema._

class BlockIndexClient[F[_]: Async]() {

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
  ): F[Either[String, Option[AccountBlockResponse]]] = {
    Async[F].pure {
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
    }
//    mockup.currentBlock.info.balances
//    Either.cond(
//      test = address == examples.address,
//      Some(AccountBlockResponse(123457890, examples.sampleHash, 1)),
//      "some error"
//    )
  }

}
