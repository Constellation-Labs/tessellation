package io.constellationnetwork.dag.l0.infrastructure.snapshot

import cats.data.NonEmptySet
import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.{SortedMap, SortedSet}

import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus
import io.constellationnetwork.node.shared.infrastructure.consensus.CertifiedConsensus._
import io.constellationnetwork.node.shared.infrastructure.consensus.state.{EligibleFacilitators, Facilitators}
import io.constellationnetwork.node.shared.infrastructure.consensus.trigger.EventTrigger
import io.constellationnetwork.schema._
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.epoch.EpochProgress
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.key.ops._
import io.constellationnetwork.security.signature.Signed

import eu.timepit.refined.types.numeric.NonNegLong
import weaver.MutableIOSuite

object GlobalCertifiedDownloadValidatorSuite extends MutableIOSuite {

  implicit val globalStateProofSelector: GlobalStateProofSelector =
    GlobalStateProofSelector(SnapshotOrdinal(NonNegLong(Long.MaxValue)))

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(securityProvider: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    implicit0(jsonSerializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    hasher = Hasher.forJson[IO]
  } yield (jsonSerializer, hasher, securityProvider)

  private def canonicalRoot(
    implicit jsonSerializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ) =
    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      genesis = GlobalSnapshot.mkGenesis(Map.empty, EpochProgress.MinValue)
      signedGenesis <- Signed.forAsyncHasher[IO, GlobalSnapshot](genesis, keyPair)
      hashedGenesis <- signedGenesis.toHashed[IO]
      incremental <- GlobalSnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
      signedIncremental <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](incremental, keyPair)
      snapshotHash <- signedIncremental.toHashed.map(_.hash)
      committee = SortedSet.from(signedIncremental.proofs.toList.map(_.id.toPeerId))
    } yield
      GlobalRecoverySeedOutcome.seed(
        signedIncremental,
        signedGenesis.value.info.toGlobalSnapshotInfo,
        snapshotHash,
        committee
      )

  private def typedArtifactHasher(logic: HashLogic, expected: Any => Boolean, result: Hash): Hasher[IO] = new Hasher[IO] {
    def hash[A: io.circe.Encoder](data: A): IO[Hash] = IO.pure(if (expected(data)) result else Hash.empty)
    def hashBytes(bytes: Array[Byte]): IO[Hash] = IO.pure(Hash.empty)
    def compare[A: io.circe.Encoder](data: A, expectedHash: Hash): IO[Boolean] = hash(data).map(_ === expectedHash)
    def getLogic(ordinal: SnapshotOrdinal): HashLogic = logic
    def prefixedHash[A: io.circe.Encoder](data: A, prefix: Array[Byte]): IO[Hash] = hash(data)
  }

  test("canonical root shape is not cache authority and genesis authority is explicitly validated") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    for {
      root <- canonicalRoot
      substituteKey <- KeyPairGenerator.makeKeyPair[IO]
      substitute = PeerId.fromPublic(substituteKey.getPublic)
      wrongEligible =
        root.copy(eligibleFacilitators = io.constellationnetwork.node.shared.infrastructure.consensus.state.EligibleFacilitators.empty)
      wrongHash = root.copy(finished = root.finished.copy(facilitatorsHash = Hash.fromBytes(Array[Byte](1))))
      missingProofWindow = root.copy(recentProofSizes = scala.collection.immutable.SortedMap.empty)
      substitutedCommittee = root.copy(
        facilitators = Facilitators(List(substitute)),
        eligibleFacilitators = EligibleFacilitators(List(substitute))
      )
      invalidSignedArtifact = root.finished.signedMajorityArtifact.copy(
        proofs = NonEmptySet.fromSetUnsafe(
          SortedSet.from(root.finished.signedMajorityArtifact.proofs.toList.map(_.copy(id = substituteKey.getPublic.toId)))
        )
      )
      invalidSignatureRoot = root.copy(finished = root.finished.copy(signedMajorityArtifact = invalidSignedArtifact))
      differentContext = root.finished.context.copy(
        balances = SortedMap(Address.fromBytes("different-context".getBytes("UTF-8")) -> Balance.empty)
      )
      contextMismatchRoot = root.copy(finished = root.finished.copy(context = differentContext))
      validGenesis <- GlobalCertifiedDownloadValidator.validateGenesisRoot[IO](
        root,
        root.finished.signedMajorityArtifact,
        root.finished.context
      )
      rootSigners = root.finished.signedMajorityArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId).toSet
      authorizedGenesis <- GlobalCertifiedDownloadValidator.validateGenesisRoot[IO](
        root,
        root.finished.signedMajorityArtifact,
        root.finished.context,
        rootSigners
      )
      unauthorizedGenesis <- GlobalCertifiedDownloadValidator.validateGenesisRoot[IO](
        root,
        root.finished.signedMajorityArtifact,
        root.finished.context,
        Set(substitute)
      )
      invalidSignatureGenesis <- GlobalCertifiedDownloadValidator.validateGenesisRoot[IO](
        invalidSignatureRoot,
        root.finished.signedMajorityArtifact,
        root.finished.context
      )
      contextMismatchGenesis <- GlobalCertifiedDownloadValidator.validateGenesisRoot[IO](
        contextMismatchRoot,
        root.finished.signedMajorityArtifact,
        root.finished.context
      )
      substitutedGenesis <- GlobalCertifiedDownloadValidator.validateGenesisRoot[IO](
        substitutedCommittee,
        root.finished.signedMajorityArtifact,
        root.finished.context
      )
    } yield
      expect(GlobalRecoverySeedOutcome.isCanonicalRoot(root)) &&
        expect(!GlobalRecoverySeedOutcome.isCanonicalRoot(wrongEligible)) &&
        expect(!GlobalRecoverySeedOutcome.isCanonicalRoot(wrongHash)) &&
        expect(!GlobalRecoverySeedOutcome.isCanonicalRoot(missingProofWindow)) &&
        expect.same(Right(()), validGenesis) &&
        expect.same(Right(()), authorizedGenesis) &&
        expect.same(Left("genesis_artifact_signer_not_seedlisted"), unauthorizedGenesis) &&
        expect.same(Left("genesis_artifact_signature_invalid"), invalidSignatureGenesis) &&
        expect.same(Left("genesis_context_state_proof_mismatch"), contextMismatchGenesis) &&
        expect.same(Left("genesis_outcome_not_proof_signer_root"), substitutedGenesis)
  }

  test("the shared artifact hasher preserves the historical V1 projection and current typed artifact") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    canonicalRoot.flatMap { root =>
      val historicalHash = Hash.fromBytes(Array[Byte](11))
      val currentHash = Hash.fromBytes(Array[Byte](22))
      val historical =
        typedArtifactHasher(KryoHash, _.isInstanceOf[io.constellationnetwork.schema.GlobalIncrementalSnapshotV1], historicalHash)
      val current = typedArtifactHasher(JsonHash, _.isInstanceOf[GlobalIncrementalSnapshot], currentHash)

      (
        GlobalSnapshotArtifactHasher.historicalHash[IO](root.finished.signedMajorityArtifact.value)(historical),
        GlobalSnapshotArtifactHasher.currentHash[IO](root.finished.signedMajorityArtifact.value)(current)
      ).mapN { (historicalResult, currentResult) =>
        expect.same(historicalHash, historicalResult) && expect.same(currentHash, currentResult)
      }
    }
  }

  test("proof-derived genesis authority is bound to the locally validated signed snapshot") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    for {
      trusted <- canonicalRoot
      substituteKey <- KeyPairGenerator.makeKeyPair[IO]
      substitutedArtifact <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](
        trusted.finished.signedMajorityArtifact.value,
        substituteKey
      )
      substitutedHash <- GlobalSnapshotArtifactHasher.currentHash[IO](substitutedArtifact.value)
      substitutedCommittee = SortedSet.from(substitutedArtifact.proofs.toList.map(_.id.toPeerId))
      substituted = GlobalRecoverySeedOutcome.seed(
        substitutedArtifact,
        trusted.finished.context,
        substitutedHash,
        substitutedCommittee
      )
      selfConsistent <- GlobalCertifiedDownloadValidator.validateGenesisRoot[IO](
        substituted,
        substitutedArtifact,
        trusted.finished.context
      )
      locallyBound <- GlobalCertifiedDownloadValidator.validateGenesisRoot[IO](
        substituted,
        trusted.finished.signedMajorityArtifact,
        trusted.finished.context
      )
    } yield
      expect.same(Right(()), selfConsistent) &&
        expect.same(Left("genesis_artifact_not_locally_validated"), locallyBound)
  }

  test("ordinal-gated activation authenticates the A-1 envelope, unique signers, and context") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    for {
      root <- canonicalRoot
      artifact = root.finished.signedMajorityArtifact
      context = root.finished.context
      substituteKey <- KeyPairGenerator.makeKeyPair[IO]
      invalidArtifact = artifact.copy(
        proofs = NonEmptySet.fromSetUnsafe(
          SortedSet.from(artifact.proofs.toList.map(_.copy(id = substituteKey.getPublic.toId)))
        )
      )
      firstReSigned <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](artifact.value, substituteKey)
      secondReSigned <- Signed.forAsyncHasher[IO, GlobalIncrementalSnapshot](artifact.value, substituteKey)
      duplicateArtifact = artifact.copy(
        proofs = NonEmptySet.fromSetUnsafe(SortedSet.from(firstReSigned.proofs.toList ++ secondReSigned.proofs.toList))
      )
      differentContext = context.copy(
        balances = SortedMap(Address.fromBytes("different-activation-context".getBytes("UTF-8")) -> Balance.empty)
      )
      wrongOrdinalArtifact = artifact.copy(
        value = artifact.value.copy(ordinal = SnapshotOrdinal.unsafeApply(artifact.ordinal.value.value + 1L))
      )
      valid <- GlobalCertifiedDownloadValidator.validateActivationRootArtifact[IO](artifact.ordinal, artifact, context)
      wrongOrdinal <- GlobalCertifiedDownloadValidator.validateActivationRootArtifact[IO](
        artifact.ordinal,
        wrongOrdinalArtifact,
        context
      )
      invalidSignature <- GlobalCertifiedDownloadValidator.validateActivationRootArtifact[IO](
        artifact.ordinal,
        invalidArtifact,
        context
      )
      duplicateSigner <- GlobalCertifiedDownloadValidator.validateActivationRootArtifact[IO](
        artifact.ordinal,
        duplicateArtifact,
        context
      )
      contextMismatch <- GlobalCertifiedDownloadValidator.validateActivationRootArtifact[IO](
        artifact.ordinal,
        artifact,
        differentContext
      )
    } yield
      expect.same(Right(()), valid) &&
        expect.same(Left("activation_artifact_ordinal_mismatch"), wrongOrdinal) &&
        expect.same(Left("activation_artifact_signature_invalid"), invalidSignature) &&
        expect.same(Left("activation_artifact_duplicate_signer"), duplicateSigner) &&
        expect.same(Left("activation_context_state_proof_mismatch"), contextMismatch)
  }

  test("exact activation reconstructs Finished.snapshotHash with current hashing across a historical parent boundary") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    canonicalRoot.flatMap { root =>
      val historicalHash = Hash.fromBytes(Array[Byte](31))
      val currentHash = Hash.fromBytes(Array[Byte](32))
      val historical =
        typedArtifactHasher(KryoHash, _.isInstanceOf[GlobalIncrementalSnapshotV1], historicalHash)
      val current = typedArtifactHasher(JsonHash, _.isInstanceOf[GlobalIncrementalSnapshot], currentHash)
      implicit val hasherSelector: HasherSelector[IO] = new HasherSelector[IO] {
        def getForOrdinal(ordinal: SnapshotOrdinal): Hasher[IO] = historical
        def getCurrent: Hasher[IO] = current
      }

      (
        GlobalCertifiedDownloadValidator.reconstructActivationParentFinished[IO](
          root.finished.signedMajorityArtifact,
          root.finished.context
        ),
        hasherSelector.forOrdinal(root.key)(implicit selected =>
          GlobalSnapshotArtifactHasher.historicalHash[IO](root.finished.signedMajorityArtifact.value)(selected)
        )
      ).mapN { (finished, parentLinkHash) =>
        expect.same(currentHash, finished.snapshotHash) &&
        expect.same(historicalHash, parentLinkHash)
      }
    }
  }

  test("historical authority continuity rejects independently valid full and Core substitutions") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res

    for {
      pairs <- List.fill(4)(KeyPairGenerator.makeKeyPair[IO]).sequence
      ids = pairs.map(keyPair => PeerId.fromPublic(keyPair.getPublic))
      full = NonEmptySet.fromSetUnsafe(SortedSet.from(ids))
      core = NonEmptySet.fromSetUnsafe(SortedSet.from(ids.take(3)))
      authority <- CertifiedConsensus.roundAuthority[IO](full, core)
      value = ProposalValue(
        SchemaVersion,
        ConsensusDomain.DagL0,
        "integrationnet",
        1L,
        Hash.empty,
        Hash.empty,
        Hash.empty,
        full,
        authority.facilitatorsHash,
        core,
        authority.coreHash,
        authority,
        Hash.empty,
        0L,
        EventTrigger,
        None,
        SortedSet.empty,
        SortedSet.empty,
        SortedSet.from(ids),
        SortedMap.empty,
        SortedSet.empty,
        None
      )
      substitutedFull = NonEmptySet.fromSetUnsafe(SortedSet.from(ids.dropRight(1)))
      substitutedCore = NonEmptySet.fromSetUnsafe(SortedSet.from(ids.drop(1)))
      valid = GlobalCertifiedDownloadValidator.validateAuthorityContinuity(value, authority)
      wrongFull = GlobalCertifiedDownloadValidator.validateAuthorityContinuity(
        value.copy(roundStartFacilitators = substitutedFull),
        authority
      )
      wrongCore = GlobalCertifiedDownloadValidator.validateAuthorityContinuity(
        value.copy(roundStartCore = substitutedCore),
        authority
      )
    } yield
      expect.all(
        valid === Right(()),
        wrongFull === Left("certified_authority_full_continuity_mismatch"),
        wrongCore === Left("certified_authority_core_continuity_mismatch")
      )
  }

}
