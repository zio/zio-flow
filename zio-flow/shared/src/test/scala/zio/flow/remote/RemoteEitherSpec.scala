/*
 * Copyright 2021-2022 John A. De Goes and the ZIO Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package zio.flow.remote

import zio.ZLayer
import zio.flow._
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test.Assertion.{equalTo, succeeds}
import zio.test._

object RemoteEitherSpec extends RemoteSpecBase {

  def spec =
    suite("RemoteEitherSpec")(
      test("handleEither") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          val expected = either.fold(_ * 2, if (_) 10 else 20)
          val result   = Remote(either).fold(_ * 2, _.ifThenElse(10, 20))
          result <-> expected
        }
      },
      // TODO: fix
//      test("flatMap") {
//        check(Gen.either(Gen.int, Gen.int), Gen.function(Gen.either(Gen.int, Gen.long))) { (either, f) =>
//          ZIO.runtime[RemoteContext].flatMap { runtime =>
//            Remote(either).flatMap(partialLift(runtime, f)) <-> either.flatMap(f)
//          }
//        }
//      },
//      test("map") {
//        check(Gen.either(Gen.int, Gen.int), Gen.function(Gen.long)) { (either, f) =>
//          ZIO.runtime[RemoteContext].flatMap { runtime =>
//            Remote(either).map(partialLift(runtime, f)) <-> either.map(f)
//          }
//        }
//      },
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
      // TODO: fix
//      test("orElse") {
//        check(Gen.either(Gen.boolean, Gen.int), Gen.either(Gen.boolean, Gen.long)) { (eitherInt, eitherLong) =>
//          Remote(eitherInt).orElse(eitherLong) <-> eitherInt.fold(_ => eitherLong, b => Right(b))
//        }
//      },
//      test("filterOrElse") {
//        check(Gen.either(Gen.boolean, Gen.int), Gen.function(Gen.boolean), Gen.boolean) { (either, f, zero) =>
//          ZIO.runtime[RemoteContext].flatMap { runtime =>
//            Remote(either).filterOrElse(partialLift(runtime, f), Remote(zero)) <-> either.filterOrElse(f, zero)
//          }
//        }
//      },
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
//      test("forall") {
//        check(Gen.either(Gen.int, Gen.int), Gen.function(Gen.boolean)) { (either, f) =>
//          ZIO.runtime[RemoteContext].flatMap { runtime =>
//            Remote(either).forall(partialLift(runtime, f)) <-> either.forall(f)
//          }
//        }
//      },
//      test("exists") {
//        check(Gen.either(Gen.int, Gen.int), Gen.function(Gen.boolean)) { (either, f) =>
//          ZIO.runtime[RemoteContext].flatMap { runtime =>
//            Remote(either).exists(partialLift(runtime, f)) <-> either.exists(f)
//          }
//        }
//      },
//      test("toSeq") {
//        check(Gen.either(Gen.boolean, Gen.int)) { either =>
//          Remote(either).toSeq <-> either.toSeq
//        }
//      },
      test("toOption") {
        check(Gen.either(Gen.boolean, Gen.int)) { either =>
          Remote(either).toOption <-> either.toOption
        }
      },
      suite("collectAll")(
        test("return the list of all right results") {
          check(Gen.listOf(Gen.int)) { list =>
            remote.RemoteEitherSyntax.collectAll(
              Remote[List[Either[Short, Int]]](list.map(Right(_)))
            ) <-> Right(list)
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
          assertZIO(
            Remote(either).toTry.eval.exit
              .map(_.mapExit(_.fold(err => Left(err.getMessage), success => Right(success))))
          )(
            succeeds(
              equalTo(
                either.left.map(_.getMessage)
              )
            )
          )
        }
      }
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

  // TODO: fix tests using partialLift
  //  private def partialLift[A: Schema, B: Schema](runtime: Runtime[RemoteContext], f: A => B): Remote[A] => Remote[B] =
//    a =>
//      Remote.Lazy[B] { () =>
//        Remote(f(runtime.unsafeRun(a.eval)))
//      }
}
