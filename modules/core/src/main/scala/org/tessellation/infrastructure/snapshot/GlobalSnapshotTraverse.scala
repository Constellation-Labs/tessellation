package org.tessellation.infrastructure.snapshot

import cats._
import cats.data.NonEmptyChain
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.option._
import cats.syntax.show._
import cats.syntax.traverse._
import cats.syntax.validated._

import org.tessellation.schema._
import org.tessellation.sdk.domain.snapshot.SnapshotContextFunctions
import org.tessellation.sdk.infrastructure.snapshot.GlobalSnapshotValidator
import org.tessellation.security.hash.Hash
import org.tessellation.security.signature.Signed

trait GlobalSnapshotTraverse[F[_]] {
  def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])]
}

object GlobalSnapshotTraverse {

  def make[F[_]: MonadThrow](
    loadInc: Hash => F[Option[Signed[GlobalIncrementalSnapshot]]],
    loadFull: Hash => F[Option[Signed[GlobalSnapshot]]],
    validator: GlobalSnapshotValidator[F],
//    contextFns: SnapshotContextFunctions[F, GlobalSnapshotArtifact, GlobalSnapshotContext],
    rollbackHash: Hash
  ): GlobalSnapshotTraverse[F] =
    new GlobalSnapshotTraverse[F] {

      def loadChain(): F[(GlobalSnapshotInfo, Signed[GlobalIncrementalSnapshot])] = {
        def loadFullOrErr(h: Hash) = loadFull(h).flatMap(_.liftTo[F](new Throwable(s"Global snapshot not found, hash=${h.show}")))
        def loadIncOrErr(h: Hash) = loadInc(h).flatMap(_.liftTo[F](new Throwable(s"Incremental snapshot not found, hash=${h.show}")))

        def hashChain(h: Hash): F[NonEmptyChain[Hash]] =
          loadInc(h).flatMap {
            _.traverse { inc =>
              hashChain(inc.lastSnapshotHash).map(_.append(h))
            }.map(_.getOrElse(NonEmptyChain.one(h)))
          }

        for {
          rollbackInc <- loadIncOrErr(rollbackHash)
          (globalHash, incHashesNec) <- hashChain(rollbackInc.lastSnapshotHash).map { nec =>
            (nec.head, NonEmptyChain.fromChainAppend(nec.tail, rollbackHash))
          }
          global <- loadFullOrErr(globalHash)
          firstInc <- loadIncOrErr(incHashesNec.head)

          (info, lastInc) <- incHashesNec.tail.foldLeftM((GlobalSnapshotInfoV1.toGlobalSnapshotInfo(global.info), firstInc)) {
            (acc, hash) =>
              loadIncOrErr(hash).flatMap { inc =>
                validator
                  .validateSignedSnapshot(acc._2, acc._1, inc)
                  .flatMap(_.leftMap(e => new Throwable(s"Error in consecutive snapshot: ${e.show}")).liftTo[F])
                  .map(_.swap)
              }
          }
        } yield (info, lastInc)
      }
    }

}
