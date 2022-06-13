package zio.flow.serialization

import zio.flow._
import zio.schema.ast.SchemaAst
import zio.schema.codec.{Codec, JsonCodec}
import zio.schema.{DeriveSchema, Schema}
import zio.test._
import zio.{ZIO, ZLayer, ZNothing}

import scala.util.{Failure, Success, Try}
import zio.schema.codec.ProtobufCodec

object RemoteSerializationSpec extends ZIOSpecDefault with Generators {
  override def spec: Spec[TestEnvironment, Any] =
    suite("Remote serialization")(
      suite("roundtrip equality")(
        equalityWithCodec(JsonCodec),
        equalityWithCodec(ProtobufCodec)
      ),
      suite("roundtrip evaluation")(
        evalWithCodec(JsonCodec),
        evalWithCodec(ProtobufCodec)
      ),
      test("Remote schema is serializable") {
        val schema             = Remote.schemaAny
        val serialized         = JsonCodec.encode(SchemaAst.schema)(schema.ast)
        val deserialized       = JsonCodec.decode(SchemaAst.schema)(serialized)
        val deserializedSchema = deserialized.map(_.toSchema)
        assertTrue(
          Schema.structureEquality.equal(schema, deserializedSchema.toOption.get)
        )
      } @@ TestAspect.ignore // TODO: fix recursion
    )

  case class TestCaseClass(a: String, b: Int)
  object TestCaseClass {
    val gen: Gen[Sized, TestCaseClass] =
      for {
        a <- Gen.string
        b <- Gen.int
      } yield TestCaseClass(a, b)

    implicit val schema: Schema[TestCaseClass] = DeriveSchema.gen
  }

  private def equalityWithCodec(
    codec: Codec
  ): Spec[Sized with TestConfig, String] =
    suite(codec.getClass.getSimpleName)(
      test("literal") {
        check(genLiteral) { literal =>
          roundtrip(codec, literal)
        }
      },
      test("literal user type") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote(data)
          roundtripEval(codec, literal).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("literal executing flow") {
        check(genExecutingFlow) { exFlow =>
          val literal = Remote(exFlow)
          roundtripEval(codec, literal).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("ignore") {
        roundtrip(codec, Remote.Ignore())
      },
      test("flow")(roundtripCheck(codec, genRemoteFlow)),
      test("nested")(roundtripCheck(codec, genNested)),
      test("variable")(roundtripCheck(codec, genRemoteVariable)),
      test("variable of nothing") {
        val variable = Remote.Variable[ZNothing](RemoteVariableName("test"), schemaZNothing)
        roundtrip(codec, variable)
      },
      test("binary numeric")(roundtripCheck(codec, genBinaryNumeric)),
      test("unary numeric")(roundtripCheck(codec, genUnaryNumeric)),
      test("unary fractional")(roundtripCheck(codec, genUnaryFractional)),
      test("evaluated remote function")(roundtripCheck(codec, genEvaluatedRemoteFunction)),
      test("remote apply")(roundtripCheck(codec, genRemoteApply)),
      test("remote either")(roundtripCheck(codec, genRemoteEither)),
      test("foldEither")(roundtripCheck(codec, genFoldEither)),
      test("swapEither")(roundtripCheck(codec, genSwapEither)),
      test("try")(roundtripCheck(codec, genTry)),
      test("tuple2")(roundtripCheck(codec, genTuple2)),
      test("tuple3")(roundtripCheck(codec, genTuple3)),
      test("tuple4")(roundtripCheck(codec, genTuple4)),
      test("tupleAccess")(roundtripCheck(codec, genTupleAccess)),
      test("branch")(roundtripCheck(codec, genBranch)),
      test("length")(roundtripCheck(codec, genLength)),
      test("less than equal")(roundtripCheck(codec, genLessThanEqual)),
      test("equal")(roundtripCheck(codec, genEqual)),
      test("not")(roundtripCheck(codec, genNot)),
      test("and")(roundtripCheck(codec, genAnd)),
      test("fold")(roundtripCheck(codec, genFold)),
      test("cons")(roundtripCheck(codec, genCons)),
      test("uncons")(roundtripCheck(codec, genUnCons)),
      test("instant from longs")(roundtripCheck(codec, genInstantFromLongs)),
      test("instant from string")(roundtripCheck(codec, genInstantFromString)),
      test("instant to tuple")(roundtripCheck(codec, genInstantToTuple)),
      test("instant plus duration")(roundtripCheck(codec, genInstantPlusDuration)),
      test("duration from string")(roundtripCheck(codec, genDurationFromString)),
      test("duration from longs")(roundtripCheck(codec, genDurationFromLongs)),
      test("duration from big decimal")(roundtripCheck(codec, genDurationFromBigDecimal)),
      test("duration to longs")(roundtripCheck(codec, genDurationToLongs)),
      test("duration plus duration")(roundtripCheck(codec, genDurationPlusDuration)),
      test("duration multiplied by")(roundtripCheck(codec, genDurationMultipledBy)),
      test("iterate")(roundtripCheck(codec, genIterate)),
      test("remote some")(roundtripCheck(codec, genRemoteSome)),
      test("fold option")(roundtripCheck(codec, genFoldOption))
    )

  private def evalWithCodec(codec: Codec): Spec[Sized with TestConfig, String] =
    suite(codec.getClass.getSimpleName)(
      test("literal user type") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote(data)
          roundtripEval(codec, literal).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("try") {
        check(Gen.either(Gen.string, Gen.int)) { either =>
          val remote: Remote.Try[Int] = Remote.Try(
            either.fold(
              msg => {
                val throwable: Throwable = new Generators.TestException(msg)
                Left((Remote(throwable), Schema[Int]))
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

          roundtripEval(codec, remote, compare)(schemaTry[Int]).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("first") {
        check(TestCaseClass.gen zip Gen.string) { case (a, b) =>
          val first = Remote.TupleAccess[(TestCaseClass, String), TestCaseClass](Remote.Tuple2(Remote(a), Remote(b)), 0)
          roundtripEval(codec, first).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("second") {
        check(TestCaseClass.gen zip Gen.string) { case (a, b) =>
          val first = Remote.TupleAccess[(String, TestCaseClass), TestCaseClass](Remote.Tuple2(Remote(b), Remote(a)), 1)
          roundtripEval(codec, first).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("instant truncation") {
        check(Gen.instant zip genSmallChronoUnit) { case (instant, chronoUnit) =>
          val remote = Remote.InstantTruncate(Remote(instant), Remote(chronoUnit))
          roundtripEval(codec, remote).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("duration from amount") {
        check(Gen.int zip genSmallChronoUnit) { case (amount, chronoUnit) =>
          val remote = Remote.DurationFromAmount(Remote(amount.toLong), Remote(chronoUnit))
          roundtripEval(codec, remote).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("lazy") {
        check(Gen.int) { a =>
          val remote = Remote.Lazy(() => Remote(a))
          roundtripEval(codec, remote).provide(ZLayer(RemoteContext.inMemory))
        }
      },
      test("literal user type wrapped in Some") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote.RemoteSome(Remote(data))
          roundtripEval(codec, literal).provide(ZLayer(RemoteContext.inMemory))
        }
      }
    )

  private def roundtripCheck(
    codec: Codec,
    gen: Gen[Sized, Remote[Any]]
  ): ZIO[Sized with TestConfig, Nothing, TestResult] =
    check(gen) { value =>
      roundtrip(codec, value)
    }

  private def roundtrip(codec: Codec, value: Remote[Any]): TestResult = {
    val encoded = codec.encode(Remote.schemaAny)(value)
    val decoded = codec.decode(Remote.schemaAny)(encoded)

    //    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    assertTrue(decoded == Right(value))
  }

  private def roundtripEval[A: Schema](
    codec: Codec,
    value: Remote[A],
    test: (A, A) => Boolean = (a: A, b: A) => a == b
  ): ZIO[RemoteContext, String, TestResult] = {
    val encoded = codec.encode(Remote.schemaAny)(value)
    val decoded = codec.decode(Remote.schemaAny)(encoded)

//    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    for {
      originalEvaluated <- value.eval[A]
      newRemote         <- ZIO.fromEither(decoded)
      newEvaluated      <- newRemote.asInstanceOf[Remote[A]].eval[A]
    } yield assertTrue(test(originalEvaluated, newEvaluated))
  }

}
