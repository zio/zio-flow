package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.random.Random
import zio.schema.Schema
import zio.test._

object RemoteEitherSpec extends DefaultRunnableSpec {
  def spec: ZSpec[TestConfig with Random with Sized with Annotations, Any] = suite("RemoteEitherSpec")(
    testM("handleEither") {
      check(Gen.either(Gen.anyInt, Gen.boolean)) { either =>
        val expected = either.fold(_ * 2, if (_) 10 else 20)
        val result   = Remote(either).handleEither(_ * 2, _.ifThenElse(10, 20))
        result <-> expected
      }
    },
    testM("flatMap") {
      check(Gen.either(Gen.anyInt, Gen.anyInt), Gen.function(Gen.either(Gen.anyInt, Gen.anyLong))) { (either, f) =>
        Remote(either).flatMap(partialLift(f)) <-> either.flatMap(f)
      }
    },
    testM("map") {
      check(Gen.either(Gen.anyInt, Gen.anyInt), Gen.function(Gen.anyLong)) { (either, f) =>
        Remote(either).map(partialLift(f)) <-> either.map(f)
      }
    },
    testM("flatten") {
      check(Gen.either(Gen.anyInt, Gen.either(Gen.anyInt, Gen.anyLong))) { either =>
        Remote(either).flatten <-> either.fold(a => Left(a), b => b)
      }
    },
    testM("merge") {
      check(Gen.either(Gen.anyInt, Gen.anyInt)) { either =>
        Remote(either).merge <-> either.fold(identity, identity)
      }
    },
    testM("isRight") {
      check(Gen.either(Gen.anyInt, Gen.boolean)) { either =>
        Remote(either).isRight <-> either.isRight
      }
    },
    testM("handleEither") {
      check(Gen.either(Gen.anyInt, Gen.boolean)) { either =>
        Remote(either).isLeft <-> either.isLeft
      }
    },
    testM("getOrElse") {
      check(Gen.either(Gen.boolean, Gen.anyInt), Gen.anyInt) { (either, int) =>
        Remote(either).getOrElse(int) <-> either.getOrElse(int)
      }
    },
    testM("orElse") {
      check(Gen.either(Gen.boolean, Gen.anyInt), Gen.either(Gen.boolean, Gen.anyLong)) { (eitherInt, eitherLong) =>
        Remote(eitherInt).orElse(eitherLong) <-> eitherInt.fold(_ => eitherLong, b => Right(b))
      }
    },
    testM("filterOrElse") {
      check(Gen.either(Gen.boolean, Gen.anyInt), Gen.function(Gen.boolean), Gen.boolean) { (either, f, zero) =>
        Remote(either).filterOrElse(partialLift(f), Remote(zero)) <-> either.filterOrElse(f, zero)
      }
    },
    testM("swap") {
      check(Gen.either(Gen.anyInt, Gen.boolean)) { either =>
        Remote(either).swap <-> either.swap
      }
    },
    testM("joinRight") {
      check(Gen.either(Gen.anyInt, Gen.either(Gen.anyInt, Gen.anyLong))) { either =>
        Remote(either).joinRight <-> either.joinRight
      }
    },
    testM("joinLeft") {
      check(Gen.either(Gen.either(Gen.anyInt, Gen.anyLong), Gen.anyLong)) { either =>
        Remote(either).joinLeft <-> either.joinLeft
      }
    },
    testM("contains") {
      check(Gen.either(Gen.boolean, Gen.anyInt), Gen.anyInt) { (either, int) =>
        Remote(either).contains(Remote(int)) <-> either.contains(int)
      }
    },
    testM("forall") {
      check(Gen.either(Gen.anyInt, Gen.anyInt), Gen.function(Gen.boolean)) { (either, f) =>
        Remote(either).forall(partialLift(f)) <-> either.forall(f)
      }
    },
    testM("exists") {
      check(Gen.either(Gen.anyInt, Gen.anyInt), Gen.function(Gen.boolean)) { (either, f) =>
        Remote(either).exists(partialLift(f)) <-> either.exists(f)
      }
    },
    testM("toSeq") {
      check(Gen.either(Gen.boolean, Gen.anyInt)) { either =>
        Remote(either).toSeq <-> either.toSeq
      }
    },
    testM("toOption") {
      check(Gen.either(Gen.boolean, Gen.anyInt)) { either =>
        Remote(either).toOption <-> either.toOption
      }
    },
    suite("collectAll")(
      testM("return the list of all right results") {
        check(Gen.listOf(Gen.anyInt)) { list =>
          remote.RemoteEitherSyntax.collectAll(Remote(list.map(Right(_)): List[Either[Short, Int]])) <-> Right(list)
        }
      },
      test("return the first left result") {
        remote.RemoteEitherSyntax.collectAll(Remote(List(Right(2), Left("V"), Right(9), Right(0), Left("P")))) <-> Left(
          "V"
        )
      }
    )
  )

  private def partialLift[A, B: Schema](f: A => B): Remote[A] => Remote[B] = a =>
    a.eval match {
      case Left(_)      => throw new IllegalStateException("Lifted functions in test requires a local value.")
      case Right(value) => Remote(f(value))
    }

}
