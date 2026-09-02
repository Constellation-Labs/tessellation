package io.constellationnetwork.currency.l0.snapshot.storage

import cats.effect.kernel.{Async, Ref}
import cats.effect.std.Mutex
import cats.syntax.all._

import io.constellationnetwork.currency.schema.globalSnapshotSync.GlobalSyncView
import io.constellationnetwork.json.JsonSerializer
import io.constellationnetwork.node.shared.infrastructure.storage.CrashSafeAtomicFileWriter
import io.constellationnetwork.schema.SnapshotOrdinal
import io.constellationnetwork.schema.address.Address
import io.constellationnetwork.schema.balance.Balance
import io.constellationnetwork.security.hash.Hash

import derevo.cats.eqv
import derevo.circe.magnolia.{decoder, encoder}
import derevo.derive
import fs2.io.file.{Files, Flags, Path}

/** Small durable fee input captured while a proposal's exact Global context is locally available.
  *
  * The receipt is local implementation state. It is not accepted from peers and it does not change Currency or Global wire schemas.
  * Multiple facilitator proposals at one Currency ordinal are keyed independently by their artifact hashes.
  */
trait CurrencyFeeContextReceiptStorage[F[_]] {
  import CurrencyFeeContextReceiptStorage.{CurrencyFeeContextKey, CurrencyFeeContextReceipt}

  def putDurably(receipt: CurrencyFeeContextReceipt): F[CurrencyFeeContextReceipt]

  def get(key: CurrencyFeeContextKey): F[Option[CurrencyFeeContextReceipt]]

  /** Release rejected proposals only after one exact proposal has been selected. */
  def retainSelected(key: CurrencyFeeContextKey): F[CurrencyFeeContextReceipt]

  /** Release a selected receipt only after its exact artifact and binary outbox are durable. */
  def complete(key: CurrencyFeeContextKey): F[Unit]

  /** Release every proposal receipt when its consensus generation is abandoned. */
  def abandonGeneration(ordinal: SnapshotOrdinal): F[Unit]

  /** Startup cleanup for receipts at or below the canonical terminal state.
    *
    * Pending ordinary or recovery publication keys remain protected until their durable work is reconciled.
    */
  def sweepCompleted(
    canonicalTerminal: SnapshotOrdinal,
    protectedKeys: Set[CurrencyFeeContextKey]
  ): F[List[CurrencyFeeContextKey]]

  /** A validated download or controlled rollback replaces all node-local proposal authority. */
  def discardAllForCanonicalReplacement: F[Unit]

  private[storage] def list: F[List[CurrencyFeeContextReceipt]]
}

object CurrencyFeeContextReceiptStorage {
  val CurrentEncodingVersion: String = "currency-fee-context-json-v1"

  @derive(encoder, decoder, eqv)
  final case class CurrencyFeeContextKey(
    currencyOrdinal: SnapshotOrdinal,
    currencyArtifactHash: Hash
  )

  @derive(encoder, decoder, eqv)
  final case class CurrencyFeeContextReceipt(
    encodingVersion: String,
    currencyOrdinal: SnapshotOrdinal,
    currencyArtifactHash: Hash,
    globalSyncView: GlobalSyncView,
    stakingAddress: Option[Address],
    stakingBalance: Balance
  ) {
    def key: CurrencyFeeContextKey = CurrencyFeeContextKey(currencyOrdinal, currencyArtifactHash)
  }

  final case class MissingCurrencyFeeContextReceipt(key: CurrencyFeeContextKey)
      extends IllegalStateException(s"Missing Currency fee-context receipt for key=$key")

  final case class CurrencyFeeContextReceiptConflict(key: CurrencyFeeContextKey)
      extends IllegalStateException(s"Conflicting Currency fee-context receipt for key=$key")

  final case class CorruptCurrencyFeeContextReceipt(key: CurrencyFeeContextKey, reason: String)
      extends IllegalStateException(s"Corrupt Currency fee-context receipt for key=$key: $reason")

  final case class UnsupportedCurrencyFeeContextEncoding(key: CurrencyFeeContextKey, version: String)
      extends IllegalStateException(s"Unsupported Currency fee-context encoding for key=$key version=$version")

  private def fileName(key: CurrencyFeeContextKey): String =
    s"${key.currencyOrdinal.value.value}-${key.currencyArtifactHash.value}.json"

  def make[F[_]: Async: Files: JsonSerializer](base: Path): F[CurrencyFeeContextReceiptStorage[F]] = {
    def validate(receipt: CurrencyFeeContextReceipt): F[CurrencyFeeContextReceipt] =
      if (receipt.encodingVersion === CurrentEncodingVersion) receipt.pure[F]
      else UnsupportedCurrencyFeeContextEncoding(receipt.key, receipt.encodingVersion).raiseError[F, CurrencyFeeContextReceipt]

    def readPath(path: Path): F[CurrencyFeeContextReceipt] =
      Files[F]
        .readAll(path, 64 * 1024, Flags.Read)
        .compile
        .to(Array)
        .flatMap(JsonSerializer[F].deserialize[CurrencyFeeContextReceipt])
        .flatMap(_.liftTo[F])
        .flatMap(validate)

    for {
      writer <- CrashSafeAtomicFileWriter.make[F](base)
      loaded <- Files[F]
        .list(base)
        .filter(_.extName === ".json")
        .compile
        .toList
        .flatMap(_.traverse(readPath))
      _ <- loaded
        .groupBy(_.key)
        .collectFirst { case (key, duplicates) if duplicates.size =!= 1 => key }
        .traverse_(key => CorruptCurrencyFeeContextReceipt(key, "duplicate key").raiseError[F, Unit])
      state <- Ref.of[F, Map[CurrencyFeeContextKey, CurrencyFeeContextReceipt]](loaded.map(receipt => receipt.key -> receipt).toMap)
      mutex <- Mutex[F]
    } yield
      new CurrencyFeeContextReceiptStorage[F] {

        private def readDisk(key: CurrencyFeeContextKey): F[Option[CurrencyFeeContextReceipt]] = {
          val path = base / fileName(key)
          Files[F].exists(path).ifM(readPath(path).map(_.some), none[CurrencyFeeContextReceipt].pure[F])
        }

        private def writeAndVerify(receipt: CurrencyFeeContextReceipt): F[CurrencyFeeContextReceipt] =
          JsonSerializer[F].serialize(receipt).flatMap(writer.write(fileName(receipt.key), _)) >>
            readDisk(receipt.key).flatMap {
              case Some(stored) if stored === receipt =>
                state.update(_.updated(receipt.key, receipt)).as(receipt)
              case Some(_) => CurrencyFeeContextReceiptConflict(receipt.key).raiseError[F, CurrencyFeeContextReceipt]
              case None =>
                CorruptCurrencyFeeContextReceipt(receipt.key, "missing after atomic replacement")
                  .raiseError[F, CurrencyFeeContextReceipt]
            }

        private def delete(key: CurrencyFeeContextKey): F[Unit] =
          writer.delete(fileName(key)).void >> state.update(_ - key)

        def putDurably(receipt: CurrencyFeeContextReceipt): F[CurrencyFeeContextReceipt] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap(_.get(receipt.key) match {
                case Some(existing) if existing === receipt => writeAndVerify(receipt)
                case Some(_) => CurrencyFeeContextReceiptConflict(receipt.key).raiseError[F, CurrencyFeeContextReceipt]
                case None    => writeAndVerify(receipt)
              })
            }
          }

        def get(key: CurrencyFeeContextKey): F[Option[CurrencyFeeContextReceipt]] =
          mutex.lock.surround {
            state.get.flatMap(_.get(key) match {
              case None => none[CurrencyFeeContextReceipt].pure[F]
              case Some(expected) =>
                readDisk(key).flatMap {
                  case Some(stored) if stored === expected => stored.some.pure[F]
                  case Some(_) => CurrencyFeeContextReceiptConflict(key).raiseError[F, Option[CurrencyFeeContextReceipt]]
                  case None    => state.update(_ - key).as(none[CurrencyFeeContextReceipt])
                }
            })
          }

        def retainSelected(key: CurrencyFeeContextKey): F[CurrencyFeeContextReceipt] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap { entries =>
                entries.get(key) match {
                  case None => MissingCurrencyFeeContextReceipt(key).raiseError[F, CurrencyFeeContextReceipt]
                  case Some(selected) =>
                    readDisk(key).flatMap {
                      case Some(stored) if stored === selected =>
                        entries.keys
                          .filter(candidate => candidate.currencyOrdinal === key.currencyOrdinal && candidate =!= key)
                          .toList
                          .traverse_(delete)
                          .as(selected)
                      case Some(_) => CurrencyFeeContextReceiptConflict(key).raiseError[F, CurrencyFeeContextReceipt]
                      case None    => MissingCurrencyFeeContextReceipt(key).raiseError[F, CurrencyFeeContextReceipt]
                    }
                }
              }
            }
          }

        def complete(key: CurrencyFeeContextKey): F[Unit] =
          mutex.lock.surround(Async[F].uncancelable(_ => delete(key)))

        def abandonGeneration(ordinal: SnapshotOrdinal): F[Unit] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap(
                _.keys.filter(_.currencyOrdinal === ordinal).toList.traverse_(delete)
              )
            }
          }

        def sweepCompleted(
          canonicalTerminal: SnapshotOrdinal,
          protectedKeys: Set[CurrencyFeeContextKey]
        ): F[List[CurrencyFeeContextKey]] =
          mutex.lock.surround {
            Async[F].uncancelable { _ =>
              state.get.flatMap { entries =>
                val removable = entries.keys
                  .filter(key => key.currencyOrdinal <= canonicalTerminal && !protectedKeys.contains(key))
                  .toList
                  .sortBy(key => (key.currencyOrdinal.value.value, key.currencyArtifactHash.value))

                removable.traverse_(delete).as(removable)
              }
            }
          }

        def discardAllForCanonicalReplacement: F[Unit] =
          mutex.lock.surround {
            Async[F].uncancelable(_ => state.get.flatMap(_.keys.toList.traverse_(delete)))
          }

        def list: F[List[CurrencyFeeContextReceipt]] =
          state.get.map(
            _.values.toList.sortBy(receipt => (receipt.currencyOrdinal.value.value, receipt.currencyArtifactHash.value))
          )
      }
  }
}
