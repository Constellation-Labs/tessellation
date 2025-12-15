package io.constellationnetwork.security.mpt.producer

import cats.Parallel
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.security.mpt.producer.InMemoryMerklePatriciaProducer.{ProducerState, TrieCache}
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver

import io.circe.syntax._
import io.circe.{Encoder, Json}

class InMemoryMerklePatriciaProducer[F[_]: Async: Hasher](
  stateRef: Ref[F, ProducerState]
) extends StatefulMerklePatriciaProducer[F] {

  override def getProver: F[MerklePatriciaSingleInclusionProver[F]] =
    stateRef.get.flatMap { state =>
      state.currentTrie match {
        case Some(trie) =>
          MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]
        case None =>
          build.flatMap {
            case Right(trie) => MerklePatriciaSingleInclusionProver.make[F](trie).pure[F]
            case Left(err)   => Async[F].raiseError(err)
          }
      }
    }

  override def entries: F[Map[Hex, Json]] =
    stateRef.get.map(_.entries)

  override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
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

  override def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      stateRef.update { state =>
        val jsonEntries = data.map { case (k, v) => k -> v.asJson }
        state.copy(
          entries = state.entries ++ jsonEntries,
          dirtyKeys = state.dirtyKeys ++ data.keySet,
          currentTrie = None
        )
      }
        .as(().asRight[MerklePatriciaError])
    }

  override def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]] =
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

  override def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]] =
    if (keys.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      stateRef.update { state =>
        val existing = keys.filter(state.entries.contains)
        if (existing.isEmpty) state
        else
          state.copy(
            entries = state.entries -- existing,
            dirtyKeys = state.dirtyKeys ++ existing.toSet,
            currentTrie = None
          )
      }
        .as(().asRight[MerklePatriciaError])
    }

  override def clear: F[Unit] =
    stateRef.update(
      _.copy(
        entries = Map.empty,
        currentTrie = None,
        dirtyKeys = Set.empty,
        version = 0L,
        trieCache = None
      )
    )

  override def buildHexMap(data: Map[GlobalStateKey, Json])(implicit parallel: Parallel[F]): F[Map[Hex, Json]] =
    data.toList.parTraverse {
      case (key, value) =>
        GlobalStateKey.toHex[F](key).map(_ -> value)
    }
      .map(_.toMap)
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

  def make[F[_]: Async: Hasher](
    initial: Map[Hex, Json] = Map.empty
  ): F[InMemoryMerklePatriciaProducer[F]] =
    Ref
      .of[F, ProducerState](
        ProducerState(
          entries = initial,
          currentTrie = None,
          version = 0L,
          dirtyKeys = if (initial.nonEmpty) initial.keySet else Set.empty,
          trieCache = None
        )
      )
      .map(new InMemoryMerklePatriciaProducer[F](_))
}
