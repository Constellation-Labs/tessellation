package org.tessellation.ext.swaydb

import cats.effect.kernel.Ref
import cats.effect.{IO, Resource}
import cats.syntax.flatMap._
import cats.syntax.option._

import io.chrisdavenport.mapref.MapRef
import swaydb.serializers.Default._
import weaver.MutableIOSuite
import weaver.scalacheck.Checkers

object SwaydbRefSuite extends MutableIOSuite with Checkers {

  type Res = MapRef[IO, Int, Option[String]]

  override def sharedResource: Resource[IO, Res] =
    SwaydbRef[IO].memory[Int, String]

  test("it should set and get the value") { ref =>
    val key = Random.key
    val value = Random.value

    ref(key).set(value.some) >>
      ref(key).get.map { expect.same(value.some, _) }
  }

  test("it should update the value") { ref =>
    val key = Random.key
    val initValue = Random.value

    ref(key).set(initValue.some) >>
      ref(key).update(_.map(_ ++ "!")) >>
      ref(key).get.map { expect.same(s"${initValue}!".some, _) }
  }

  test("it should modify the value") { ref =>
    val key = Random.key
    val initValue = Random.value

    ref(key).set(initValue.some) >>
      ref(key).modify(m => (m.map(x => x ++ "!"), m)) >>= { out =>
      IO { expect.same(initValue.some, out) } >>
        ref(key).get.map { expect.same(s"${initValue}!".some, _) }
    }

  }

  test("it should access") { ref =>
    val key = Random.key
    val initValue = Random.value

    ref(key).set(initValue.some) >>
      ref(key).access >>= {
      case (value, setter) =>
        setter(value.map(_ + "!")) >>
          ref(key).get.map { expect.same(s"${initValue}!".some, _) }
    }
  }

  test("access - it should fail if value is modified before setter is called") { ref =>
    val key = Random.key

    val r = ref(key)

    val op = for {
      (value, setter) <- r.access
      newValue = Random.value
      _ <- r.set(newValue.some)
      success <- setter(value.map(_ + "!"))
      result <- r.get
    } yield !success && result == newValue.some

    op.map { expect.same(true, _) }
  }

  test("access - it should fail if called twice") { ref =>
    val key = Random.key
    val initValue = Random.value

    val r = ref(key)

    val op = for {
      _ <- r.set(initValue.some)
      (value, setter) <- r.access
      cond1 <- setter(value.map(_ + "!"))
      _ <- r.set(value)
      cond2 <- setter(value.map(_ + "!"))
      result <- r.get
    } yield cond1 && !cond2 && result == initValue.some

    op.map { expect.same(true, _) }

  }

  test("tryUpdate - should fail to update if modification has occured") { ref =>
    import cats.effect.unsafe.implicits.global

    val updateRunUnsafely: Ref[IO, Option[String]] => Unit = { r =>
      r.update(_.map(_ + "!")).unsafeRunSync()
      ()
    }

    val key = Random.key
    val value = Random.value

    val r = ref(key)

    val op = for {
      _ <- r.set(value.some)
      result <- r.tryUpdate { currentValue =>
        updateRunUnsafely(ref(key))
        currentValue.map(_ + "!")
      }
    } yield result

    op.map { expect.same(false, _) }
  }

  test("tryUpdate - should not lock independent keys") { ref =>
    import cats.effect.unsafe.implicits.global

    val updateRunUnsafely: Ref[IO, Option[String]] => Unit = { r =>
      r.update(_.map(_ + "!")).unsafeRunSync()
      ()
    }

    val key1 = Random.key
    val key2 = Random.key

    val value = Random.value

    val r1 = ref(key1)
    val r2 = ref(key2)

    val op = for {
      _ <- r1.set(value.some)
      _ <- r2.set(value.some)
      
      result <- r1.tryUpdate { currentValue =>
        updateRunUnsafely(r2)
        currentValue.map(_ + "!")
      }
    } yield result

    op.map { expect.same(true, _) }
  }

}

object Random {
  private val random = new scala.util.Random()

  def key: Int = random.nextInt()
  def value: String = s"value-${random.alphanumeric.take(100).mkString}"
}
