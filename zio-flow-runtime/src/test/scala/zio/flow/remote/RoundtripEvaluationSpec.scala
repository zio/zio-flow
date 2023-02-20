/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

import zio.constraintless.TypeList._
import zio.flow._
import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow.serialization.Generators
import zio.flow.remote.RemoteSpec.TestCaseClass
import zio.schema.Schema
import zio.schema.codec.{BinaryCodecs, JsonCodec, ProtobufCodec}
import zio.test.{Gen, Sized, Spec, TestConfig, TestEnvironment, TestResult, ZIOSpecDefault, assertTrue, check}
import zio.{Scope, ZIO, ZLayer}

import scala.util.{Failure, Success, Try}

class RoundtripEvaluationSpec extends ZIOSpecDefault with Generators {

  private def jsonCodecs: BinaryCodecs[Remote[Any] :: End] = {
    import JsonCodec.schemaBasedBinaryCodec
    BinaryCodecs.make[Remote[Any] :: End]
  }

  private def protobufCodecs: BinaryCodecs[Remote[Any] :: End] = {
    import ProtobufCodec.protobufCodec
    BinaryCodecs.make[Remote[Any] :: End]
  }

  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("Remote roundtrip evaluation")(
      evalWithCodec("JSON", jsonCodecs),
      evalWithCodec("Protobuf", protobufCodecs)
    )

  private def evalWithCodec(
    label: String,
    codec: BinaryCodecs[Remote[Any] :: End]
  ): Spec[Sized with TestConfig, String] =
    suite(label)(
      test("literal user type") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote(data)
          roundtripEval(codec, literal).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      },
      test("try") {
        check(Gen.either(Gen.string, Gen.int)) { either =>
          val remote: Remote.Try[Int] = Remote.Try(
            either.fold(
              msg => {
                val throwable: Throwable = new Generators.TestException(msg)
                Left(Remote(throwable))
              },
              value => Right(value)
            )
          )

          def compare(a: Try[Int], b: Try[Int]): Boolean =
            (a, b) match {
              case (Success(x), Success(y)) => x == y
              case (Failure(x), Failure(y)) => x.getMessage == y.getMessage
              case _                        => false
            }

          roundtripEval(codec, remote, compare)(schemaTry[Int])
            .provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      },
      test("first") {
        check(TestCaseClass.gen zip Gen.string) { case (a, b) =>
          val first =
            Remote.TupleAccess[(TestCaseClass, String), TestCaseClass](Remote.Tuple2(Remote(a), Remote(b)), 0, 2)
          roundtripEval(codec, first).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      },
      test("second") {
        check(TestCaseClass.gen zip Gen.string) { case (a, b) =>
          val first =
            Remote.TupleAccess[(String, TestCaseClass), TestCaseClass](Remote.Tuple2(Remote(b), Remote(a)), 1, 2)
          roundtripEval(codec, first).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      },
      test("duration from amount") {
        check(Gen.int zip genSmallChronoUnit) { case (amount, chronoUnit) =>
          val remote = Remote.DurationFromAmount(Remote(amount.toLong), Remote(chronoUnit))
          roundtripEval(codec, remote).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      },
      test("lazy") {
        check(Gen.int) { a =>
          val remote = Remote.Lazy(() => Remote(a))
          roundtripEval(codec, remote).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      },
      test("literal user type wrapped in Some") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote.RemoteSome(Remote(data))
          roundtripEval(codec, literal).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      },
      test("literal user type") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote(data)
          roundtripEval(codec, literal).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      },
      test("literal executing flow") {
        check(genExecutingFlow) { exFlow =>
          val literal = Remote(exFlow)
          roundtripEval(codec, literal).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)
        }
      }
    )

  private def roundtripEval[A: Schema](
    codec: BinaryCodecs[Remote[Any] :: End],
    value: Remote[A],
    test: (A, A) => Boolean = (a: A, b: A) => a == b
  ): ZIO[RemoteContext with LocalContext, String, TestResult] = {
    val encoded = codec.encode(value.asInstanceOf[Remote[Any]])
    val decoded = codec.decode(encoded)

    //    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    for {
      originalEvaluated <- value.eval[A].mapError(_.toMessage)
      newRemote         <- ZIO.fromEither(decoded).mapError(_.message)
      newEvaluated      <- newRemote.asInstanceOf[Remote[A]].eval[A].mapError(_.toMessage)
    } yield assertTrue(test(originalEvaluated, newEvaluated))
  }

}
