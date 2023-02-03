package org.tessellation.dag.block.processing

import cats.data.{EitherT, NonEmptyList}
import cats.effect.kernel.Ref
import cats.effect.{IO, Resource}
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.validated._

import org.tessellation.dag.block.BlockValidator
import org.tessellation.dag.block.BlockValidator.NotEnoughParents
import org.tessellation.dag.block.processing.UsageCount
import org.tessellation.dag.dagSharedKryoRegistrar
import org.tessellation.dag.domain.block.DAGBlock
import org.tessellation.dag.domain.block.generators._
import org.tessellation.dag.transaction.TransactionChainValidator
import org.tessellation.ext.kryo._
import org.tessellation.kryo.KryoSerializer
import org.tessellation.schema.BlockReference
import org.tessellation.schema.address.Address
import org.tessellation.schema.height.Height
import org.tessellation.security.hash.{Hash, ProofsHash}
import org.tessellation.security.signature.Signed
import org.tessellation.shared.sharedKryoRegistrar

import eu.timepit.refined.auto._
import org.scalacheck.Gen
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object BlockAcceptanceManagerSuite extends MutableIOSuite with Checkers {

  val validAcceptedParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "1"))
  val validRejectedParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "2"))
  val validAwaitingParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "3"))
  val validInitiallyAwaitingParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "4"))
  val invalidParent = BlockReference(Height(0L), ProofsHash(Hash.empty.value.init + "5"))
  val commonAddress = Address("DAG0y4eLqhhXUafeE3mgBstezPTnr8L3tZjAtMWB")
  type Res = KryoSerializer[IO]

  override def sharedResource: Resource[IO, BlockAcceptanceManagerSuite.Res] =
    KryoSerializer.forAsync[IO](dagSharedKryoRegistrar.union(sharedKryoRegistrar))

  def mkBlockAcceptanceManager(acceptInitiallyAwaiting: Boolean = true)(implicit kryo: KryoSerializer[IO]) =
    Ref[F].of[Map[Signed[DAGBlock], Boolean]](Map.empty.withDefaultValue(false)).map { state =>
      val blockLogic = new BlockAcceptanceLogic[IO] {
        override def acceptBlock(
          block: Signed[DAGBlock],
          txChains: Map[Address, TransactionChainValidator.TransactionNel],
          context: BlockAcceptanceContext[IO],
          contextUpdate: BlockAcceptanceContextUpdate
        ): EitherT[IO, BlockNotAcceptedReason, (BlockAcceptanceContextUpdate, UsageCount)] =
          EitherT(
            for {
              wasSeen <- state.getAndUpdate(wasSeen => wasSeen + ((block, true)))
            } yield
              block.parent.head match {
                case `validAcceptedParent` =>
                  (BlockAcceptanceContextUpdate.empty.copy(parentUsages = Map((block.parent.head, 1L))), initUsageCount)
                    .asRight[BlockNotAcceptedReason]

                case `validRejectedParent` =>
                  ParentNotFound(validRejectedParent)
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validAwaitingParent` =>
                  SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if !wasSeen.apply(block) =>
                  SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if wasSeen.apply(block) && acceptInitiallyAwaiting =>
                  (BlockAcceptanceContextUpdate.empty.copy(parentUsages = Map((block.parent.head, 1L))), initUsageCount)
                    .asRight[BlockNotAcceptedReason]

                case `validInitiallyAwaitingParent` if wasSeen.apply(block) && !acceptInitiallyAwaiting =>
                  ParentNotFound(validInitiallyAwaitingParent)
                    .asLeft[(BlockAcceptanceContextUpdate, UsageCount)]
                    .leftWiden[BlockNotAcceptedReason]

                case _ => ???
              }
          )
      }

      val blockValidator = new BlockValidator[IO] {

        override def validate(
          signedBlock: Signed[DAGBlock],
          params: BlockValidator.BlockValidationParams
        ): IO[BlockValidator.BlockValidationErrorOr[
          (Signed[DAGBlock], Map[Address, TransactionChainValidator.TransactionNel])
        ]] = signedBlock.parent.head match {
          case `invalidParent` => IO.pure(NotEnoughParents(0, 0).invalidNec)
          case _               => IO.pure((signedBlock, Map.empty[Address, TransactionChainValidator.TransactionNel]).validNec)

        }
      }
      BlockAcceptanceManager.make[IO](blockLogic, blockValidator)
    }

  test("accept valid block") { implicit ks =>
    forall(validAcceptedDAGBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty
          .copy(parentUsages = Map((validAcceptedParent, 1L))),
        blocks.sorted.map(b => (b, 0L)),
        Nil
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager()
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("reject valid block") { implicit ks =>
    forall(validRejectedDAGBlocksGen) {
      case (acceptedBlocks, rejectedBlocks) =>
        val expected = BlockAcceptanceResult(
          BlockAcceptanceContextUpdate.empty
            .copy(parentUsages = if (acceptedBlocks.nonEmpty) Map((validAcceptedParent, 1L)) else Map.empty),
          acceptedBlocks.sorted.map(b => (b, 0L)),
          rejectedBlocks.sorted.reverse.map(b => (b, ParentNotFound(validRejectedParent)))
        )

        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager()
          res <- blockAcceptanceManager.acceptBlocksIteratively(acceptedBlocks ++ rejectedBlocks, null)
        } yield expect.same(res, expected)
    }
  }

  test("awaiting valid block") { implicit ks =>
    forall(validAwaitingDAGBlocksGen) {
      case (acceptedBlocks, awaitingBlocks) =>
        val expected = BlockAcceptanceResult(
          BlockAcceptanceContextUpdate.empty
            .copy(parentUsages = if (acceptedBlocks.nonEmpty) Map((validAcceptedParent, 1L)) else Map.empty),
          acceptedBlocks.sorted.map(b => (b, 0L)),
          awaitingBlocks.sorted.reverse
            .map(b => (b, SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))))
        )
        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager()
          res <- blockAcceptanceManager.acceptBlocksIteratively(acceptedBlocks ++ awaitingBlocks, null)
        } yield expect.same(res, expected)
    }
  }

  test("accept initially awaiting valid block") { implicit ks =>
    forall(validInitiallyAwaitingDAGBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty
          .copy(parentUsages = Map((validInitiallyAwaitingParent, 1L))),
        blocks.sorted.map(b => (b, 0L)),
        Nil
      )

      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = true)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("reject initially awaiting valid block") { implicit ks =>
    forall(validInitiallyAwaitingDAGBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        Nil,
        blocks.sorted.reverse.map(b => (b, ParentNotFound(validInitiallyAwaitingParent)))
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = false)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  test("invalid block") { implicit ks =>
    forall(invalidDAGBlocksGen) { blocks =>
      val expected = BlockAcceptanceResult(
        BlockAcceptanceContextUpdate.empty,
        Nil,
        blocks.sorted.reverse.map(b => (b, ValidationFailed(NonEmptyList.of(NotEnoughParents(0, 0)))))
      )
      for {
        blockAcceptanceManager <- mkBlockAcceptanceManager(acceptInitiallyAwaiting = false)
        res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null)
      } yield expect.same(res, expected)
    }
  }

  def validAcceptedDAGBlocksGen = dagBlocksGen(1, validAcceptedParent)

  def validRejectedDAGBlocksGen =
    for {
      rejected <- dagBlocksGen(1, validRejectedParent)
      accepted <- dagBlocksGen(0, validAcceptedParent)
    } yield (accepted, rejected)

  def validAwaitingDAGBlocksGen =
    for {
      awaiting <- dagBlocksGen(1, validAwaitingParent)
      accepted <- dagBlocksGen(0, validAcceptedParent)
    } yield (accepted, awaiting)

  def validInitiallyAwaitingDAGBlocksGen = dagBlocksGen(1, validInitiallyAwaitingParent)

  def invalidDAGBlocksGen = dagBlocksGen(1, invalidParent)

  def dagBlocksGen(minimalSize: Int, parent: BlockReference) =
    for {
      size <- Gen.choose(minimalSize, 3)
      blocks <- Gen.listOfN(size, signedDAGBlockGen)
    } yield blocks.map(substituteParent(parent))

  def substituteParent(parent: BlockReference)(signedBlock: Signed[DAGBlock]) =
    signedBlock.copy(value = signedBlock.value.copy(parent = NonEmptyList.of(parent)))
}
