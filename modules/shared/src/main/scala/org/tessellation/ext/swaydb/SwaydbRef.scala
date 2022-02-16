package org.tessellation.ext.swaydb

import java.util.concurrent.atomic.AtomicBoolean

import _root_.cats.data.State
import _root_.cats.effect._
import _root_.cats.syntax.flatMap._
import _root_.cats.syntax.functor._
import _root_.cats.syntax.option._
import io.chrisdavenport.mapref.MapRef
import swaydb.serializers.Serializer
import swaydb.{memory => dbMemory, persistent => dbPersistent, _}

object SwaydbRef {

  def apply[F[_]]: ApplyBuilders[F] = new ApplyBuilders()

  final class ApplyBuilders[F[_]] {

    def memory[K: Serializer, V: Serializer](implicit F: Async[F]): Resource[F, MapRef[F, K, Option[V]]] =
      Resource
        .make(F.delay { dbMemory.Map[K, V, Nothing, Glass]() })(db => F.delay { db.close() })
        .map(new SwaydbRef(_))

    def persisted[K: Serializer, V: Serializer](
      dir: String
    )(implicit F: Async[F]): Resource[F, MapRef[F, K, Option[V]]] =
      Resource
        .make(F.delay { dbPersistent.Map[K, V, Nothing, Glass](dir) })(db => F.delay { db.close() })
        .map(new SwaydbRef(_))
  }

  private class SwaydbRef[F[_], K, V](db: swaydb.Map[K, V, Nothing, Glass])(
    implicit concurrent: Concurrent[F]
  ) extends MapRef[F, K, Option[V]] {

    def delay[A](a: => A): F[A] = concurrent.unit.map(_ => a)

    class HandleRef(k: K) extends Ref[F, Option[V]] {

      val lock = k.toString()

      def access: F[(Option[V], Option[V] => F[Boolean])] =
        delay {
          val hasBeenCalled = new AtomicBoolean(false)
          db.get(k) match {
            case None =>
              val set: Option[V] => F[Boolean] = { (opt: Option[V]) =>
                opt match {
                  case None =>
                    delay {
                      hasBeenCalled.compareAndSet(false, true) && lock.synchronized { !db.contains(k) }
                    }
                  case Some(newV) =>
                    delay {
                      hasBeenCalled.compareAndSet(false, true) && lock.synchronized { db.put(k, newV).isInstanceOf[OK] }
                    }
                }
              }
              (None, set)
            case Some(init) =>
              val set: Option[V] => F[Boolean] = { (opt: Option[V]) =>
                opt match {
                  case None =>
                    delay {
                      hasBeenCalled.compareAndSet(false, true) && lock.synchronized { db.remove(k).isInstanceOf[OK] }
                    }
                  case Some(newV) =>
                    delay {
                      hasBeenCalled.compareAndSet(false, true) && lock.synchronized {
                        db.update(k, newV).isInstanceOf[OK]
                      }
                    }
                }
              }
              (Some(init), set)
          }
        }

      def get: F[Option[V]] =
        delay {
          db.get(k)
        }

      def modify[B](f: Option[V] => (Option[V], B)): F[B] = {
        lazy val loop: F[B] = tryModify(f).flatMap {
          case None    => loop
          case Some(b) => concurrent.pure(b)
        }
        loop
      }

      def modifyState[B](state: State[Option[V], B]): F[B] =
        modify(state.run(_).value)

      def set(a: Option[V]): F[Unit] =
        a match {
          case None    => delay { lock.synchronized { db.remove(k); () } }
          case Some(v) => delay { lock.synchronized { db.put(k, v); () } }
        }

      def tryModify[B](f: Option[V] => (Option[V], B)): F[Option[B]] =
        delay {
          db.get(k) match {
            case None =>
              f(None) match {
                case (None, b) =>
                  // no-op
                  concurrent.pure(b.some)
                case (Some(newV), b) =>
                  delay {
                    lock.synchronized {
                      if (db.get(k) == None) {
                        db.put(k, newV)
                        b.some
                      } else none
                    }
                  }
              }
            case Some(init) =>
              f(Some(init)) match {
                case (None, b) =>
                  delay {
                    lock.synchronized {
                      if (db.get(k) == Some(init)) {
                        db.remove(k)
                        b.some
                      } else none
                    }
                  }
                case (Some(next), b) =>
                  delay {
                    lock.synchronized {
                      if (db.get(k) == Some(init)) {
                        db.update(k, next)
                        b.some
                      } else none
                    }
                  }
              }
          }
        }.flatten

      def tryModifyState[B](state: State[Option[V], B]): F[Option[B]] =
        tryModify(state.run(_).value)

      def tryUpdate(f: Option[V] => Option[V]): F[Boolean] =
        tryModify { opt =>
          (f(opt), ())
        }.map(_.isDefined)

      def update(f: Option[V] => Option[V]): F[Unit] = {
        lazy val loop: F[Unit] = tryUpdate(f).flatMap {
          case true  => concurrent.unit
          case false => loop
        }
        loop
      }

    }

    val keys: F[List[K]] = delay {
      db.keys.materialize.result()
    }

    def apply(k: K): Ref[F, Option[V]] = new HandleRef(k)

  }

}
