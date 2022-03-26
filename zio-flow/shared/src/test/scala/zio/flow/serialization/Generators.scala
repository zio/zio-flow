package zio.flow.serialization

import zio.flow.Remote.RemoteFunction
import zio.{Duration, Random, flow}
import zio.flow.{
  Activity,
  ActivityError,
  EmailRequest,
  Operation,
  Remote,
  RemoteVariableName,
  SchemaAndValue,
  SchemaOrNothing,
  ZFlow
}
import zio.flow.remote.{Fractional, Numeric}
import zio.flow.serialization.Generators.TestException
import zio.schema.{DefaultJavaTimeSchemas, Schema}
import zio.test.{Gen, Sized}

import java.time.Instant
import java.time.temporal.ChronoUnit

trait Generators extends DefaultJavaTimeSchemas {

  lazy val genPrimitiveSchemaAndValue: Gen[Random with Sized, SchemaAndValue[Any]] =
    Gen.oneOf(
      Gen.string.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[String], value)),
      Gen.int.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)),
      Gen.double.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[Double], value)),
      Gen.instant.map(value => SchemaAndValue.fromSchemaAndValue(instantSchema, value))
    )

  lazy val genRemoteVariableName: Gen[Random with Sized, flow.RemoteVariableName.Type] =
    Gen.string1(Gen.alphaNumericChar).map(RemoteVariableName.apply(_))

  lazy val genNumeric: Gen[Random, (Numeric[Any], Gen[Random, Remote[Any]])] =
    Gen.oneOf(
      Gen
        .const(Numeric.NumericShort)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.short.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Short], value)))
          )
        ),
      Gen
        .const(Numeric.NumericInt)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.int.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)))
          )
        ),
      Gen
        .const(Numeric.NumericLong)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.long.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Long], value)))
          )
        ),
      Gen
        .const(Numeric.NumericFloat)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.float.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Float], value)))
          )
        ),
      Gen
        .const(Numeric.NumericDouble)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.double.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Double], value)))
          )
        ),
      Gen
        .const(Numeric.NumericBigInt)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen
              .bigInt(BigInt(-1000), BigInt(1000))
              .map(value =>
                Remote
                  .Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[java.math.BigInteger], value.bigInteger))
              )
          )
        ),
      Gen
        .const(Numeric.NumericBigDecimal)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen
              .bigDecimal(BigDecimal(-1000), BigDecimal(1000))
              .map(value =>
                Remote
                  .Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[java.math.BigDecimal], value.bigDecimal))
              )
          )
        )
    )

  lazy val genFractional: Gen[Random, (Fractional[Any], Gen[Random, Remote[Any]])] =
    Gen.oneOf(
      Gen
        .const(Fractional.FractionalFloat)
        .map(n =>
          (
            n.asInstanceOf[Fractional[Any]],
            Gen.float.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Float], value)))
          )
        ),
      Gen
        .const(Fractional.FractionalDouble)
        .map(n =>
          (
            n.asInstanceOf[Fractional[Any]],
            Gen.double.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Double], value)))
          )
        ),
      Gen
        .const(Fractional.FractionalBigDecimal)
        .map(n =>
          (
            n.asInstanceOf[Fractional[Any]],
            Gen
              .bigDecimal(BigDecimal(-1000), BigDecimal(1000))
              .map(value =>
                Remote
                  .Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[java.math.BigDecimal], value.bigDecimal))
              )
          )
        )
    )

  lazy val genThrowableSchemaAndValue: Gen[Random with Sized, SchemaAndValue[Throwable]] =
    Gen.alphaNumericString.map(msg =>
      SchemaAndValue.fromSchemaAndValue(
        SchemaOrNothing.fromSchema(zio.flow.schemaThrowable).schema,
        new TestException(msg)
      )
    )

  lazy val genLiteral: Gen[Random with Sized, Remote[Any]] =
    Gen.oneOf(genPrimitiveSchemaAndValue).map(Remote.Literal(_))

  lazy val genRemoteFlow: Gen[Random with Sized, Remote[Any]] =
    Gen
      .oneOf(
        genZFlowReturn,
        genZFlowNow,
        genZFlowWaitTill
      )
      .map(Remote.Flow(_))

  lazy val genRemoteVariable: Gen[Random with Sized, Remote[Any]] =
    for {
      name           <- genRemoteVariableName
      schemaAndValue <- genPrimitiveSchemaAndValue
    } yield Remote.Variable(name, schemaAndValue.schema)

  lazy val genAddNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.AddNumeric(left, right, numeric)

  lazy val genDivNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.DivNumeric(left, right, numeric)

  lazy val genMulNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.MulNumeric(left, right, numeric)

  lazy val genPowNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
    } yield Remote.PowNumeric(left, right, numeric)

  lazy val genNegationNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.NegationNumeric(value, numeric)

  lazy val genRootNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      n             <- gen
    } yield Remote.RootNumeric(value, n, numeric)

  lazy val genLogNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      base          <- gen
    } yield Remote.LogNumeric(value, base, numeric)

  lazy val genModNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      left  <- Gen.int.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)))
      right <- Gen.int.map(value => Remote.Literal(SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)))
    } yield Remote.ModNumeric(left, right)

  lazy val genAbsoluteNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.AbsoluteNumeric(value, numeric)

  lazy val genFloorNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.FloorNumeric(value, numeric)

  lazy val genCeilNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.CeilNumeric(value, numeric)

  lazy val genRoundNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
    } yield Remote.RoundNumeric(value, numeric)

  lazy val genMinNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      base          <- gen
    } yield Remote.MinNumeric(value, base, numeric)

  lazy val genMaxNumeric: Gen[Random with Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      base          <- gen
    } yield Remote.MaxNumeric(value, base, numeric)

  lazy val genSinFractional: Gen[Random with Sized, Remote[Any]] =
    for {
      pair             <- genFractional
      (fractional, gen) = pair
      value            <- gen
    } yield Remote.SinFractional(value, fractional)

  lazy val genSinInverseFractional: Gen[Random with Sized, Remote[Any]] =
    for {
      pair             <- genFractional
      (fractional, gen) = pair
      value            <- gen
    } yield Remote.SinInverseFractional(value, fractional)

  lazy val genTanInverseFractional: Gen[Random with Sized, Remote[Any]] =
    for {
      pair             <- genFractional
      (fractional, gen) = pair
      value            <- gen
    } yield Remote.TanInverseFractional(value, fractional)

  lazy val genEvaluatedRemoteFunction: Gen[Random with Sized, Remote[Any]] =
    for {
      v <- genRemoteVariable
      r <- Gen.oneOf(genLiteral, genRemoteVariable, genAddNumeric)
    } yield Remote.EvaluatedRemoteFunction(v.asInstanceOf[Remote.Variable[Any]], r)

  lazy val genEvaluatedRemoteFunctionWithResultSchema: Gen[Random with Sized, (Remote[Any], Schema[Any])] =
    for {
      v <- genRemoteVariable
      (r, s) <- Gen.oneOf(
                  genLiteral.map(l => (l, l.asInstanceOf[Remote.Literal[Any]].schemaA)),
                  genRemoteVariable.map(l => (l, l.asInstanceOf[Remote.Variable[Any]].schemaA)),
                  genAddNumeric.map(l => (l, l.asInstanceOf[Remote.AddNumeric[Any]].numeric.schema))
                )
    } yield (Remote.EvaluatedRemoteFunction(v.asInstanceOf[Remote.Variable[Any]], r), s)

  lazy val genRemoteApply: Gen[Random with Sized, Remote[Any]] =
    for {
      f <- genEvaluatedRemoteFunction
      a <- genLiteral
    } yield Remote.RemoteApply(f.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Any]], a)

  lazy val genEither0: Gen[Random with Sized, Remote[Any]] =
    for {
      left  <- genPrimitiveSchemaAndValue
      right <- genPrimitiveSchemaAndValue
      either <- Gen.oneOf(
                  Gen.const(Left((left.toRemote, SchemaOrNothing.fromSchema(right.schema.asInstanceOf[Schema[Any]])))),
                  Gen.const(Right((SchemaOrNothing.fromSchema(left.schema.asInstanceOf[Schema[Any]]), right.toRemote)))
                )
    } yield Remote.Either0(either)

  lazy val genFlatMapEither: Gen[Random with Sized, Remote[Any]] =
    for {
      left  <- genPrimitiveSchemaAndValue
      right <- genPrimitiveSchemaAndValue
      either <- Gen.oneOf(
                  Gen.const(Left((left.toRemote, SchemaOrNothing.fromSchema(right.schema.asInstanceOf[Schema[Any]])))),
                  Gen.const(Right((SchemaOrNothing.fromSchema(left.schema.asInstanceOf[Schema[Any]]), right.toRemote)))
                )
      (f, cSchema) <- genEvaluatedRemoteFunctionWithResultSchema
      aSchema       = SchemaOrNothing.fromSchema(left.schema.asInstanceOf[Schema[Any]])
    } yield Remote.FlatMapEither[Any, Any, Any](
      Remote.Either0(either),
      f.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Either[Any, Any]]],
      aSchema,
      SchemaOrNothing.fromSchema(cSchema)
    )

  lazy val genFoldEither: Gen[Random with Sized, Remote[Any]] =
    for {
      left  <- genPrimitiveSchemaAndValue
      right <- genPrimitiveSchemaAndValue
      either <- Gen.oneOf(
                  Gen.const(Left((left.toRemote, SchemaOrNothing.fromSchema(right.schema.asInstanceOf[Schema[Any]])))),
                  Gen.const(Right((SchemaOrNothing.fromSchema(left.schema.asInstanceOf[Schema[Any]]), right.toRemote)))
                )
      // TODO: generate functions compatible with the generated either
      leftF  <- genEvaluatedRemoteFunction.map(_.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Either[Any, Any]]])
      rightF <- genEvaluatedRemoteFunction.map(_.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Either[Any, Any]]])
    } yield Remote.FoldEither(Remote.Either0(either), leftF, rightF)

  lazy val genSwapEither: Gen[Random with Sized, Remote[Any]] =
    genEither0.map(r => Remote.SwapEither(r.asInstanceOf[Remote[Either[Any, Any]]]))

  lazy val genTry: Gen[Random with Sized, Remote[Any]] =
    for {
      value <- genPrimitiveSchemaAndValue
      error <- genThrowableSchemaAndValue
      either <- Gen.either(
                  Gen.const(
                    (
                      error.toRemote,
                      SchemaOrNothing.fromSchema(value.schema.asInstanceOf[Schema[Any]])
                    )
                  ),
                  Gen.const(value.toRemote)
                )
    } yield Remote.Try(either)

  lazy val genTuple2: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genAddNumeric
    } yield Remote.Tuple2(a, b)

  lazy val genTuple3: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genAddNumeric
      c <- genRemoteVariable
    } yield Remote.Tuple3(a, b, c)

  lazy val genTuple4: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genEvaluatedRemoteFunction
      c <- genPowNumeric
      d <- genEither0
    } yield Remote.Tuple4(a, b, c, d)

  lazy val genFirst: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
    } yield Remote.First(Remote.Tuple2(a, b))

  lazy val genSecond: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
    } yield Remote.Second(Remote.Tuple2(a, b))

  lazy val genFirstOf3: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
      c <- genLiteral
    } yield Remote.FirstOf3(Remote.Tuple3(a, b, c))

  lazy val genSecondOf3: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
      c <- genLiteral
    } yield Remote.SecondOf3(Remote.Tuple3(a, b, c))

  lazy val genThirdOf3: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
      c <- genLiteral
    } yield Remote.ThirdOf3(Remote.Tuple3(a, b, c))

  lazy val genFirstOf4: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
      c <- genLiteral
      d <- genLiteral
    } yield Remote.FirstOf4(Remote.Tuple4(a, b, c, d))

  lazy val genSecondOf4: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
      c <- genLiteral
      d <- genLiteral
    } yield Remote.SecondOf4(Remote.Tuple4(a, b, c, d))

  lazy val genThirdOf4: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
      c <- genLiteral
      d <- genLiteral
    } yield Remote.ThirdOf4(Remote.Tuple4(a, b, c, d))

  lazy val genFourthOf4: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genLiteral
      c <- genLiteral
      d <- genLiteral
    } yield Remote.ThirdOf4(Remote.Tuple4(a, b, c, d))

  lazy val genBranch: Gen[Random with Sized, Remote[Any]] =
    for {
      condition <- Gen.boolean.map(Remote(_))
      ifTrue    <- Gen.int.map(Remote(_))
      ifFalse   <- Gen.int.map(n => Remote.MulNumeric(Remote(10), Remote(n), Numeric.NumericInt))
    } yield Remote.Branch(condition, ifTrue, ifFalse)

  lazy val genLength: Gen[Random with Sized, Remote[Any]] =
    Gen.string.map(Remote(_)).map(Remote.Length(_))

  lazy val genLessThanEqual: Gen[Random with Sized, Remote[Any]] =
    for {
      lv  <- Gen.int
      rv  <- Gen.int
      lLit = Remote(lv)
      rLit = Remote(rv)
    } yield Remote.LessThanEqual(lLit, rLit)

  lazy val genEqual: Gen[Random with Sized, Remote[Any]] =
    for {
      lv  <- Gen.string
      rv  <- Gen.string
      lLit = Remote(lv)
      rLit = Remote(rv)
    } yield Remote.Equal(lLit, rLit)

  lazy val genNot: Gen[Random with Sized, Remote[Any]] =
    for {
      value <- Gen.boolean.map(Remote(_))
    } yield Remote.Not(value)

  lazy val genAnd: Gen[Random with Sized, Remote[Any]] =
    for {
      left <- Gen.boolean.map(Remote(_))
      right = Remote.RemoteFunction((b: Remote[Boolean]) => Remote.Not(b)).evaluated
    } yield Remote.And(left, right)

  lazy val genFold: Gen[Random with Sized, Remote[Any]] =
    for {
      list    <- Gen.listOf(Gen.double).map(Remote(_))
      initial <- Gen.double(-1000.0, 1000.0).map(Remote(_))
      fun = Remote.RemoteFunction((tuple: Remote[(Double, Double)]) =>
              Remote.AddNumeric(Remote.First(tuple), Remote.Second(tuple), Numeric.NumericDouble)
            )
    } yield Remote.Fold(list, initial, fun.evaluated)

  lazy val genCons: Gen[Random with Sized, Remote[Any]] =
    for {
      list <- Gen.listOf(Gen.int).map(Remote(_))
      head <- Gen.int.map(Remote(_))
    } yield Remote.Cons(list, head)

  lazy val genUnCons: Gen[Random with Sized, Remote[Any]] =
    for {
      list <- Gen.listOf(Gen.int zip Gen.string).map(Remote(_))
    } yield Remote.UnCons(list)

  lazy val genInstantFromLong: Gen[Random with Sized, Remote[Any]] =
    Gen.long.map(l => Remote.InstantFromLong(Remote(l)))

  lazy val genInstantFromLongs: Gen[Random with Sized, Remote[Any]] =
    for {
      seconds <- Gen.long
      nanos   <- Gen.long
    } yield Remote.InstantFromLongs(Remote(seconds), Remote(nanos))

  lazy val genInstantFromMilli: Gen[Random with Sized, Remote[Any]] =
    Gen.long.map(l => Remote.InstantFromMilli(Remote(l)))

  lazy val genInstantFromString: Gen[Random with Sized, Remote[Any]] =
    Gen.instant.map(i => Remote.InstantFromString(Remote(i.toString)))

  lazy val genInstantToTuple: Gen[Random with Sized, Remote[Any]] =
    Gen.instant.map(i => Remote.InstantToTuple(Remote(i)))

  lazy val genInstantPlusDuration: Gen[Random with Sized, Remote[Any]] =
    for {
      instant  <- Gen.instant
      duration <- Gen.finiteDuration
    } yield Remote.InstantPlusDuration(Remote(instant), Remote(duration))

  lazy val genInstantMinusDuration: Gen[Random with Sized, Remote[Any]] =
    for {
      instant  <- Gen.instant
      duration <- Gen.finiteDuration
    } yield Remote.InstantMinusDuration(Remote(instant), Remote(duration))

  lazy val genSmallChronoUnit: Gen[Random, ChronoUnit] = Gen.oneOf(
    Seq(
      ChronoUnit.NANOS,
      ChronoUnit.MICROS,
      ChronoUnit.MILLIS,
      ChronoUnit.SECONDS,
      ChronoUnit.MINUTES,
      ChronoUnit.HOURS
    ).map(Gen.const(_)): _*
  )
  lazy val genChronoUnit: Gen[Random, ChronoUnit] = Gen.oneOf(ChronoUnit.values().map(Gen.const(_)): _*)

  lazy val genInstantTruncate: Gen[Random with Sized, Remote[Any]] =
    for {
      instant    <- Gen.instant
      chronoUnit <- genChronoUnit
    } yield Remote.InstantTruncate(Remote(instant), Remote(chronoUnit))

  lazy val genDurationFromString: Gen[Random, Remote[Any]] =
    for {
      duration <- Gen.finiteDuration
    } yield Remote.DurationFromString(Remote(duration.toString))

  lazy val genDurationBetweenInstants: Gen[Random, Remote[Any]] =
    for {
      start <- Gen.instant.map(Remote(_))
      end   <- Gen.instant.map(Remote(_))
    } yield Remote.DurationBetweenInstants(start, end)

  lazy val genDurationFromBigDecimal: Gen[Random, Remote[Any]] =
    for {
      seconds <- Gen.bigDecimal(0, BigDecimal(1000000000))
    } yield Remote.DurationFromBigDecimal(Remote(seconds.bigDecimal))

  lazy val genDurationFromLong: Gen[Random, Remote[Any]] =
    for {
      seconds <- Gen.long
    } yield Remote.DurationFromLong(Remote(seconds))

  lazy val genDurationFromLongs: Gen[Random, Remote[Any]] =
    for {
      seconds        <- Gen.long
      nanoAdjustment <- Gen.long
    } yield Remote.DurationFromLongs(Remote(seconds), Remote(nanoAdjustment))

  lazy val genDurationFromAmount: Gen[Random, Remote[Any]] =
    for {
      amount <- Gen.long
      unit   <- genChronoUnit
    } yield Remote.DurationFromAmount(Remote(amount), Remote(unit))

  lazy val genDurationToLongs: Gen[Random, Remote[Any]] =
    Gen.finiteDuration.map(duration => Remote.DurationToLongs(Remote(duration)))

  lazy val genDurationToLong: Gen[Random, Remote[Any]] =
    Gen.finiteDuration.map(duration => Remote.DurationToLong(Remote(duration)))

  lazy val genDurationPlusDuration: Gen[Random, Remote[Any]] =
    for {
      a              <- Gen.finiteDuration
      seconds        <- Gen.long
      nanoAdjustment <- Gen.long
    } yield Remote.DurationPlusDuration(Remote(a), Remote.DurationFromLongs(Remote(seconds), Remote(nanoAdjustment)))

  lazy val genDurationMinusDuration: Gen[Random, Remote[Any]] =
    for {
      a <- Gen.finiteDuration
      b <- Gen.finiteDuration
    } yield Remote.DurationMinusDuration(Remote(a), Remote(b))

  lazy val genIterate: Gen[Random, Remote[Any]] =
    for {
      initial <- Gen.int.map(Remote(_))
      delta   <- Gen.int
      iterate  = RemoteFunction((a: Remote[Int]) => Remote.AddNumeric(a, Remote(delta), Numeric.NumericInt))
      limit   <- Gen.int
      predicate = RemoteFunction((a: Remote[Int]) =>
                    Remote.Equal(
                      a,
                      Remote.AddNumeric(
                        initial,
                        Remote.MulNumeric(Remote(delta), Remote(limit), Numeric.NumericInt),
                        Numeric.NumericInt
                      )
                    )
                  )
    } yield Remote.Iterate(initial, iterate.evaluated, predicate.evaluated)

  lazy val genSome0: Gen[Random with Sized, Remote[Any]] =
    for {
      value <- genLiteral
    } yield Remote.Some0(value)

  lazy val genFoldOption: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- Gen.int.map(Remote(_))
      b <- genLiteral
    } yield Remote.FoldOption(Remote.Some0(a), b, Remote.RemoteFunction((_: Remote[Int]) => b).evaluated)

  lazy val genZipOption: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- Gen.oneOf(genLiteral.map(Remote.Some0(_)), Gen.const(Remote(None)))
      b <- Gen.oneOf(genLiteral.map(Remote.Some0(_)), Gen.const(Remote(None)))
    } yield Remote.ZipOption(a, b)

  lazy val genOptionContains: Gen[Random with Sized, Remote[Any]] =
    for {
      a <- Gen.string.map(Remote(_))
      b <- Gen.string.map(Remote(_))
    } yield Remote.OptionContains(Remote.Some0(a), b)

  lazy val genZFlowReturn: Gen[Random with Sized, ZFlow.Return[Any]] =
    Gen
      .oneOf(
        genLiteral,
        genSome0,
        genRemoteApply
      )
      .map(ZFlow.Return(_))

  lazy val genZFlowFail: Gen[Random with Sized, ZFlow.Fail[Any]] =
    Gen
      .oneOf(
        genLiteral,
        genSome0,
        genRemoteApply
      )
      .map(ZFlow.Fail(_))

  lazy val genZFlowNow: Gen[Any, ZFlow.Now.type] = Gen.const(ZFlow.Now)

  lazy val genZFlowWaitTill: Gen[Random, ZFlow.WaitTill] =
    Gen.instant.map(Remote(_)).map(ZFlow.WaitTill(_))

  lazy val genZFlowModify: Gen[Random with Sized, ZFlow.Modify[Int, String]] =
    for {
      varName <- genRemoteVariableName
      svar     = Remote.Variable(varName, Schema[Int])
      f = Remote.RemoteFunction((a: Remote[Int]) =>
            Remote.Tuple2(Remote("done"), Remote.AddNumeric(a, Remote(1), Numeric.NumericInt))
          )
    } yield ZFlow.Modify(svar, f.evaluated)(SchemaOrNothing.fromSchema[String])

  lazy val genZFlowFold: Gen[Random with Sized, ZFlow.Fold[Any, Nothing, Nothing, Instant, Any]] =
    for {
      flow                  <- genZFlowNow
      successSchemaAndValue <- genPrimitiveSchemaAndValue
      ifSuccess =
        RemoteFunction[Instant, ZFlow[Any, Nothing, Any]]((_: Remote[Instant]) =>
          ZFlow.Return(Remote.Literal(successSchemaAndValue)).asInstanceOf[ZFlow[Any, Nothing, Any]].toRemote
        ).evaluated
      ifError =
        RemoteFunction[Nothing, ZFlow[Any, Nothing, Any]]((_: Remote[Nothing]) =>
          ZFlow.Return(Remote.Literal(successSchemaAndValue)).asInstanceOf[ZFlow[Any, Nothing, Any]].toRemote
        ).evaluated
    } yield ZFlow.Fold[Any, Nothing, Nothing, Instant, Any](flow, ifError, ifSuccess)(
      SchemaOrNothing.nothing,
      SchemaOrNothing.fromSchema(successSchemaAndValue.schema.asInstanceOf[Schema[Any]])
    )

  lazy val genZFlowApply: Gen[Random with Sized, ZFlow[Any, Any, Any]] =
    for {
      resultFlow <- Gen.oneOf(genZFlowFold, genZFlowModify, genZFlowReturn)
    } yield ZFlow
      .Apply[Int, Nothing, Any](RemoteFunction((_: Remote[Int]) => resultFlow.toRemote).evaluated)(
        SchemaOrNothing.nothing,
        resultFlow.resultSchema.asInstanceOf[SchemaOrNothing.Aux[Any]]
      )
      .asInstanceOf[ZFlow[Any, Any, Any]]

  lazy val genZFlowLog: Gen[Random with Sized, ZFlow.Log] =
    Gen.string.map(ZFlow.Log(_))

  lazy val genZFlowRunActivity: Gen[Random with Sized, ZFlow.RunActivity[Any, Any]] =
    for {
      input    <- genLiteral
      activity <- genActivity
    } yield ZFlow.RunActivity(input, activity)

  lazy val genOperationHttp: Gen[Random with Sized, Operation.Http[Any, Any]] =
    for {
      url          <- Gen.oneOf(Gen.const("http://test.com/x"), Gen.const("https://100.0.0.1?test")).map(new java.net.URI(_))
      method       <- Gen.oneOf(Gen.const("GET"), Gen.const("POST"))
      headers      <- Gen.mapOf(Gen.string1(Gen.asciiChar), Gen.string)
      inputSchema  <- genPrimitiveSchemaAndValue.map(_.schema)
      outputSchema <- genPrimitiveSchemaAndValue.map(_.schema)
    } yield Operation.Http(
      url,
      method,
      headers,
      inputSchema.asInstanceOf[Schema[Any]],
      outputSchema.asInstanceOf[Schema[Any]]
    )

  lazy val genOperationSendEmail: Gen[Random with Sized, Operation.SendEmail] =
    for {
      server <- Gen.string1(Gen.asciiChar)
      port   <- Gen.int
    } yield Operation.SendEmail(server, port)

  lazy val genOperation: Gen[Random with Sized, Operation[Any, Any]] =
    Gen.oneOf(genOperationHttp, genOperationSendEmail.map(_.asInstanceOf[Operation[Any, Any]]))

  lazy val genActivity: Gen[Random with Sized, Activity[Any, Any]] =
    for {
      name        <- Gen.string1(Gen.unicodeChar)
      description <- Gen.string
      operation   <- genOperation
      check        = ZFlow.Fail(Remote(ActivityError("test", None)))
      compensate   = ZFlow.Fail(Remote(ActivityError("test", None)))
      resultSchema = operation.resultSchema
    } yield Activity(
      name,
      description,
      operation,
      check,
      compensate,
      SchemaOrNothing.fromSchema(resultSchema.asInstanceOf[Schema[Any]])
    )
}

object Generators {
  final class TestException(message: String) extends RuntimeException(message) {
    // Weak equality test for roundtrip exception serialization
    override def equals(obj: Any): Boolean =
      obj match {
        case t: Throwable => t.getMessage == message
        case _            => false
      }
  }
}
