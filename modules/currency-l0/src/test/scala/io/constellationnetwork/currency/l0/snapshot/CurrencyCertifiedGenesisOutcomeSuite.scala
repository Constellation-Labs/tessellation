package io.constellationnetwork.currency.l0.snapshot

import cats.effect.{IO, Resource}
import cats.syntax.all._

import scala.collection.immutable.SortedMap

import io.constellationnetwork.currency.schema.currency._
import io.constellationnetwork.ext.cats.effect.ResourceIO
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.consensus.state.Facilitators
import io.constellationnetwork.schema.CurrencyStateProofSelector
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.schema.peer.PeerId
import io.constellationnetwork.security._
import io.constellationnetwork.security.hash.Hash
import io.constellationnetwork.security.signature.Signed
import io.constellationnetwork.statechannel.StateChannelSnapshotBinary

import weaver.MutableIOSuite

object CurrencyCertifiedGenesisOutcomeSuite extends MutableIOSuite {

  implicit val currencyStateProofSelector: CurrencyStateProofSelector = CurrencyStateProofSelector.instance

  type Res = (JsonSerializer[IO], Hasher[IO], SecurityProvider[IO])

  override def sharedResource: Resource[IO, Res] = for {
    implicit0(securityProvider: SecurityProvider[IO]) <- SecurityProvider.forAsync[IO]
    implicit0(jsonSerializer: JsonSerializer[IO]) <- JsonSerializer.forAsync[IO].asResource
    hasher = Hasher.forJson[IO]
  } yield (jsonSerializer, hasher, securityProvider)

  private val address = Address.fromBytes(Array[Byte](1, 2, 3))

  private def canonicalRoot(artifactKey: java.security.KeyPair, binaryKey: java.security.KeyPair)(
    implicit jsonSerializer: JsonSerializer[IO],
    hasher: Hasher[IO],
    securityProvider: SecurityProvider[IO]
  ) = {
    val genesis = CurrencySnapshot.mkGenesis(Map.empty, None, None)

    for {
      signedGenesis <- Signed.forAsyncHasher[IO, CurrencySnapshot](genesis, artifactKey)
      hashedGenesis <- signedGenesis.toHashed[IO]
      snapshotValue <- CurrencySnapshot.mkFirstIncrementalSnapshot[IO](hashedGenesis)
      snapshot <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](snapshotValue, artifactKey)
      binaryBytes <- jsonSerializer.serialize(snapshot)
      binary <- Signed.forAsyncHasher[IO, StateChannelSnapshotBinary](
        StateChannelSnapshotBinary(Hash.empty, binaryBytes, SnapshotFee.MinValue),
        binaryKey
      )
      hashedSnapshot <- snapshot.toHashed[IO]
      hashedBinary <- binary.toHashed[IO]
      context = CurrencySnapshotContext(address, hashedGenesis.info.toCurrencySnapshotInfo)
    } yield CurrencyCertifiedGenesisOutcome.seed(snapshot, hashedBinary, context, hashedSnapshot.hash)
  }

  test("only the exact signed Currency genesis artifact and binary form a canonical root") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    for {
      keyPair <- KeyPairGenerator.makeKeyPair[IO]
      root <- canonicalRoot(keyPair, keyPair)
      rootSigners = root.finished.signedMajorityArtifact.proofs.toSortedSet.toList.map(_.id.toPeerId).toSet
      unauthorizedKey <- KeyPairGenerator.makeKeyPair[IO]
      unauthorized = PeerId.fromPublic(unauthorizedKey.getPublic)
      valid <- CurrencyCertifiedGenesisOutcome.validate[IO](root)
      authorized <- CurrencyCertifiedGenesisOutcome.validate[IO](root, rootSigners)
      unauthorizedResult <- CurrencyCertifiedGenesisOutcome.validate[IO](root, Set(unauthorized))
      wrongCommittee <- CurrencyCertifiedGenesisOutcome.validate[IO](root.copy(facilitators = Facilitators(List.empty)))
      wrongSnapshotHash <- CurrencyCertifiedGenesisOutcome.validate[IO](
        root.copy(finished = root.finished.copy(snapshotHash = Hash("candidate-scalar")))
      )
      wrongBinaryHash <- CurrencyCertifiedGenesisOutcome.validate[IO](
        root.copy(finished = root.finished.copy(binaryArtifactHash = Hash("candidate-scalar")))
      )
      differentBalanceContext = root.finished.context.copy(
        snapshotInfo = root.finished.context.snapshotInfo.copy(balances = SortedMap(address -> Balance.empty))
      )
      wrongContext <- CurrencyCertifiedGenesisOutcome.validate[IO](
        root.copy(finished = root.finished.copy(context = differentBalanceContext))
      )
    } yield
      expect.same(Right(()), valid) &&
        expect.same(Right(()), authorized) &&
        expect.same(Left("genesis_artifact_signer_not_seedlisted"), unauthorizedResult) &&
        expect.same(Left("genesis_outcome_not_proof_signer_root"), wrongCommittee) &&
        expect.same(Left("genesis_outcome_not_proof_signer_root"), wrongSnapshotHash) &&
        expect.same(Left("genesis_outcome_not_proof_signer_root"), wrongBinaryHash) &&
        expect.same(Left("genesis_context_state_proof_mismatch"), wrongContext)
  }

  test("the genesis binary signer set must equal the signed Currency artifact signer set") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    for {
      artifactKey <- KeyPairGenerator.makeKeyPair[IO]
      binaryKey <- KeyPairGenerator.makeKeyPair[IO]
      root <- canonicalRoot(artifactKey, binaryKey)
      result <- CurrencyCertifiedGenesisOutcome.validate[IO](root)
    } yield expect.same(Left("genesis_binary_signers_mismatch"), result)
  }

  test("proof-derived genesis authority is bound to the locally accepted signed artifact") { implicit res =>
    implicit val (jsonSerializer, hasher, securityProvider) = res
    implicit val hasherSelector: HasherSelector[IO] = HasherSelector.forSyncAlwaysCurrent(hasher)

    for {
      trustedKey <- KeyPairGenerator.makeKeyPair[IO]
      substituteKey <- KeyPairGenerator.makeKeyPair[IO]
      trusted <- canonicalRoot(trustedKey, trustedKey)
      substitutedArtifact <- Signed.forAsyncHasher[IO, CurrencyIncrementalSnapshot](
        trusted.finished.signedMajorityArtifact.value,
        substituteKey
      )
      binaryBytes <- jsonSerializer.serialize(substitutedArtifact)
      substitutedBinary <- Signed.forAsyncHasher[IO, StateChannelSnapshotBinary](
        StateChannelSnapshotBinary(Hash.empty, binaryBytes, SnapshotFee.MinValue),
        substituteKey
      )
      hashedArtifact <- substitutedArtifact.toHashed[IO]
      hashedBinary <- substitutedBinary.toHashed[IO]
      substituted = CurrencyCertifiedGenesisOutcome.seed(
        substitutedArtifact,
        hashedBinary,
        trusted.finished.context,
        hashedArtifact.hash
      )
      selfConsistent <- CurrencyCertifiedGenesisOutcome.validate[IO](substituted)
      locallyBound <- CurrencyCertifiedGenesisOutcome.validateAgainstLocalArtifact[IO](
        substituted,
        trusted.finished.signedMajorityArtifact
      )
    } yield
      expect.same(Right(()), selfConsistent) &&
        expect.same(Left("genesis_artifact_not_locally_validated"), locallyBound)
  }
}
