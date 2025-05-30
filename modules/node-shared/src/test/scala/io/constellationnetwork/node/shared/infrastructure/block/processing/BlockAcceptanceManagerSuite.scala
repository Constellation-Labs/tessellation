package io.constellationnetwork.node.shared.infrastructure.block.processing

import cats.data.{EitherT, NonEmptyList}
import cats.effect.kernel.Ref
import cats.effect.{IO, Resource}
import cats.syntax.bifunctor._
import cats.syntax.either._
import cats.syntax.validated._

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.block.generators.signedBlockGen
import io.constellationnetwork.node.shared.domain.block.processing.{UsageCount, initUsageCount, _}
import io.constellationnetwork.node.shared.domain.transaction.TransactionChainValidator
import io.constellationnetwork.node.shared.infrastructure.block.processing.BlockAcceptanceManager
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.{Block, BlockReference, SnapshotOrdinal}
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.{Hash, ProofsHash}
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.shared.sharedKryoRegistrar

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

  type Res = (Hasher[IO], Hasher[IO])

  override def sharedResource: Resource[IO, Res] =
    KryoSerializer.forAsync[IO](sharedKryoRegistrar).flatMap { implicit kryo =>
      JsonSerializer.forSync[IO].asResource.map { implicit json =>
        (Hasher.forJson[IO], Hasher.forKryo[IO])
      }
    }

  private val getOption = SnapshotOrdinal.MinValue

  def mkBlockAcceptanceManager(txHasher: Hasher[IO], acceptInitiallyAwaiting: Boolean = true) =
    Ref[F].of[Map[Signed[Block], Boolean]](Map.empty.withDefaultValue(false)).map { state =>
      val blockLogic = new BlockAcceptanceLogic[IO] {
        override def acceptBlock(
          block: Signed[Block],
          txChains: Map[Address, TransactionChainValidator.TransactionNel],
          context: BlockAcceptanceContext[IO],
          contextUpdate: BlockAcceptanceContextUpdate,
          shouldValidateCollateral: Boolean = true
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
          signedBlock: Signed[Block],
          ordinal: SnapshotOrdinal,
          params: BlockValidationParams
        )(implicit hasher: Hasher[F]): IO[BlockValidationErrorOr[
          (Signed[Block], Map[Address, TransactionChainValidator.TransactionNel])
        ]] = signedBlock.parent.head match {
          case `invalidParent` => IO.pure(NotEnoughParents(0, 0).invalidNec)
          case _               => IO.pure((signedBlock, Map.empty[Address, TransactionChainValidator.TransactionNel]).validNec)

        }
      }
      BlockAcceptanceManager.make[IO](blockLogic, blockValidator, txHasher)
    }

  test("accept valid block") {
    case (currentHasher, txHasher) =>
      forall(validAcceptedBlocksGen) { blocks =>
        val expected = BlockAcceptanceResult(
          BlockAcceptanceContextUpdate.empty
            .copy(parentUsages = Map((validAcceptedParent, 1L))),
          blocks.sorted.map(b => (b, 0L)),
          Nil
        )
        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager(txHasher)
          res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null, getOption)(currentHasher)
        } yield expect.same(res, expected)
      }
  }

  test("reject valid block") {
    case (currentHasher, txHasher) =>
      forall(validRejectedBlocksGen) {
        case (acceptedBlocks, rejectedBlocks) =>
          val expected = BlockAcceptanceResult(
            BlockAcceptanceContextUpdate.empty
              .copy(parentUsages = if (acceptedBlocks.nonEmpty) Map((validAcceptedParent, 1L)) else Map.empty),
            acceptedBlocks.sorted.map(b => (b, 0L)),
            rejectedBlocks.sorted.reverse.map(b => (b, ParentNotFound(validRejectedParent)))
          )

          for {
            blockAcceptanceManager <- mkBlockAcceptanceManager(txHasher)
            res <- blockAcceptanceManager.acceptBlocksIteratively(acceptedBlocks ++ rejectedBlocks, null, getOption)(currentHasher)
          } yield expect.same(res, expected)
      }
  }

  test("awaiting valid block") {
    case (currentHasher, txHasher) =>
      forall(validAwaitingBlocksGen) {
        case (acceptedBlocks, awaitingBlocks) =>
          val expected = BlockAcceptanceResult(
            BlockAcceptanceContextUpdate.empty
              .copy(parentUsages = if (acceptedBlocks.nonEmpty) Map((validAcceptedParent, 1L)) else Map.empty),
            acceptedBlocks.sorted.map(b => (b, 0L)),
            awaitingBlocks.sorted.reverse
              .map(b => (b, SigningPeerBelowCollateral(NonEmptyList.of(commonAddress))))
          )
          for {
            blockAcceptanceManager <- mkBlockAcceptanceManager(txHasher)
            res <- blockAcceptanceManager.acceptBlocksIteratively(acceptedBlocks ++ awaitingBlocks, null, getOption)(currentHasher)
          } yield expect.same(res, expected)
      }
  }

  test("accept initially awaiting valid block") {
    case (currentHasher, txHasher) =>
      forall(validInitiallyAwaitingBlocksGen) { blocks =>
        val expected = BlockAcceptanceResult(
          BlockAcceptanceContextUpdate.empty
            .copy(parentUsages = Map((validInitiallyAwaitingParent, 1L))),
          blocks.sorted.map(b => (b, 0L)),
          Nil
        )

        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager(txHasher, acceptInitiallyAwaiting = true)
          res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null, getOption)(currentHasher)
        } yield expect.same(res, expected)
      }
  }

  test("reject initially awaiting valid block") {
    case (currentHasher, txHasher) =>
      forall(validInitiallyAwaitingBlocksGen) { blocks =>
        val expected = BlockAcceptanceResult(
          BlockAcceptanceContextUpdate.empty,
          Nil,
          blocks.sorted.reverse.map(b => (b, ParentNotFound(validInitiallyAwaitingParent)))
        )
        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager(txHasher, acceptInitiallyAwaiting = false)
          res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null, getOption)(currentHasher)
        } yield expect.same(res, expected)
      }
  }

  test("invalid block") {
    case (currentHasher, txHasher) =>
      forall(invalidBlocksGen) { blocks =>
        val expected = BlockAcceptanceResult(
          BlockAcceptanceContextUpdate.empty,
          Nil,
          blocks.sorted.reverse.map(b => (b, ValidationFailed(NonEmptyList.of(NotEnoughParents(0, 0)))))
        )
        for {
          blockAcceptanceManager <- mkBlockAcceptanceManager(txHasher, acceptInitiallyAwaiting = false)
          res <- blockAcceptanceManager.acceptBlocksIteratively(blocks, null, getOption)(currentHasher)
        } yield expect.same(res, expected)
      }
  }

  def validAcceptedBlocksGen = dagBlocksGen(1, validAcceptedParent)

  def validRejectedBlocksGen =
    for {
      rejected <- dagBlocksGen(1, validRejectedParent)
      accepted <- dagBlocksGen(0, validAcceptedParent)
    } yield (accepted, rejected)

  def validAwaitingBlocksGen =
    for {
      awaiting <- dagBlocksGen(1, validAwaitingParent)
      accepted <- dagBlocksGen(0, validAcceptedParent)
    } yield (accepted, awaiting)

  def validInitiallyAwaitingBlocksGen = dagBlocksGen(1, validInitiallyAwaitingParent)

  def invalidBlocksGen = dagBlocksGen(1, invalidParent)

  def dagBlocksGen(minimalSize: Int, parent: BlockReference) =
    for {
      size <- Gen.choose(minimalSize, 3)
      blocks <- Gen.listOfN(size, signedBlockGen)
    } yield blocks.map(substituteParent(parent))

  def substituteParent(parent: BlockReference)(signedBlock: Signed[Block]) =
    signedBlock.copy(value = signedBlock.value.copy(parent = NonEmptyList.of(parent)))
}
