package io.constellationnetwork.security.mpt.producer

import cats.Parallel
import cats.effect.{Async, Ref}
import cats.syntax.all._

import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.schema.mpt.GlobalStateKey
import io.constellationnetwork.security.Hasher
import io.constellationnetwork.security.hex.Hex
import io.constellationnetwork.security.mpt.MerklePatriciaTrie
import io.constellationnetwork.security.mpt.prover.MerklePatriciaSingleInclusionProver

import io.circe.syntax._
import io.circe.{Encoder, Json}

class InMemoryMerklePatriciaProducer[F[_]: Async: Hasher: Parallel: JsonSerializer](
  stateRef: Ref[F, Map[Hex, Array[Byte]]],
  trieRef: Ref[F, Option[MerklePatriciaTrie]],
  pendingInsertsRef: Ref[F, Map[Hex, Array[Byte]]],
  pendingRemovesRef: Ref[F, List[Hex]]
) extends StatefulMerklePatriciaProducer[F] {

  private val parallelProducer: ParallelMerklePatriciaProducer[F] = ParallelMerklePatriciaProducer[F]

  override def getProver: F[MerklePatriciaSingleInclusionProver[F]] =
    build.flatMap {
      case Right(trie) => parallelProducer.getProver(trie)
      case Left(err)   => Async[F].raiseError(err)
    }

  override def entries: F[Map[Hex, Array[Byte]]] =
    stateRef.get

  override def build: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      currentTrie <- trieRef.get
      pendingInserts <- pendingInsertsRef.get
      pendingRemoves <- pendingRemovesRef.get

      result <- (currentTrie, pendingInserts.isEmpty, pendingRemoves.isEmpty) match {
        case (None, _, _) =>
          fullBuild

        case (Some(trie), true, true) =>
          trie.asRight[MerklePatriciaError].pure[F]

        case (Some(trie), _, _) =>
          incrementalUpdate(trie, pendingInserts, pendingRemoves)
      }
    } yield result

  private def fullBuild: F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      currentEntries <- entries
      result <-
        if (currentEntries.isEmpty) {
          val err: Either[MerklePatriciaError, MerklePatriciaTrie] =
            OperationError("Cannot build trie with no entries").asLeft[MerklePatriciaTrie]
          err.pure[F]
        } else {
          // Use bytes-based creation - NO JSON CONVERSION
          parallelProducer.createFromBytes(currentEntries).attempt.flatMap {
            case Right(trie) =>
              MerklePatriciaTrie.rootHash[F](trie).flatMap {
                case (_, hashedTrie) =>
                  (trieRef.set(Some(hashedTrie)) >>
                    pendingInsertsRef.set(Map.empty) >>
                    pendingRemovesRef.set(List.empty)).as(hashedTrie.asRight[MerklePatriciaError])
              }
            case Left(e) =>
              val err: Either[MerklePatriciaError, MerklePatriciaTrie] =
                OperationError(e.getMessage).asLeft[MerklePatriciaTrie]
              err.pure[F]
          }
        }
    } yield result

  private def incrementalUpdate(
    currentTrie: MerklePatriciaTrie,
    inserts: Map[Hex, Array[Byte]],
    removes: List[Hex]
  ): F[Either[MerklePatriciaError, MerklePatriciaTrie]] =
    for {
      afterRemoves <-
        if (removes.isEmpty) currentTrie.asRight[MerklePatriciaError].pure[F]
        else parallelProducer.remove(currentTrie, removes)

      result <- afterRemoves match {
        case Left(err) =>
          val errResult: Either[MerklePatriciaError, MerklePatriciaTrie] = err.asLeft[MerklePatriciaTrie]
          errResult.pure[F]
        case Right(trieAfterRemoves) =>
          if (inserts.isEmpty) {
            MerklePatriciaTrie.rootHash[F](trieAfterRemoves).flatMap {
              case (_, hashedTrie) =>
                (trieRef.set(Some(hashedTrie)) >>
                  pendingInsertsRef.set(Map.empty) >>
                  pendingRemovesRef.set(List.empty)).as(hashedTrie.asRight[MerklePatriciaError])
            }
          } else {
            parallelProducer.insertFromBytes(trieAfterRemoves, inserts).flatMap {
              case Left(err) =>
                val errResult: Either[MerklePatriciaError, MerklePatriciaTrie] = err.asLeft[MerklePatriciaTrie]
                errResult.pure[F]
              case Right(finalTrie) =>
                MerklePatriciaTrie.rootHash[F](finalTrie).flatMap {
                  case (_, hashedTrie) =>
                    (trieRef.set(Some(hashedTrie)) >>
                      pendingInsertsRef.set(Map.empty) >>
                      pendingRemovesRef.set(List.empty)).as(hashedTrie.asRight[MerklePatriciaError])
                }
            }
          }
      }
    } yield result

  override def insert[A: Encoder](data: Map[Hex, A]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      for {
        byteEntries <- data.toList.traverse {
          case (k, v) =>
            JsonSerializer[F].serialize(v.asJson).map(k -> _)
        }.map(_.toMap)
        _ <- stateRef.update(_ ++ byteEntries)
        _ <- pendingRemovesRef.update(_.filterNot(byteEntries.contains))
        _ <- pendingInsertsRef.update(_ ++ byteEntries)
      } yield ().asRight[MerklePatriciaError]
    }

  override def insertBytes(data: Map[Hex, Array[Byte]]): F[Either[MerklePatriciaError, Unit]] =
    if (data.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      for {
        _ <- stateRef.update(_ ++ data)
        _ <- pendingRemovesRef.update(_.filterNot(data.contains))
        _ <- pendingInsertsRef.update(_ ++ data)
      } yield ().asRight[MerklePatriciaError]
    }

  override def update[A: Encoder](key: Hex, value: A): F[Either[MerklePatriciaError, Unit]] =
    stateRef.get.flatMap { state =>
      if (!state.contains(key)) {
        val err: Either[MerklePatriciaError, Unit] = OperationError(s"Key not found for update: $key").asLeft[Unit]
        err.pure[F]
      } else {
        for {
          bytes <- JsonSerializer[F].serialize(value.asJson)
          _ <- stateRef.update(_ + (key -> bytes))
          _ <- pendingInsertsRef.update(_ + (key -> bytes))
        } yield ().asRight[MerklePatriciaError]
      }
    }

  override def remove(keys: List[Hex]): F[Either[MerklePatriciaError, Unit]] =
    if (keys.isEmpty) ().asRight[MerklePatriciaError].pure[F]
    else {
      for {
        _ <- stateRef.update(_ -- keys)
        _ <- pendingInsertsRef.update(_ -- keys)
        _ <- pendingRemovesRef.update(existing => (existing ++ keys).distinct)
      } yield ().asRight[MerklePatriciaError]
    }

  override def clear: F[Unit] =
    stateRef.set(Map.empty) >>
      trieRef.set(None) >>
      pendingInsertsRef.set(Map.empty) >>
      pendingRemovesRef.set(List.empty)

  override def buildHexMap(data: Map[GlobalStateKey, Json]): F[Map[Hex, Array[Byte]]] = {
    val BatchSize = 5000

    if (data.size <= BatchSize) {
      data.toList.parTraverse {
        case (key, value) =>
          for {
            hex <- GlobalStateKey.toHex[F](key)
            bytes <- JsonSerializer[F].serialize(value)
          } yield hex -> bytes
      }.map(_.toMap)
    } else {
      data.toList
        .grouped(BatchSize)
        .toList
        .foldLeftM(Map.empty[Hex, Array[Byte]]) { (acc, batch) =>
          for {
            batchResult <- batch.parTraverse {
              case (key, value) =>
                for {
                  hex <- GlobalStateKey.toHex[F](key)
                  bytes <- JsonSerializer[F].serialize(value)
                } yield hex -> bytes
            }
            _ <- Async[F].cede
          } yield acc ++ batchResult.toMap
        }
    }
  }
}

object InMemoryMerklePatriciaProducer {

  def make[F[_]: Async: Hasher: Parallel: JsonSerializer](
    initial: Map[Hex, Array[Byte]] = Map.empty
  ): F[InMemoryMerklePatriciaProducer[F]] =
    for {
      stateRef <- Ref.of[F, Map[Hex, Array[Byte]]](initial)
      trieRef <- Ref.of[F, Option[MerklePatriciaTrie]](None)
      pendingInsertsRef <- Ref.of[F, Map[Hex, Array[Byte]]](Map.empty)
      pendingRemovesRef <- Ref.of[F, List[Hex]](List.empty)
    } yield new InMemoryMerklePatriciaProducer[F](stateRef, trieRef, pendingInsertsRef, pendingRemovesRef)
}
