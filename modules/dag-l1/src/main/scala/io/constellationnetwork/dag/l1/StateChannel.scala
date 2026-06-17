package io.constellationnetwork.dag.l1

import java.security.KeyPair

import cats.data.OptionT
import cats.effect.std.{Random, Semaphore}
import cats.effect.{Async, Ref}
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.order._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.traverseFilter._

import scala.concurrent.duration.DurationInt

import io.constellationnetwork.dag.l1.config.types.AppConfig
import io.constellationnetwork.dag.l1.domain.block.BlockStorage._
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusInput._
import io.constellationnetwork.dag.l1.domain.consensus.block.BlockConsensusOutput.{CleanedConsensuses, FinalBlock, NoData}
import io.constellationnetwork.dag.l1.domain.consensus.block.Validator.{canStartInspectionTrigger, canStartOwnConsensus, isPeerInputValid}
import io.constellationnetwork.dag.l1.domain.consensus.block._
import io.constellationnetwork.dag.l1.http.p2p.L0BlockOutputClient.L1OutputSubmissionResult
import io.constellationnetwork.dag.l1.http.p2p.L0BlockOutputClient.L1OutputSubmissionResult.{Accepted, ParentOrdinalGapTooLarge, Rejected}
import io.constellationnetwork.dag.l1.http.p2p.P2PClient
import io.constellationnetwork.dag.l1.modules._
import io.constellationnetwork.ext.fs2.StreamOps
import io.constellationnetwork.kernel.CellError
import io.constellationnetwork.node.shared.cli.CliMethod
import io.constellationnetwork.node.shared.infrastructure.metrics.Metrics
import io.constellationnetwork.schema.Block
import io.constellationnetwork.schema.height.Height
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.snapshot.{Snapshot, SnapshotInfo, StateProof}
import io.constellationnetwork.security._
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.auto._
import fs2.{Pipe, Stream}
import org.typelevel.log4cats.SelfAwareStructuredLogger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class StateChannel[
  F[_]: Async: HasherSelector: SecurityProvider: Random: Metrics,
  P <: StateProof,
  S <: Snapshot,
  SI <: SnapshotInfo[P],
  R <: CliMethod
](
  appConfig: AppConfig,
  blockAcceptanceS: Semaphore[F],
  blockCreationS: Semaphore[F],
  blockStoringS: Semaphore[F],
  l0ResendBuffer: Ref[F, Vector[Signed[Block]]],
  keyPair: KeyPair,
  p2PClient: P2PClient[F],
  programs: Programs[F, P, S, SI],
  queues: Queues[F],
  selfId: PeerId,
  services: Services[F, P, S, SI, R],
  storages: Storages[F, P, S, SI],
  validators: Validators[F],
  txHasher: Hasher[F]
) {

  private implicit val logger: SelfAwareStructuredLogger[F] = Slf4jLogger.getLogger[F]

  private val blockConsensusContext =
    BlockConsensusContext[F](
      p2PClient.blockConsensus,
      storages.block,
      validators.block,
      storages.cluster,
      appConfig.consensus,
      storages.consensus,
      keyPair,
      selfId,
      storages.transaction,
      validators.transaction,
      txHasher
    )

  private val inspectionTriggerInput: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .evalMap { _ =>
      canStartInspectionTrigger(
        storages.lastSnapshot.getOrdinal
      ).handleErrorWith { e =>
        logger.warn(e)("Failure checking if inspection trigger consensus can be kicked off!").map(_ => false)
      }
    }
    .filter(identity)
    .as(InspectionTrigger)

  private val ownRoundTriggerInput: Stream[F, OwnerBlockConsensusInput] = Stream
    .awakeEvery(5.seconds)
    .evalMapLocked(blockCreationS) { _ =>
      canStartOwnConsensus(
        storages.consensus,
        storages.node,
        storages.cluster,
        storages.block,
        storages.transaction,
        appConfig.consensus.peersCount,
        appConfig.consensus.tipsCount
      ).handleErrorWith { e =>
        logger.warn(e)("Failure checking if own consensus can be kicked off!").map(_ => false)
      }
    }
    .filter(identity)
    .as(OwnRoundTrigger)

  private val ownerBlockConsensusInputs: Stream[F, OwnerBlockConsensusInput] =
    inspectionTriggerInput.merge(ownRoundTriggerInput)

  private val peerBlockConsensusInputs: Stream[F, PeerBlockConsensusInput] = Stream
    .fromQueueUnterminated(queues.peerBlockConsensusInput)
    .evalFilter(in => HasherSelector[F].withCurrent(implicit hasher => isPeerInputValid(in)))
    .map(_.value)

  private val blockConsensusInputs: Stream[F, BlockConsensusInput] =
    ownerBlockConsensusInputs.merge(peerBlockConsensusInputs)

  private val runConsensus: Pipe[F, BlockConsensusInput, FinalBlock] =
    _.evalTap(input => logger.debug(s"Received block consensus input to process: ${input.show}"))
      .evalMap(blockConsensusInput =>
        OptionT(storages.lastSnapshot.getOrdinal)
          .getOrRaise(new IllegalStateException("Could not find the latest snapshot ordinal"))
          .flatMap(ordinal =>
            HasherSelector[F].withCurrent { implicit hasher =>
              new BlockConsensusCell[F](blockConsensusInput, blockConsensusContext, ordinal).run()
            }
          )
          .handleErrorWith(e => CellError(e.getMessage).asLeft[BlockConsensusOutput].pure[F])
      )
      .flatMap {
        case Left(ce) =>
          Stream.eval(logger.warn(ce)(s"Error occurred during some step of block consensus.")) >>
            Stream.empty
        case Right(ohm) =>
          ohm match {
            case fb @ FinalBlock(hashedBlock) =>
              Stream
                .eval(logger.debug(s"Block created! Hash=${hashedBlock.hash} ProofsHash=${hashedBlock.proofsHash}"))
                .as(fb)
            case CleanedConsensuses(ids) =>
              Stream.eval(logger.warn(s"Cleaned following timed-out consensuses: $ids")) >>
                Stream.empty
            case NoData => Stream.empty
          }
      }

  private val gossipBlock: Pipe[F, FinalBlock, FinalBlock] =
    _.evalTap { fb =>
      services.gossip
        .spreadCommon(fb.hashedBlock.signed)
        .handleErrorWith(e => logger.warn(e)("Block gossip spread failed!"))
    }

  private val peerBlocks: Stream[F, FinalBlock] = Stream
    .fromQueueUnterminated(queues.peerBlock)
    .evalMap(block => HasherSelector[F].withCurrent(implicit hasher => block.toHashedWithSignatureCheck))
    .evalTap {
      case Left(e)  => logger.warn(e)(s"Received an invalidly signed peer block!")
      case Right(_) => Async[F].unit
    }
    .collect {
      case Right(hashedBlock) => FinalBlock(hashedBlock)
    }

  private val storeBlock: Pipe[F, FinalBlock, Unit] =
    _.evalMapLocked(blockStoringS) { fb =>
      storages.lastSnapshot.getHeight.map(_.getOrElse(Height.MinValue)).flatMap { lastSnapshotHeight =>
        if (lastSnapshotHeight < fb.hashedBlock.height)
          storages.block.store(fb.hashedBlock).handleErrorWith(e => logger.debug(e)("Block storing failed."))
        else
          logger.debug(
            s"Block can't be stored! Block height not above last snapshot height! block:${fb.hashedBlock.height} <= snapshot: $lastSnapshotHeight"
          )
      }
    }

  // Maximum number of undelivered blocks retained for re-send. Bounds memory if L0 is unreachable.
  private val maxL0ResendBuffer: Int = 1024
  private val maxL0BackfillBlocksPerGap: Int = 256
  private val l0OutputOutcomeLabel = Metrics.unsafeLabelName("outcome")
  private val l0OutputReasonLabel = Metrics.unsafeLabelName("reason")
  private val l0OutputSourceLabel = Metrics.unsafeLabelName("source")

  private def submissionOutcome(result: L1OutputSubmissionResult): String =
    result match {
      case Accepted                    => "accepted"
      case _: ParentOrdinalGapTooLarge => "parent_ordinal_gap_too_large"
      case _: Rejected                 => "rejected"
    }

  private def recordL0Submission(result: L1OutputSubmissionResult, source: String): F[Unit] =
    Metrics[F].incrementCounterBy(
      "dag_l1_l0_output_submission_total",
      1L,
      Seq(
        l0OutputOutcomeLabel -> submissionOutcome(result),
        l0OutputSourceLabel -> source
      )
    )

  /** Attempt a single delivery of a block to a collateralized L0 peer. Returns the classified response so the L1 can distinguish
    * gap-rejections from generic transport/non-2xx failures.
    */
  private def trySendToL0(block: Signed[Block]): F[L1OutputSubmissionResult] =
    storages.l0Cluster.getPeers
      .flatMap(_.toNonEmptyList.toList.filterA(p => services.collateral.hasCollateral(p.id)))
      .flatMap(peers => Random[F].shuffleList(peers))
      .map(_.headOption)
      .flatMap {
        case None         => logger.warn("No available L0 peer").as(Rejected(0, "NoAvailableL0Peer", ""): L1OutputSubmissionResult)
        case Some(l0Peer) => p2PClient.l0BlockOutputClient.sendL1OutputDetailed(block)(l0Peer)
      }
      .handleErrorWith(err =>
        logger.error(err)("Error sending block to L0").as(Rejected(0, "TransportError", err.getMessage): L1OutputSubmissionResult)
      )

  private def txParentOrdinals(block: Signed[Block]): List[Long] =
    block.value.transactions.toNonEmptyList.toList.map(_.value.parent.ordinal.value.value)

  private def lowestParentOrdinal(block: Signed[Block]): Long =
    txParentOrdinals(block).minOption.getOrElse(Long.MaxValue)

  private def storedSignedBlock(stored: StoredBlock): Option[Signed[Block]] =
    stored match {
      case WaitingBlock(block)   => Some(block)
      case PostponedBlock(block) => Some(block)
      case AcceptedBlock(block)  => Some(block.signed)
      case _: MajorityBlock      => None
    }

  private def appendToL0ResendBuffer(blocks: List[Signed[Block]], reason: String): F[Unit] =
    if (blocks.isEmpty) Async[F].unit
    else
      l0ResendBuffer.modify { buf =>
        val merged = (buf ++ blocks).distinct.sortBy(lowestParentOrdinal)
        val overflow = (merged.size - maxL0ResendBuffer).max(0)
        val retained = merged.take(maxL0ResendBuffer)

        (retained, (overflow, retained.size))
      }.flatMap {
        case (dropped, retainedSize) =>
          logger
            .warn(
              s"L0 re-send buffer full ($maxL0ResendBuffer): dropped $dropped furthest-ahead undelivered block(s). " +
                "GL0 is likely badly unhealthy (deep finalization stall) and may need a restart."
            )
            .whenA(dropped > 0) >>
            Metrics[F].incrementCounterBy(
              "dag_l1_l0_output_buffered_total",
              blocks.size.toLong,
              Seq(l0OutputReasonLabel -> reason)
            ) >>
            Metrics[F]
              .incrementCounterBy(
                "dag_l1_l0_output_buffer_overflow_dropped_total",
                dropped.toLong,
                Seq(l0OutputReasonLabel -> reason)
              )
              .whenA(dropped > 0) >>
            Metrics[F].updateGauge("dag_l1_l0_output_resend_buffer_size", retainedSize.toLong) >>
            logger.debug(s"Buffered ${blocks.size} block(s) for L0 re-send; reason=$reason")
      }

  private def bufferBackfillForGap(failedBlock: Signed[Block], gap: ParentOrdinalGapTooLarge): F[Unit] = {
    val lower = gap.currentLastTxOrdinal
    val upper = math.min(gap.parentOrdinal - 1L, gap.currentLastTxOrdinal + gap.maxAcceptedParentOrdinalGap)
    val gapSources = failedBlock.value.transactions.toNonEmptyList.toList
      .filter(_.value.parent.ordinal.value.value === gap.parentOrdinal)
      .map(_.value.source)
      .toSet

    storages.block
      .getState()
      .map(_.values.toList.flatMap(storedSignedBlock))
      .map { blocks =>
        blocks.filter { block =>
          block.value.transactions.toNonEmptyList.toList.exists { tx =>
            val ordinal = tx.value.parent.ordinal.value.value
            val sourceMatches = gapSources.isEmpty || gapSources.contains(tx.value.source)

            sourceMatches && ordinal > lower && ordinal <= upper
          }
        }
          .sortBy(lowestParentOrdinal)
          .take(maxL0BackfillBlocksPerGap)
      }
      .flatMap { backfill =>
        val toBuffer = (backfill :+ failedBlock).distinct.sortBy(lowestParentOrdinal)

        logger.info(
          s"L0 rejected DAG block with ParentOrdinalGapTooLarge: currentLastTxOrdinal=${gap.currentLastTxOrdinal} " +
            s"parentOrdinal=${gap.parentOrdinal} gap=${gap.parentOrdinalGap} maxAcceptedGap=${gap.maxAcceptedParentOrdinalGap}. " +
            s"Buffered ${backfill.size} locally stored bridge block(s) plus failed block; scanWindow=(${lower + 1}..$upper) " +
            s"sourceScoped=${gapSources.nonEmpty} backfillCap=$maxL0BackfillBlocksPerGap."
        ) >>
          Metrics[F].incrementCounterBy(
            "dag_l1_l0_output_backfill_blocks_total",
            backfill.size.toLong,
            Seq(l0OutputReasonLabel -> "parent_ordinal_gap_too_large")
          ) >>
          Metrics[F].updateGauge("dag_l1_l0_output_parent_gap", gap.parentOrdinalGap.toLong) >>
          Metrics[F].updateGauge("dag_l1_l0_output_backfill_scan_window_size", math.max(0L, upper - lower)) >>
          appendToL0ResendBuffer(toBuffer, "parent_gap_backfill")
      }
  }

  private def bufferFailedL0Delivery(block: Signed[Block], result: L1OutputSubmissionResult): F[Unit] =
    result match {
      case Accepted => Async[F].unit
      case gap: ParentOrdinalGapTooLarge =>
        bufferBackfillForGap(block, gap)
      case _: Rejected =>
        appendToL0ResendBuffer(List(block), "delivery_failed")
    }

  // Send each finalized block to L0 once. On failure the block is NOT dropped -- dropping leaves a
  // permanent ordinal gap that L0 can never advance past once its finalization falls behind -- but is
  // retained for ordered re-send by `resendToL0`. Local storage downstream is unaffected.
  private val sendBlockToL0: Pipe[F, FinalBlock, FinalBlock] =
    _.evalTap { fb =>
      val block = fb.hashedBlock.signed
      trySendToL0(block).flatMap {
        case Accepted => recordL0Submission(Accepted, "initial")
        case result   => recordL0Submission(result, "initial") >> bufferFailedL0Delivery(block, result)
      }
    }

  // Re-deliver buffered blocks oldest-first as L0's finalization frontier catches up. Stops at the
  // first failure each tick -- for the observed ParentOrdinalGapTooLarge wedge the head is the binding
  // block, so this paces re-delivery to the frontier. (Classifying the failure to stop only on a gap
  // reject, and trying another L0 peer on transport errors, is a follow-up.) Caps per-tick work.
  private val resendToL0: Stream[F, Unit] =
    Stream.awakeEvery(2.seconds).evalMap { _ =>
      def loop(remaining: Int): F[Unit] =
        if (remaining <= 0) Async[F].unit
        else
          l0ResendBuffer.get.map(_.headOption).flatMap {
            case None => Async[F].unit
            case Some(block) =>
              trySendToL0(block).flatMap {
                case Accepted =>
                  // Remove the just-delivered block only if it is still the head, so a concurrent
                  // overflow drop+append on the failure path cannot make us delete a different,
                  // not-yet-sent block.
                  recordL0Submission(Accepted, "resend") >>
                    l0ResendBuffer.update {
                      case buf if buf.headOption.contains(block) => buf.drop(1)
                      case buf                                   => buf
                    } >>
                    l0ResendBuffer.get.flatMap(buf => Metrics[F].updateGauge("dag_l1_l0_output_resend_buffer_size", buf.size.toLong)) >>
                    loop(remaining - 1)
                case result => recordL0Submission(result, "resend") >> bufferFailedL0Delivery(block, result)
              }
          }
      loop(64)
    }

  private val blockAcceptance: Stream[F, Unit] = Stream
    .awakeEvery(1.seconds)
    .evalMapLocked(blockAcceptanceS) { _ =>
      storages.block.getWaiting.flatTap { awaiting =>
        if (awaiting.nonEmpty) logger.debug(s"Pulled following blocks for acceptance ${awaiting.keySet}")
        else Async[F].unit
      }.flatMap(
        _.toList
          .sortBy(_._2.value.height)
          .traverse {
            case (hash, signedBlock) =>
              logger.debug(s"Acceptance of a block $hash starts!") >>
                HasherSelector[F].withCurrent { implicit hasher =>
                  services.block
                    .accept(signedBlock)
                }.handleErrorWith { error =>
                  for {
                    _ <- logger.warn(error)(s"Failed acceptance of a block with ${hash.show}")
                    _ <- storages.globalL0Alignment.updateShouldRedownload(
                      value = true,
                      reasons = List(s"Block acceptance failed for ${hash.show}: ${error.getMessage}")
                    )
                  } yield ()
                }
          }
          .void
      )
    }

  private val blockConsensus: Stream[F, Unit] =
    blockConsensusInputs
      .through(runConsensus)
      .through(gossipBlock)
      .through(sendBlockToL0)
      .merge(peerBlocks)
      .through(storeBlock)

  val runtime: Stream[F, Unit] =
    blockConsensus
      .merge(blockAcceptance)
      .merge(resendToL0)

}

object StateChannel {

  def make[
    F[_]: Async: HasherSelector: SecurityProvider: Random: Metrics,
    P <: StateProof,
    S <: Snapshot,
    SI <: SnapshotInfo[P],
    R <: CliMethod
  ](
    appConfig: AppConfig,
    keyPair: KeyPair,
    p2PClient: P2PClient[F],
    programs: Programs[F, P, S, SI],
    queues: Queues[F],
    selfId: PeerId,
    services: Services[F, P, S, SI, R],
    storages: Storages[F, P, S, SI],
    validators: Validators[F],
    txHasher: Hasher[F]
  ): F[StateChannel[F, P, S, SI, R]] =
    for {
      blockAcceptanceS <- Semaphore(1)
      blockCreationS <- Semaphore(1)
      blockStoringS <- Semaphore(1)
      l0ResendBuffer <- Ref.of[F, Vector[Signed[Block]]](Vector.empty)
    } yield
      new StateChannel[F, P, S, SI, R](
        appConfig,
        blockAcceptanceS,
        blockCreationS,
        blockStoringS,
        l0ResendBuffer,
        keyPair,
        p2PClient,
        programs,
        queues,
        selfId,
        services,
        storages,
        validators,
        txHasher
      )
}
