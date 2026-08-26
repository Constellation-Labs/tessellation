package io.constellationnetwork.node.shared.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.currency.dataApplication.FeeTransaction
import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.ext.cats.syntax.next.catsSyntaxNext
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.kryo.KryoSerializer
import io.constellationnetwork.node.shared.domain.rewards.Rewards
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.ConsensusTrigger
import io.constellationnetwork.node.shared.nodeSharedKryoRegistrar
import io.constellationnetwork.node.shared.snapshot.currency._
import io.constellationnetwork.schema.ID.Id
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.artifact.SharedArtifact
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.height.{Height, SubHeight}
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.schema.transaction.TransactionReference
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.security.signature.signature.{Signature, SignatureProof}

import eu.timepit.refined.auto._
import weaver.MutableIOSuite

object CurrencySnapshotValidatorAllowSpendModeSuite extends MutableIOSuite {

  type Res = (KryoSerializer[IO], JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  def sharedResource: Resource[IO, Res] =
    for {
      kryo <- KryoSerializer.forAsync[IO](nodeSharedKryoRegistrar)
      json <- JsonSerializer.forSync[IO].asResource
      securityProvider <- SecurityProvider.forAsync[IO]
    } yield {
      implicit val jsonSerializer: JsonSerializer[IO] = json
      (kryo, json, Hasher.forJson[IO], securityProvider)
    }

  private val address = Address("DAG011jH7FMDvKpdb7wewrMWwYtkwq56nHquAHdi")
  private val activation = SnapshotOrdinal.unsafeApply(100L)

  private def proofOf(seed: Int): NonEmptySet[SignatureProof] = {
    val hex = (seed.toString * 128).take(128)
    NonEmptySet.one(SignatureProof(Id(Hex(hex)), Signature(Hex(hex))))
  }

  private def info: CurrencySnapshotInfo =
    CurrencySnapshotInfo(
      SortedMap.empty[Address, TransactionReference],
      SortedMap.empty[Address, Balance],
      None,
      None,
      None,
      None,
      None,
      None,
      None
    )

  private def stateProof: CurrencySnapshotStateProof =
    CurrencySnapshotStateProof(Hash.empty, Hash.empty, None, None, None, None, None, None, None)

  private def artifact(ordinal: SnapshotOrdinal, globalViewOrdinal: SnapshotOrdinal, parentHash: Hash): CurrencyIncrementalSnapshot =
    CurrencyIncrementalSnapshot(
      ordinal,
      Height.MinValue,
      SubHeight.MinValue,
      parentHash,
      SortedSet.empty,
      SortedSet.empty,
      SnapshotTips(SortedSet.empty, SortedSet.empty),
      stateProof,
      EpochProgress.MinValue,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Some(GlobalSyncView(globalViewOrdinal, Hash.empty, EpochProgress.MinValue))
    )

  private def parent(globalViewOrdinal: SnapshotOrdinal): Signed[CurrencyIncrementalSnapshot] =
    Signed(artifact(SnapshotOrdinal(10L), globalViewOrdinal, Hash.empty), proofOf(9))

  private def recordingCreator(
    calls: Ref[IO, List[AllowSpendBlockAcceptanceMode]],
    resultFor: AllowSpendBlockAcceptanceMode => CurrencyIncrementalSnapshot
  ): CurrencySnapshotCreator[IO] =
    new CurrencySnapshotCreator[IO] {
      def createProposalArtifact(
        lastKey: SnapshotOrdinal,
        lastArtifact: Signed[CurrencySnapshotArtifact],
        lastContext: CurrencySnapshotContext,
        lastArtifactHasher: Hasher[IO],
        trigger: ConsensusTrigger,
        events: Set[CurrencySnapshotEvent],
        rewards: Option[Rewards[IO, CurrencySnapshotStateProof, CurrencyIncrementalSnapshot, CurrencySnapshotEvent]],
        facilitators: Set[PeerId],
        feeTransactionFn: Option[() => SortedSet[Signed[FeeTransaction]]],
        artifactsFn: Option[() => SortedSet[SharedArtifact]],
        getGlobalSnapshotByOrdinal: SnapshotOrdinal => IO[Option[Hashed[GlobalIncrementalSnapshot]]],
        shouldValidateCollateral: Boolean,
        maybeCustomArtifacts: Option[Signed[CurrencyIncrementalSnapshot] => Option[SortedSet[SharedArtifact]]],
        allowSpendBlockAcceptanceMode: AllowSpendBlockAcceptanceMode
      )(implicit hasher: Hasher[IO]): IO[CurrencySnapshotCreationResult[CurrencySnapshotEvent]] =
        calls
          .update(_ :+ allowSpendBlockAcceptanceMode)
          .as(
            CurrencySnapshotCreationResult(
              resultFor(allowSpendBlockAcceptanceMode),
              lastContext,
              Set.empty,
              Set.empty
            )
          )
    }

  private def validator(
    calls: Ref[IO, List[AllowSpendBlockAcceptanceMode]],
    resultFor: AllowSpendBlockAcceptanceMode => CurrencyIncrementalSnapshot
  )(implicit kryo: KryoSerializer[IO], json: JsonSerializer[IO], securityProvider: SecurityProvider[IO]): CurrencySnapshotValidator[IO] =
    CurrencySnapshotValidator.make[IO](
      SnapshotOrdinal.MinValue,
      recordingCreator(calls, resultFor),
      io.constellationnetwork.security.signature.SignedValidator.make[IO],
      None,
      None,
      activation
    )

  private def distinctCalls(calls: List[AllowSpendBlockAcceptanceMode]): List[AllowSpendBlockAcceptanceMode] =
    calls.foldLeft(List.empty[AllowSpendBlockAcceptanceMode]) { (acc, mode) =>
      if (acc.contains(mode)) acc else acc :+ mode
    }

  test("live artifact validation never attempts legacy semantics") { res =>
    implicit val (kryo, json, hasher, securityProvider) = res
    val oldParent = parent(SnapshotOrdinal.unsafeApply(99L))
    val expected = artifact(oldParent.ordinal.next, SnapshotOrdinal.unsafeApply(99L), Hash.empty)

    for {
      calls <- Ref.of[IO, List[AllowSpendBlockAcceptanceMode]](List.empty)
      snapshotValidator = validator(calls, _ => expected)
      result <- snapshotValidator
        .validateSnapshot(
          oldParent,
          CurrencySnapshotContext(address, info),
          expected,
          Set.empty,
          _ => none[Hashed[GlobalIncrementalSnapshot]].pure[IO]
        )
      observed <- calls.get
    } yield
      expect.all(
        distinctCalls(observed) == List(AllowSpendBlockAcceptanceMode.Escrow),
        result.isValid
      )
  }

  test("signed historical replay below activation can reproduce legacy semantics") { res =>
    implicit val (kryo, json, hasher, securityProvider) = res
    val oldParent = parent(SnapshotOrdinal.unsafeApply(99L))
    val expected = artifact(oldParent.ordinal.next, SnapshotOrdinal.unsafeApply(99L), Hash.empty)
    val mismatch = expected.copy(lastSnapshotHash = Hash("escrow"))

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      signedExpected <- Signed.forAsyncHasher(expected, keyPair)
      calls <- Ref.of[IO, List[AllowSpendBlockAcceptanceMode]](List.empty)
      snapshotValidator = validator(
        calls,
        {
          case AllowSpendBlockAcceptanceMode.LegacyCreditDestination => expected
          case AllowSpendBlockAcceptanceMode.Escrow                  => mismatch
        }
      )
      result <- snapshotValidator
        .validateSignedSnapshot(
          oldParent,
          CurrencySnapshotContext(address, info),
          signedExpected,
          _ => none[Hashed[GlobalIncrementalSnapshot]].pure[IO]
        )
      observed <- calls.get
    } yield
      expect.all(
        distinctCalls(observed) == List(
          AllowSpendBlockAcceptanceMode.Escrow,
          AllowSpendBlockAcceptanceMode.LegacyCreditDestination
        ),
        result.isValid
      )
  }

  test("signed replay below activation uses escrow first for a new artifact") { res =>
    implicit val (kryo, json, hasher, securityProvider) = res
    val oldParent = parent(SnapshotOrdinal.unsafeApply(99L))
    val expected = artifact(oldParent.ordinal.next, SnapshotOrdinal.unsafeApply(99L), Hash.empty)
    val legacyMismatch = expected.copy(lastSnapshotHash = Hash("legacy"))

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      signedExpected <- Signed.forAsyncHasher(expected, keyPair)
      calls <- Ref.of[IO, List[AllowSpendBlockAcceptanceMode]](List.empty)
      snapshotValidator = validator(
        calls,
        {
          case AllowSpendBlockAcceptanceMode.LegacyCreditDestination => legacyMismatch
          case AllowSpendBlockAcceptanceMode.Escrow                  => expected
        }
      )
      result <- snapshotValidator
        .validateSignedSnapshot(
          oldParent,
          CurrencySnapshotContext(address, info),
          signedExpected,
          _ => none[Hashed[GlobalIncrementalSnapshot]].pure[IO]
        )
      observed <- calls.get
    } yield
      expect(
        distinctCalls(observed) == List(AllowSpendBlockAcceptanceMode.Escrow)
      ) && expect(result.isValid)
  }

  test("signed replay at activation never attempts legacy semantics") { res =>
    implicit val (kryo, json, hasher, securityProvider) = res
    val activatedParent = parent(activation)
    val expectedLegacy = artifact(activatedParent.ordinal.next, activation, Hash.empty)
    val escrowMismatch = expectedLegacy.copy(lastSnapshotHash = Hash("escrow"))

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      signedExpected <- Signed.forAsyncHasher(expectedLegacy, keyPair)
      calls <- Ref.of[IO, List[AllowSpendBlockAcceptanceMode]](List.empty)
      snapshotValidator = validator(calls, _ => escrowMismatch)
      result <- snapshotValidator.validateSignedSnapshot(
        activatedParent,
        CurrencySnapshotContext(address, info),
        signedExpected,
        _ => none[Hashed[GlobalIncrementalSnapshot]].pure[IO]
      )
      observed <- calls.get
    } yield
      expect.all(
        distinctCalls(observed) == List(AllowSpendBlockAcceptanceMode.Escrow),
        result.isInvalid
      )
  }

  test("invalid signed historical artifact is rejected before any replay mode is attempted") { res =>
    implicit val (kryo, json, hasher, securityProvider) = res
    val oldParent = parent(SnapshotOrdinal.unsafeApply(99L))
    val expected = artifact(oldParent.ordinal.next, SnapshotOrdinal.unsafeApply(99L), Hash.empty)
    val invalidSignedExpected = Signed(expected, proofOf(1))

    for {
      calls <- Ref.of[IO, List[AllowSpendBlockAcceptanceMode]](List.empty)
      snapshotValidator = validator(calls, _ => expected)
      result <- snapshotValidator.validateSignedSnapshot(
        oldParent,
        CurrencySnapshotContext(address, info),
        invalidSignedExpected,
        _ => none[Hashed[GlobalIncrementalSnapshot]].pure[IO]
      )
      observed <- calls.get
    } yield expect.all(result.isInvalid, observed.isEmpty)
  }
}
