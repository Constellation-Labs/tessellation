package io.constellationnetwork.tools

import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files => JFiles, Path => JPath, StandardOpenOption}

import cats.effect.Async
import cats.syntax.all._

import scala.collection.immutable.SortedSet
import scala.util.control.NoStackTrace

import io.constellationnetwork.dag.l0.domain.snapshot.recovery.{Gl0RecoveryPlan, RecoveryCheckpoint}
import io.constellationnetwork.keytool.KeyStoreUtils
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.{Signed, SignedValidator}
import io.constellationnetwork.security.{Hasher, SecurityProvider}
import io.constellationnetwork.tools.cli.method.GenerateGl0RecoveryPlanCmd

import io.circe.parser.decode
import io.circe.syntax._

/** Offline generator for the operator-authorized GL0 recovery plan.
  *
  * It deliberately reuses `Signed`, the repository JSON codec, and the configured JSON `Hasher`; there is no plan-specific byte encoding or
  * signature scheme. The generated JSON is decoded and signature-verified before an exclusive atomic write. Passwords come from the same
  * `CL_PASSWORD` input as node startup and are never logged.
  */
object Gl0RecoveryPlanGenerator {

  final case class InvalidGeneratorInput(reason: String) extends NoStackTrace {
    override def getMessage: String = s"invalid GL0 recovery-plan generator input: $reason"
  }

  private val HashPattern = "[0-9a-f]{64}".r
  private val PeerIdPattern = "[0-9a-f]{128}".r

  private[tools] def validateInputs(
    network: String,
    snapshotHash: Hash,
    planId: Hash,
    lead: PeerId,
    committee: SortedSet[PeerId]
  ): Either[InvalidGeneratorInput, Unit] =
    Either
      .cond(network.nonEmpty && network === network.trim, (), InvalidGeneratorInput("network must be non-empty with no surrounding space"))
      .flatMap(_ =>
        Either.cond(HashPattern.matches(snapshotHash.value), (), InvalidGeneratorInput("snapshot hash must be 64 lowercase hex characters"))
      )
      .flatMap(_ =>
        Either.cond(Gl0RecoveryPlan.isCanonicalPlanId(planId), (), InvalidGeneratorInput("plan id must be 64 lowercase hex characters"))
      )
      .flatMap(_ =>
        Either.cond(PeerIdPattern.matches(lead.value.value), (), InvalidGeneratorInput("lead PeerId must be 128 lowercase hex characters"))
      )
      .flatMap(_ =>
        Either.cond(
          committee.forall(peer => PeerIdPattern.matches(peer.value.value)),
          (),
          InvalidGeneratorInput("every committee PeerId must be 128 lowercase hex characters")
        )
      )
      .flatMap(_ =>
        Either.cond(
          committee.size >= Gl0RecoveryPlan.MinimumCommitteeSize,
          (),
          InvalidGeneratorInput(
            s"committee has ${committee.size} unique members; minimum is ${Gl0RecoveryPlan.MinimumCommitteeSize}"
          )
        )
      )
      .flatMap(_ => Either.cond(committee.contains(lead), (), InvalidGeneratorInput("lead key PeerId is not in committee")))

  /** Publish a complete, already-verified plan without ever exposing partial JSON.
    *
    * This intentionally differs from the consumed-receipt `CREATE_NEW` primitive: a partial receipt is conservatively sufficient to burn
    * authority after a crash, while a plan file must be fully written before it becomes visible to node startup.
    */
  private def writeNewAtomically[F[_]: Async](target: JPath, bytes: Array[Byte]): F[Unit] =
    Async[F].blocking {
      val absolute = target.toAbsolutePath.normalize()
      val parent = Option(absolute.getParent).getOrElse(throw InvalidGeneratorInput("output has no parent directory"))
      JFiles.createDirectories(parent)
      if (JFiles.exists(absolute)) throw new java.nio.file.FileAlreadyExistsException(absolute.toString)

      val temporary = JFiles.createTempFile(parent, s".${absolute.getFileName.toString}.", ".tmp")
      try {
        JFiles.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        val fileChannel = FileChannel.open(temporary, StandardOpenOption.WRITE)
        try fileChannel.force(true)
        finally fileChannel.close()

        JFiles.createLink(absolute, temporary)
        JFiles.delete(temporary)

        try {
          val directoryChannel = FileChannel.open(parent, StandardOpenOption.READ)
          try directoryChannel.force(true)
          finally directoryChannel.close()
        } catch {
          case _: UnsupportedOperationException => ()
        }
      } finally {
        JFiles.deleteIfExists(temporary)
        ()
      }
    }

  def generate[F[_]: Async: Hasher: SecurityProvider](command: GenerateGl0RecoveryPlanCmd): F[JPath] =
    for {
      keyPair <- KeyStoreUtils.readKeyPairFromStore[F](
        command.keyStore.value.toString,
        command.alias.value.value,
        command.password.value.value.toCharArray,
        command.password.value.value.toCharArray
      )
      lead = PeerId.fromPublic(keyPair.getPublic)
      committee = SortedSet.from(command.committee.toList)
      _ <- validateInputs(command.network, command.snapshotHash, command.planId, lead, committee).liftTo[F]
      plan = Gl0RecoveryPlan(
        Gl0RecoveryPlan.CurrentProtocol,
        Gl0RecoveryPlan.CurrentFormatVersion,
        command.planId,
        RecoveryCheckpoint(command.network, SnapshotOrdinal(command.ordinal), command.snapshotHash),
        lead,
        committee
      )
      signed <- Signed.forAsyncHasher[F, Gl0RecoveryPlan](plan, keyPair)
      json = signed.asJson.noSpaces
      // Exercise the exact persisted decoder and verifier before making the file visible.
      decoded <- decode[Signed[Gl0RecoveryPlan]](json).liftTo[F]
      verified <- Gl0RecoveryPlan
        .verify(
          SignedValidator.make[F],
          command.network,
          lead,
          command.snapshotHash,
          committee,
          None,
          None,
          // The rc.8 bridge is generated for GL0's named supermajority mode. Each node independently
          // re-validates growth viability against its effective, deterministic configuration at startup.
          2.0 / 3.0,
          decoded
        )
        .flatMap(_.liftTo[F])
      _ <- Async[F].raiseUnless(verified === plan)(InvalidGeneratorInput("serialized plan did not round-trip to the same typed value"))
      output = command.output.toAbsolutePath.normalize()
      _ <- writeNewAtomically(output, json.getBytes(StandardCharsets.UTF_8))
    } yield output
}
