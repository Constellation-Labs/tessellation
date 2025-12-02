package io.constellationnetwork.security.mpt.producer

import cats.effect.{Ref, Sync}
import cats.syntax.all._

import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer.TrieCache
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver
import io.constellationnetwork.security.mpt.verifier._

import io.circe.syntax._
import io.circe.{Encoder, Json}

class InMemoryMerklePatriciaProducer[F[_]: Sync: Hasher](
  stateRef: Ref[F, InMemoryMerklePatriciaProducer.ProducerState]
) extends StatefulMerklePatriciaProducer[F] {

  def getProver: F[MerklePatriciaSingleInclusionProver[F]] =
    stateRef.get.flatMap { state =>
      state.currentTrie match {
        case Some(trie) =>
          MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]
        case None =>
          build.flatMap {
            case Right(trie) => MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]
            case Left(err)   => Sync[F].raiseError(err)
          }
      }
    }

  def entries: F[Map[Hex, Json]] =
    stateRef.get.map(_.entries)

  def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    stateRef.get.flatMap { state =>
      if (state.entries.isEmpty) {
        OperationError("Cannot build trie with no entries").asLeft[MerklePatriciaTrie].pure[F].widen
      } else {
        state.currentTrie match {
          case Some(trie) if state.dirtyKeys.isEmpty =>
            trie.asRight[MerklePatriciaError].pure[F]
          case _ =>
            MerklePatriciaTrie.make[F, Json](state.entries).attempt.flatMap {
              case Right(trie) =>
                val shouldCache = state.dirtyKeys.size <= 100
                stateRef
                  .update(
                    _.copy(
                      currentTrie = Some(trie),
                      dirtyKeys = Set.empty,
                      version = state.version + 1,
                      trieCache = if (shouldCache) Some(TrieCache(trie, state.version + 1)) else None
                    )
                  )
                  .as(trie.asRight[MerklePatriciaError])
              case Left(e) =>
                OperationError(e.getMessage).asLeft[MerklePatriciaTrie].pure[F].widen
            }
        }
      }
    }

  def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      stateRef.update { state =>
        val jsonEntries = data.map { case (k, v) => k -> v.asJson }
        val newKeys = data.keySet
        state.copy(
          entries = state.entries ++ jsonEntries,
          dirtyKeys = state.dirtyKeys ++ newKeys,
          currentTrie = None
        )
      }
        .as(().asRight[MerklePatriciaError])
    }

  def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]] =
    stateRef.get.flatMap { state =>
      if (!state.entries.contains(key)) {
        OperationError(s"Key not found for update: $key").asLeft[Unit].pure[F].widen
      } else {
        stateRef.update { s =>
          s.copy(
            entries = s.entries + (key -> value.asJson),
            dirtyKeys = s.dirtyKeys + key,
            currentTrie = None
          )
        }
          .as(().asRight[MerklePatriciaError])
      }
    }

  def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]] =
    if (keys.isEmpty) {
      ().asRight[MerklePatriciaError].pure[F]
    } else {
      stateRef.get.flatMap { state =>
        val existing = keys.filter(state.entries.contains)
        if (existing.isEmpty) {
          ().asRight[MerklePatriciaError].pure[F]
        } else {
          stateRef.update { s =>
            s.copy(
              entries = s.entries -- existing,
              dirtyKeys = s.dirtyKeys ++ existing.toSet,
              currentTrie = None
            )
          }
            .as(().asRight[MerklePatriciaError])
        }
      }
    }

  def clear: F[Unit] =
    stateRef.update(
      _.copy(
        entries = Map.empty,
        currentTrie = None,
        dirtyKeys = Set.empty,
        version = 0L,
        trieCache = None
      )
    )
}

object InMemoryMerklePatriciaProducer {

  case class ProducerState(
    entries: Map[Hex, Json],
    currentTrie: Option[MerklePatriciaTrie],
    version: Long,
    dirtyKeys: Set[Hex],
    trieCache: Option[TrieCache]
  )

  case class TrieCache(
    trie: MerklePatriciaTrie,
    version: Long,
    maxDirtyKeys: Int = 100
  )

  def make[F[_]: Sync: Hasher](
    initial: Map[Hex, Json] = Map.empty
  ): F[InMemoryMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, ProducerState](
        ProducerState(
          entries = initial,
          currentTrie = None,
          version = 0L,
          dirtyKeys = if (initial.nonEmpty) initial.keySet else Set.empty,
          trieCache = None
        )
      )
    } yield new InMemoryMerklePatriciaProducer[F](stateRef)
}
