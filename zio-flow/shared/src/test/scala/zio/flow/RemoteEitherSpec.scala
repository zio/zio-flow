package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.schema.Schema
import zio.test._
import zio.{Runtime, ZIO}

object RemoteEitherSpec extends RemoteSpecBase {

  def spec =
    suite("RemoteEitherSpec")(
      test("handleEither") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          val expected = either.fold(_ * 2, if (_) 10 else 20)
          val result   = Remote(either).handleEither(_ * 2, _.ifThenElse(10, 20))
          result <-> expected
        }
      },
      test("flatMap") {
        check(Gen.either(Gen.int, Gen.int), Gen.function(Gen.either(Gen.int, Gen.long))) { (either, f) =>
          ZIO.runtime[RemoteContext].flatMap { runtime =>
            Remote(either).flatMap(partialLift(runtime, f)) <-> either.flatMap(f)
          }
        }
      },
      test("map") {
        check(Gen.either(Gen.int, Gen.int), Gen.function(Gen.long)) { (either, f) =>
          ZIO.runtime[RemoteContext].flatMap { runtime =>
            Remote(either).map(partialLift(runtime, f)) <-> either.map(f)
          }
        }
      },
      test("flatten") {
        check(Gen.either(Gen.int, Gen.either(Gen.int, Gen.long))) { either =>
          Remote(either).flatten <-> either.fold(a => Left(a), b => b)
        }
      },
      test("merge") {
        check(Gen.either(Gen.int, Gen.int)) { either =>
          Remote(either).merge <-> either.fold(identity, identity)
        }
      },
      test("isRight") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          Remote(either).isRight <-> either.isRight
        }
      },
      test("handleEither") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          Remote(either).isLeft <-> either.isLeft
        }
      },
      test("getOrElse") {
        check(Gen.either(Gen.boolean, Gen.int), Gen.int) { (either, int) =>
          Remote(either).getOrElse(int) <-> either.getOrElse(int)
        }
      },
      test("orElse") {
        check(Gen.either(Gen.boolean, Gen.int), Gen.either(Gen.boolean, Gen.long)) { (eitherInt, eitherLong) =>
          Remote(eitherInt).orElse(eitherLong) <-> eitherInt.fold(_ => eitherLong, b => Right(b))
        }
      },
      test("filterOrElse") {
        check(Gen.either(Gen.boolean, Gen.int), Gen.function(Gen.boolean), Gen.boolean) { (either, f, zero) =>
          ZIO.runtime[RemoteContext].flatMap { runtime =>
            Remote(either).filterOrElse(partialLift(runtime, f), Remote(zero)) <-> either.filterOrElse(f, zero)
          }
        }
      },
      test("swap") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          Remote(either).swap <-> either.swap
        }
      },
      test("joinRight") {
        check(Gen.either(Gen.int, Gen.either(Gen.int, Gen.long))) { either =>
          Remote(either).joinRight <-> either.joinRight
        }
      },
      test("joinLeft") {
        check(Gen.either(Gen.either(Gen.int, Gen.long), Gen.long)) { either =>
          Remote(either).joinLeft <-> either.joinLeft
        }
      },
      test("contains") {
        check(Gen.either(Gen.boolean, Gen.int), Gen.int) { (either, int) =>
          Remote(either).contains(Remote(int)) <-> either.contains(int)
        }
      },
      test("forall") {
        check(Gen.either(Gen.int, Gen.int), Gen.function(Gen.boolean)) { (either, f) =>
          ZIO.runtime[RemoteContext].flatMap { runtime =>
            Remote(either).forall(partialLift(runtime, f)) <-> either.forall(f)
          }
        }
      },
      test("exists") {
        check(Gen.either(Gen.int, Gen.int), Gen.function(Gen.boolean)) { (either, f) =>
          ZIO.runtime[RemoteContext].flatMap { runtime =>
            Remote(either).exists(partialLift(runtime, f)) <-> either.exists(f)
          }
        }
      },
      test("toSeq") {
        check(Gen.either(Gen.boolean, Gen.int)) { either =>
          Remote(either).toSeq <-> either.toSeq
        }
      },
      test("toOption") {
        check(Gen.either(Gen.boolean, Gen.int)) { either =>
          Remote(either).toOption <-> either.toOption
        }
      },
      suite("collectAll")(
        test("return the list of all right results") {
          check(Gen.listOf(Gen.int)) { list =>
            remote.RemoteEitherSyntax.collectAll(Remote(list.map(Right(_)): List[Either[Short, Int]])) <-> Right(list)
          }
        },
        test("return the first left result") {
          remote.RemoteEitherSyntax.collectAll(
            Remote(List(Right(2), Left("V"), Right(9), Right(0), Left("P")))
          ) <-> Left(
            "V"
          )
        }
      ),
      test("toTry") {
        check(Gen.either(Gen.throwable, Gen.int)) { either =>
          Remote(either).toTry <-> either.toTry
        }
      }
    ).provideCustom(RemoteContext.inMemory)

  private def partialLift[A, B: Schema](runtime: Runtime[RemoteContext], f: A => B): Remote[A] => Remote[B] =
    a =>
      Remote.Lazy[B] { () =>
        runtime.unsafeRun(a.eval) match {
          case Left(_)      => throw new IllegalStateException("Lifted functions in test requires a local value.")
          case Right(value) => Remote(f(value))
        }
      }

}
