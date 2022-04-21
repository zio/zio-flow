package zio.flow.serialization

import zio.flow._
import zio.schema.ast.SchemaAst
import zio.schema.codec.{Codec, JsonCodec}
import zio.schema.{DeriveSchema, Schema}
import zio.stream.ZNothing
import zio.test._
import zio.{Random, ZIO}

import scala.util.{Failure, Success, Try}

object RemoteSerializationSpec extends DefaultRunnableSpec with Generators {
  override def spec: ZSpec[TestEnvironment, Any] =
    suite("Remote serialization")(
      suite("roundtrip equality")(
        equalityWithCodec(JsonCodec)
        // equalityWithCodec(ProtobufCodec) // TODO: fix
      ),
      suite("roundtrip evaluation")(
        evalWithCodec(JsonCodec)
        //      evalWithCodec(ProtobufCodec) // TODO: fix
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
    val gen: Gen[Random with Sized, TestCaseClass] =
      for {
        a <- Gen.string
        b <- Gen.int
      } yield TestCaseClass(a, b)

    implicit val schema: Schema[TestCaseClass] = DeriveSchema.gen
  }

  private def equalityWithCodec(
    codec: Codec
  ): Spec[Random with Sized with TestConfig, TestFailure[String], TestSuccess] =
    suite(codec.getClass.getSimpleName)(
      test("literal") {
        check(genLiteral) { literal =>
          roundtrip(codec, literal)
        }
      },
      test("literal user type") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote(data)
          roundtripEval(codec, literal).provide(RemoteContext.inMemory)
        }
      },
      test("literal executing flow") {
        check(genExecutingFlow) { exFlow =>
          val literal = Remote(exFlow)
          roundtripEval(codec, literal).provide(RemoteContext.inMemory)
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
      test("add numeric")(roundtripCheck(codec, genAddNumeric)),
      test("div numeric")(roundtripCheck(codec, genDivNumeric)),
      test("mul numeric")(roundtripCheck(codec, genMulNumeric)),
      test("pow numeric")(roundtripCheck(codec, genPowNumeric)),
      test("negation numeric")(roundtripCheck(codec, genNegationNumeric)),
      test("root numeric")(roundtripCheck(codec, genRootNumeric)),
      test("log numeric")(roundtripCheck(codec, genLogNumeric)),
      test("absolute numeric")(roundtripCheck(codec, genAbsoluteNumeric)),
      test("mod numeric")(roundtripCheck(codec, genModNumeric)),
      test("min numeric")(roundtripCheck(codec, genMinNumeric)),
      test("max numeric")(roundtripCheck(codec, genMaxNumeric)),
      test("floor numeric")(roundtripCheck(codec, genFloorNumeric)),
      test("ceil numeric")(roundtripCheck(codec, genCeilNumeric)),
      test("round numeric")(roundtripCheck(codec, genRoundNumeric)),
      test("sin fractional")(roundtripCheck(codec, genSinFractional)),
      test("sin inverse fractional")(roundtripCheck(codec, genSinInverseFractional)),
      test("tan inverse fractional")(roundtripCheck(codec, genTanInverseFractional)),
      test("evaluated remote function")(roundtripCheck(codec, genEvaluatedRemoteFunction)),
      test("remote apply")(roundtripCheck(codec, genRemoteApply)),
      test("either0")(roundtripCheck(codec, genEither0)),
      test("flatMapEither")(roundtripCheck(codec, genFlatMapEither)),
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
      test("instant from long")(roundtripCheck(codec, genInstantFromLong)),
      test("instant from longs")(roundtripCheck(codec, genInstantFromLongs)),
      test("instant from milli")(roundtripCheck(codec, genInstantFromMilli)),
      test("instant from string")(roundtripCheck(codec, genInstantFromString)),
      test("instant to tuple")(roundtripCheck(codec, genInstantToTuple)),
      test("instant plus duration")(roundtripCheck(codec, genInstantPlusDuration)),
      test("instant minus duration")(roundtripCheck(codec, genInstantMinusDuration)),
      test("duration from string")(roundtripCheck(codec, genDurationFromString)),
      test("duration from long")(roundtripCheck(codec, genDurationFromLong)),
      test("duration from longs")(roundtripCheck(codec, genDurationFromLongs)),
      test("duration from big decimal")(roundtripCheck(codec, genDurationFromBigDecimal)),
      test("duration to longs")(roundtripCheck(codec, genDurationToLongs)),
      test("duration to long")(roundtripCheck(codec, genDurationToLong)),
      test("duration plus duration")(roundtripCheck(codec, genDurationPlusDuration)),
      test("duration minus duration")(roundtripCheck(codec, genDurationMinusDuration)),
      test("iterate")(roundtripCheck(codec, genIterate)),
      test("some0")(roundtripCheck(codec, genSome0)),
      test("fold option")(roundtripCheck(codec, genFoldOption)),
      test("zip option")(roundtripCheck(codec, genZipOption)),
      test("option contains")(roundtripCheck(codec, genOptionContains))
    )

  private def evalWithCodec(codec: Codec): Spec[Random with Sized with TestConfig, TestFailure[String], TestSuccess] =
    suite(codec.getClass.getSimpleName)(
      test("literal user type") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote(data)
          roundtripEval(codec, literal).provide(RemoteContext.inMemory)
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

          roundtripEval(codec, remote, compare)(schemaTry[Int]).provide(RemoteContext.inMemory)
        }
      },
      test("first") {
        check(TestCaseClass.gen zip Gen.string) { case (a, b) =>
          val first = Remote.TupleAccess[(TestCaseClass, String), TestCaseClass](Remote.Tuple2(Remote(a), Remote(b)), 0)
          roundtripEval(codec, first).provide(RemoteContext.inMemory)
        }
      },
      test("second") {
        check(TestCaseClass.gen zip Gen.string) { case (a, b) =>
          val first = Remote.TupleAccess[(String, TestCaseClass), TestCaseClass](Remote.Tuple2(Remote(b), Remote(a)), 1)
          roundtripEval(codec, first).provide(RemoteContext.inMemory)
        }
      },
      test("instant truncation") {
        check(Gen.instant zip genSmallChronoUnit) { case (instant, chronoUnit) =>
          val remote = Remote.InstantTruncate(Remote(instant), Remote(chronoUnit))
          roundtripEval(codec, remote).provide(RemoteContext.inMemory)
        }
      },
      test("duration from amount") {
        check(Gen.int zip genSmallChronoUnit) { case (amount, chronoUnit) =>
          val remote = Remote.DurationFromAmount(Remote(amount.toLong), Remote(chronoUnit))
          roundtripEval(codec, remote).provide(RemoteContext.inMemory)
        }
      },
      test("lazy") {
        check(Gen.int) { a =>
          val remote = Remote.Lazy(() => Remote(a))
          roundtripEval(codec, remote).provide(RemoteContext.inMemory)
        }
      },
      test("literal user type wrapped in Some") {
        check(TestCaseClass.gen) { data =>
          val literal = Remote.Some0(Remote(data))
          roundtripEval(codec, literal).provide(RemoteContext.inMemory)
        }
      }
    )

  private def roundtripCheck(
    codec: Codec,
    gen: Gen[Random with Sized, Remote[Any]]
  ): ZIO[Random with Sized with TestConfig, Nothing, TestResult] =
    check(gen) { value =>
      roundtrip(codec, value)
    }

  private def roundtrip(codec: Codec, value: Remote[Any]): Assert = {
    val encoded = codec.encode(Remote.schemaAny)(value)
    val decoded = codec.decode(Remote.schemaAny)(encoded)

//    println(s"$value => ${new String(encoded.toArray)} =>$decoded")

    assertTrue(decoded == Right(value))
  }

  private def roundtripEval[A: Schema](
    codec: Codec,
    value: Remote[A],
    test: (A, A) => Boolean = (a: A, b: A) => a == b
  ): ZIO[RemoteContext, String, Assert] = {
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
