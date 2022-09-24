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

object RemoteEitherSyntaxSpec extends RemoteSpecBase {

  def spec =
    suite("RemoteEitherSyntax")(
      test("contains") {
        check(Gen.either(Gen.boolean, Gen.int), Gen.int) { (either, int) =>
          Remote(either).contains(Remote(int)) <-> either.contains(int)
        }
      },
      test("exists") {
        check(Gen.either(Gen.int, Gen.int)) { (either) =>
          Remote(either).exists(_ % 2 === 0) <-> either.exists(_ % 2 == 0)
        }
      },
      test("filterOrElse") {
        check(Gen.either(Gen.int, Gen.int)) { (either) =>
          Remote(either).filterOrElse(_ % 2 === 0, 111) <-> either.filterOrElse(_ % 2 == 0, 111)
        }
      },
      test("flatMap") {
        check(Gen.either(Gen.int, Gen.int), Gen.either(Gen.int, Gen.int)) { (either, either2) =>
          Remote(either).flatMap(r => Remote(either2).map(_ => r)) <-> either.flatMap(r => either2.map(_ => r))
        }
      },
      test("flatten") {
        check(Gen.either(Gen.int, Gen.int), Gen.either(Gen.int, Gen.int)) { (either, either2) =>
          Remote(either).map(_ => either2).flatten <-> either.map(_ => either2).flatten
        }
      },
      test("fold") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          val expected = either.fold(_ * 2, if (_) 10 else 20)
          val result   = Remote(either).fold(_ * 2, _.ifThenElse(10, 20))
          result <-> expected
        }
      },
      test("forall") {
        check(Gen.either(Gen.int, Gen.int)) { either =>
          Remote(either).forall(_ % 2 === 0) <-> either.forall(_ % 2 == 0)
        }
      },
      test("getOrElse") {
        check(Gen.either(Gen.boolean, Gen.int), Gen.int) { (either, int) =>
          Remote(either).getOrElse(int) <-> either.getOrElse(int)
        }
      },
      test("isLeft") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          Remote(either).isLeft <-> either.isLeft
        }
      },
      test("isRight") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          Remote(either).isRight <-> either.isRight
        }
      },
      test("joinLeft") {
        check(Gen.either(Gen.either(Gen.int, Gen.long), Gen.long)) { either =>
          Remote(either).joinLeft <-> either.joinLeft
        }
      },
      test("joinRight") {
        check(Gen.either(Gen.int, Gen.either(Gen.int, Gen.long))) { either =>
          Remote(either).joinRight <-> either.joinRight
        }
      },
      suite("left")(
        test("getOrElse") {
          check(Gen.either(Gen.string, Gen.int), Gen.string) { (either, fallback) =>
            Remote(either).left.getOrElse(fallback) <-> either.left.getOrElse(fallback)
          }
        },
        test("forall") {
          check(Gen.either(Gen.string, Gen.int)) { either =>
            Remote(either).left.forall(_.length > 3) <-> either.left.forall(_.length > 3)
          }
        },
        test("exists") {
          check(Gen.either(Gen.string, Gen.int)) { either =>
            Remote(either).left.exists(_.length > 3) <-> either.left.exists(_.length > 3)
          }
        },
        test("filterToOption") {
          check(Gen.either(Gen.string, Gen.int)) { either =>
            Remote(either).left.filterToOption[Int](_.length > 3) <-> either.left.filterToOption(_.length > 3)
          }
        },
        test("flatMap") {
          check(Gen.either(Gen.int, Gen.int), Gen.either(Gen.int, Gen.int)) { (either, either2) =>
            Remote(either).left.flatMap(r => Remote(either2).map(_ => r)) <-> either.left.flatMap(r =>
              either2.map(_ => r)
            )
          }
        },
        test("map") {
          check(Gen.either(Gen.int, Gen.string), Gen.int) { (either, n) =>
            Remote(either).left.map(_ + n) <-> either.left.map(_ + n)
          }
        },
        test("toSeq") {
          check(Gen.either(Gen.int, Gen.string)) { either =>
            Remote(either).left.toSeq <-> either.left.toSeq.toList
          }
        },
        test("toOption") {
          check(Gen.either(Gen.int, Gen.string)) { either =>
            Remote(either).left.toOption <-> either.left.toOption
          }
        }
      ),
      test("map") {
        check(Gen.either(Gen.string, Gen.int), Gen.int) { (either, n) =>
          Remote(either).map(_ + n) <-> either.map(_ + n)
        }
      },
      test("merge") {
        check(Gen.either(Gen.int, Gen.int)) { either =>
          Remote(either).merge <-> either.merge
        }
      },
      test("orElse") {
        check(Gen.either(Gen.boolean, Gen.int), Gen.either(Gen.boolean, Gen.int)) { (either1, either2) =>
          Remote(either1).orElse(either2) <-> either1.orElse(either2)
        }
      },
      test("swap") {
        check(Gen.either(Gen.int, Gen.boolean)) { either =>
          Remote(either).swap <-> either.swap
        }
      },
      test("toOption") {
        check(Gen.either(Gen.boolean, Gen.int)) { either =>
          Remote(either).toOption <-> either.toOption
        }
      },
      test("toSeq") {
        check(Gen.either(Gen.boolean, Gen.int)) { either =>
          Remote(either).toSeq <-> either.toSeq.toList
        }
      },
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
      )
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)
}
