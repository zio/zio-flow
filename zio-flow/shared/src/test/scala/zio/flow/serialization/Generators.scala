package zio.flow.serialization

import zio.flow.Remote.EvaluatedRemoteFunction
import zio.flow.internal.DurablePromise
import zio.flow.remote.numeric.{
  BinaryNumericOperator,
  Fractional,
  Numeric,
  UnaryFractionalOperator,
  UnaryNumericOperator
}
import zio.flow.{
  Activity,
  ActivityError,
  BindingName,
  ExecutingFlow,
  FlowId,
  Operation,
  Remote,
  RemoteVariableName,
  RemoteVariableReference,
  SchemaAndValue,
  ZFlow,
  schemaZNothing
}
import zio.schema.{DefaultJavaTimeSchemas, Schema}
import zio.test.{Gen, Sized}
import zio.{Duration, ZNothing, flow}

import java.time.Instant
import java.time.temporal.ChronoUnit
import zio.flow.operation.http

trait Generators extends DefaultJavaTimeSchemas {

  lazy val genPrimitiveSchemaAndValue: Gen[Sized, SchemaAndValue[Any]] =
    Gen.oneOf(
      Gen.string.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[String], value)),
      Gen.int.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[Int], value)),
      Gen.double.map(value => SchemaAndValue.fromSchemaAndValue(Schema.primitive[Double], value)),
      Gen.instant.map(value => SchemaAndValue.fromSchemaAndValue(instantSchema, value))
    )

  lazy val genRemoteVariableName: Gen[Sized, flow.RemoteVariableName.Type] =
    Gen.string1(Gen.alphaNumericChar).map(RemoteVariableName.apply(_))

  lazy val genBindingName: Gen[Sized, flow.BindingName.Type] =
    Gen.uuid.map(BindingName.apply(_))

  lazy val genNumeric: Gen[Any, (Numeric[Any], Gen[Any, Remote[Any]])] =
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

  lazy val genFractional: Gen[Any, (Fractional[Any], Gen[Any, Remote[Any]])] =
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

  lazy val genThrowableSchemaAndValue: Gen[Sized, SchemaAndValue[Throwable]] =
    Gen.alphaNumericString.map(msg =>
      SchemaAndValue.fromSchemaAndValue(
        zio.flow.schemaThrowable,
        new Generators.TestException(msg)
      )
    )

  lazy val genLiteral: Gen[Sized, Remote[Any]] =
    Gen.oneOf(genPrimitiveSchemaAndValue).map(Remote.Literal(_))

  lazy val genRemoteFlow: Gen[Sized, Remote[Any]] =
    Gen
      .oneOf(
        genZFlowReturn,
        genZFlowNow,
        genZFlowWaitTill
      )
      .map(Remote.Flow(_))

  lazy val genNested: Gen[Sized, Remote[Any]] =
    Gen.oneOf(genLiteral, genRemoteVariable).map(Remote.Nested(_))

  lazy val genRemoteVariable: Gen[Sized, Remote[Any]] =
    for {
      name           <- genRemoteVariableName
      schemaAndValue <- genPrimitiveSchemaAndValue
    } yield Remote.Variable(name, schemaAndValue.schema)

  lazy val genUnbound: Gen[Sized, Remote[Any]] =
    for {
      name           <- genBindingName
      schemaAndValue <- genPrimitiveSchemaAndValue
    } yield Remote.Unbound(name, schemaAndValue.schema)

  lazy val genUnaryOperator: Gen[Any, UnaryNumericOperator] =
    Gen.oneOf(
      Gen.const(UnaryNumericOperator.Neg),
      Gen.const(UnaryNumericOperator.Abs),
      Gen.const(UnaryNumericOperator.Floor),
      Gen.const(UnaryNumericOperator.Ceil),
      Gen.const(UnaryNumericOperator.Round)
    )

  lazy val genBinaryOperator: Gen[Any, BinaryNumericOperator] =
    Gen.oneOf(
      Gen.const(BinaryNumericOperator.Add),
      Gen.const(BinaryNumericOperator.Mul),
      Gen.const(BinaryNumericOperator.Div),
      Gen.const(BinaryNumericOperator.Mod),
      Gen.const(BinaryNumericOperator.Pow),
      Gen.const(BinaryNumericOperator.Root),
      Gen.const(BinaryNumericOperator.Log),
      Gen.const(BinaryNumericOperator.Min),
      Gen.const(BinaryNumericOperator.Max)
    )

  lazy val genUnaryFractionalOperator: Gen[Any, UnaryFractionalOperator] =
    Gen.oneOf(
      Gen.const(UnaryFractionalOperator.Sin),
      Gen.const(UnaryFractionalOperator.ArcSin),
      Gen.const(UnaryFractionalOperator.ArcTan)
    )

  lazy val genBinaryNumeric: Gen[Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      left          <- gen
      right         <- gen
      operator      <- genBinaryOperator
    } yield Remote.BinaryNumeric(left, right, numeric, operator)

  lazy val genUnaryNumeric: Gen[Sized, Remote[Any]] =
    for {
      pair          <- genNumeric
      (numeric, gen) = pair
      value         <- gen
      operator      <- genUnaryOperator
    } yield Remote.UnaryNumeric(value, numeric, operator)

  lazy val genUnaryFractional: Gen[Sized, Remote[Any]] =
    for {
      pair             <- genFractional
      (fractional, gen) = pair
      value            <- gen
      operator         <- genUnaryFractionalOperator
    } yield Remote.UnaryFractional(value, fractional, operator)

  lazy val genEvaluatedRemoteFunction: Gen[Sized, Remote[Any]] =
    for {
      v <- genUnbound
      r <- Gen.oneOf(genLiteral, genRemoteVariable, genBinaryNumeric)
    } yield Remote.EvaluatedRemoteFunction(v.asInstanceOf[Remote.Unbound[Any]], r)

  lazy val genEvaluatedRemoteFunctionWithResultSchema: Gen[Sized, (Remote[Any], Schema[Any])] =
    for {
      v <- genUnbound
      (r, s) <- Gen.oneOf(
                  genLiteral.map(l => (l, l.asInstanceOf[Remote.Literal[Any]].schemaA)),
                  genRemoteVariable.map(l => (l, l.asInstanceOf[Remote.Variable[Any]].schemaA)),
                  genBinaryNumeric.map(l => (l, l.asInstanceOf[Remote.BinaryNumeric[Any]].numeric.schema))
                )
    } yield (Remote.EvaluatedRemoteFunction(v.asInstanceOf[Remote.Unbound[Any]], r), s)

  lazy val genRemoteApply: Gen[Sized, Remote[Any]] =
    for {
      f <- genEvaluatedRemoteFunction
      a <- genLiteral
    } yield Remote.ApplyEvaluatedFunction(f.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Any]], a)

  lazy val genRemoteEither: Gen[Sized, Remote[Any]] =
    for {
      left  <- genPrimitiveSchemaAndValue
      right <- genPrimitiveSchemaAndValue
      either <- Gen.oneOf(
                  Gen.const(Left((left.toRemote, right.schema.asInstanceOf[Schema[Any]]))),
                  Gen.const(Right((left.schema.asInstanceOf[Schema[Any]], right.toRemote)))
                )
    } yield Remote.RemoteEither(either)

  lazy val genFoldEither: Gen[Sized, Remote[Any]] =
    for {
      left  <- genPrimitiveSchemaAndValue
      right <- genPrimitiveSchemaAndValue
      either <- Gen.oneOf(
                  Gen.const(Left((left.toRemote, right.schema.asInstanceOf[Schema[Any]]))),
                  Gen.const(Right((left.schema.asInstanceOf[Schema[Any]], right.toRemote)))
                )
      // TODO: generate functions compatible with the generated either
      leftF  <- genEvaluatedRemoteFunction.map(_.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Either[Any, Any]]])
      rightF <- genEvaluatedRemoteFunction.map(_.asInstanceOf[Remote.EvaluatedRemoteFunction[Any, Either[Any, Any]]])
    } yield Remote.FoldEither(Remote.RemoteEither(either), leftF, rightF)

  lazy val genSwapEither: Gen[Sized, Remote[Any]] =
    genRemoteEither.map(r => Remote.SwapEither(r.asInstanceOf[Remote[Either[Any, Any]]]))

  lazy val genTry: Gen[Sized, Remote[Any]] =
    for {
      value <- genPrimitiveSchemaAndValue
      error <- genThrowableSchemaAndValue
      either <- Gen.either(
                  Gen.const(
                    (
                      error.toRemote,
                      value.schema.asInstanceOf[Schema[Any]]
                    )
                  ),
                  Gen.const(value.toRemote)
                )
    } yield Remote.Try(either)

  lazy val genTuple2: Gen[Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genBinaryNumeric
    } yield Remote.Tuple2(a, b)

  lazy val genTuple3: Gen[Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genBinaryNumeric
      c <- genRemoteVariable
    } yield Remote.Tuple3(a, b, c)

  lazy val genTuple4: Gen[Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genEvaluatedRemoteFunction
      c <- genBinaryNumeric
      d <- genRemoteEither
    } yield Remote.Tuple4(a, b, c, d)

  lazy val genTupleAccess: Gen[Sized, Remote[Any]] =
    for {
      tuple <- genTuple4
      n     <- Gen.int(0, 3)
    } yield Remote.TupleAccess[(Any, Any, Any, Any), Any](tuple.asInstanceOf[Remote[(Any, Any, Any, Any)]], n)

  lazy val genBranch: Gen[Sized, Remote[Any]] =
    for {
      condition <- Gen.boolean.map(Remote(_))
      ifTrue    <- Gen.int.map(Remote(_))
      ifFalse <-
        Gen.int.map(n => Remote.BinaryNumeric(Remote(10), Remote(n), Numeric.NumericInt, BinaryNumericOperator.Mul))
    } yield Remote.Branch(condition, ifTrue, ifFalse)

  lazy val genLength: Gen[Sized, Remote[Any]] =
    Gen.string.map(Remote(_)).map(Remote.Length(_))

  lazy val genLessThanEqual: Gen[Sized, Remote[Any]] =
    for {
      lv  <- Gen.int
      rv  <- Gen.int
      lLit = Remote(lv)
      rLit = Remote(rv)
    } yield Remote.LessThanEqual(lLit, rLit)

  lazy val genEqual: Gen[Sized, Remote[Any]] =
    for {
      lv  <- Gen.string
      rv  <- Gen.string
      lLit = Remote(lv)
      rLit = Remote(rv)
    } yield Remote.Equal(lLit, rLit)

  lazy val genNot: Gen[Sized, Remote[Any]] =
    for {
      value <- Gen.boolean.map(Remote(_))
    } yield Remote.Not(value)

  lazy val genAnd: Gen[Sized, Remote[Any]] =
    for {
      left <- Gen.boolean.map(Remote(_))
      right = Remote.EvaluatedRemoteFunction.make((b: Remote[Boolean]) => Remote.Not(b))
    } yield Remote.And(left, right)

  lazy val genFold: Gen[Sized, Remote[Any]] =
    for {
      list    <- Gen.listOf(Gen.double).map(Remote(_))
      initial <- Gen.double(-1000.0, 1000.0).map(Remote(_))
      fun = Remote.EvaluatedRemoteFunction.make((tuple: Remote[(Double, Double)]) =>
              Remote.BinaryNumeric(
                Remote.TupleAccess(tuple, 0),
                Remote.TupleAccess(tuple, 1),
                Numeric.NumericDouble,
                BinaryNumericOperator.Add
              )
            )
    } yield Remote.Fold(list, initial, fun)

  lazy val genCons: Gen[Sized, Remote[Any]] =
    for {
      list <- Gen.listOf(Gen.int).map(Remote(_))
      head <- Gen.int.map(Remote(_))
    } yield Remote.Cons(list, head)

  lazy val genUnCons: Gen[Sized, Remote[Any]] =
    for {
      list <- Gen.listOf(Gen.int zip Gen.string).map(Remote(_))
    } yield Remote.UnCons(list)

  lazy val genInstantFromLongs: Gen[Sized, Remote[Any]] =
    for {
      seconds <- Gen.long
      nanos   <- Gen.long
    } yield Remote.InstantFromLongs(Remote(seconds), Remote(nanos))

  lazy val genInstantFromString: Gen[Sized, Remote[Any]] =
    Gen.instant.map(i => Remote.InstantFromString(Remote(i.toString)))

  lazy val genInstantToTuple: Gen[Sized, Remote[Any]] =
    Gen.instant.map(i => Remote.InstantToTuple(Remote(i)))

  lazy val genInstantPlusDuration: Gen[Sized, Remote[Any]] =
    for {
      instant  <- Gen.instant
      duration <- Gen.finiteDuration
    } yield Remote.InstantPlusDuration(Remote(instant), Remote(duration))

  lazy val genSmallChronoUnit: Gen[Any, ChronoUnit] = Gen.oneOf(
    Seq(
      ChronoUnit.NANOS,
      ChronoUnit.MICROS,
      ChronoUnit.MILLIS,
      ChronoUnit.SECONDS,
      ChronoUnit.MINUTES,
      ChronoUnit.HOURS
    ).map(Gen.const(_)): _*
  )
  lazy val genChronoUnit: Gen[Any, ChronoUnit] = Gen.oneOf(ChronoUnit.values().toList.map(Gen.const(_)): _*)

  lazy val genInstantTruncate: Gen[Sized, Remote[Any]] =
    for {
      instant    <- Gen.instant
      chronoUnit <- genChronoUnit
    } yield Remote.InstantTruncate(Remote(instant), Remote(chronoUnit))

  lazy val genDurationFromString: Gen[Any, Remote[Any]] =
    for {
      duration <- Gen.finiteDuration
    } yield Remote.DurationFromString(Remote(duration.toString))

  lazy val genDurationBetweenInstants: Gen[Any, Remote[Any]] =
    for {
      start <- Gen.instant.map(Remote(_))
      end   <- Gen.instant.map(Remote(_))
    } yield Remote.DurationBetweenInstants(start, end)

  lazy val genDurationFromBigDecimal: Gen[Any, Remote[Any]] =
    for {
      seconds <- Gen.bigDecimal(0, BigDecimal(1000000000))
    } yield Remote.DurationFromBigDecimal(Remote(seconds.bigDecimal))

  lazy val genDurationFromLongs: Gen[Any, Remote[Any]] =
    for {
      seconds        <- Gen.long
      nanoAdjustment <- Gen.long
    } yield Remote.DurationFromLongs(Remote(seconds), Remote(nanoAdjustment))

  lazy val genDurationFromAmount: Gen[Any, Remote[Any]] =
    for {
      amount <- Gen.long
      unit   <- genChronoUnit
    } yield Remote.DurationFromAmount(Remote(amount), Remote(unit))

  lazy val genDurationToLongs: Gen[Any, Remote[Any]] =
    Gen.finiteDuration.map(duration => Remote.DurationToLongs(Remote(duration)))

  lazy val genDurationPlusDuration: Gen[Any, Remote[Any]] =
    for {
      a              <- Gen.finiteDuration
      seconds        <- Gen.long
      nanoAdjustment <- Gen.long
    } yield Remote.DurationPlusDuration(Remote(a), Remote.DurationFromLongs(Remote(seconds), Remote(nanoAdjustment)))

  lazy val genDurationMultipledBy: Gen[Any, Remote[Any]] =
    for {
      a <- Gen.finiteDuration
      b <- Gen.long
    } yield Remote.DurationMultipliedBy(Remote(a), Remote(b))

  lazy val genIterate: Gen[Any, Remote[Any]] =
    for {
      initial <- Gen.int.map(Remote(_))
      delta   <- Gen.int
      iterate = EvaluatedRemoteFunction.make((a: Remote[Int]) =>
                  Remote.BinaryNumeric(a, Remote(delta), Numeric.NumericInt, BinaryNumericOperator.Add)
                )
      limit <- Gen.int
      predicate = EvaluatedRemoteFunction.make((a: Remote[Int]) =>
                    Remote.Equal(
                      a,
                      Remote.BinaryNumeric(
                        initial,
                        Remote
                          .BinaryNumeric(Remote(delta), Remote(limit), Numeric.NumericInt, BinaryNumericOperator.Mul),
                        Numeric.NumericInt,
                        BinaryNumericOperator.Add
                      )
                    )
                  )
    } yield Remote.Iterate(initial, iterate, predicate)

  lazy val genRemoteSome: Gen[Sized, Remote[Any]] =
    for {
      value <- genLiteral
    } yield Remote.RemoteSome(value)

  lazy val genFoldOption: Gen[Sized, Remote[Any]] =
    for {
      a <- Gen.int.map(Remote(_))
      b <- genLiteral
    } yield Remote.FoldOption(Remote.RemoteSome(a), b, Remote.EvaluatedRemoteFunction.make((_: Remote[Int]) => b))

  lazy val genZFlowReturn: Gen[Sized, ZFlow.Return[Any]] =
    Gen
      .oneOf(
        genLiteral,
        genRemoteSome,
        genRemoteApply
      )
      .map(ZFlow.Return(_))

  lazy val genZFlowFail: Gen[Sized, ZFlow.Fail[Any]] =
    Gen
      .oneOf(
        genLiteral,
        genRemoteSome,
        genRemoteApply
      )
      .map(ZFlow.Fail(_))

  lazy val genZFlowNow: Gen[Any, ZFlow.Now.type] = Gen.const(ZFlow.Now)

  lazy val genZFlowWaitTill: Gen[Any, ZFlow.WaitTill] =
    Gen.instant.map(Remote(_)).map(ZFlow.WaitTill(_))

  lazy val genZFlowRead: Gen[Sized, ZFlow.Read[String]] =
    for {
      varName <- genRemoteVariableName
      svar     = RemoteVariableReference[String](varName)
    } yield ZFlow.Read(Remote(svar), Schema[String])

  lazy val genZFlowModify: Gen[Sized, ZFlow.Modify[Int, String]] =
    for {
      varName <- genRemoteVariableName
      svar     = RemoteVariableReference[Int](varName)
      f = Remote.EvaluatedRemoteFunction.make((a: Remote[Int]) =>
            Remote.Tuple2(
              Remote("done"),
              Remote.BinaryNumeric(a, Remote(1), Numeric.NumericInt, BinaryNumericOperator.Add)
            )
          )
    } yield ZFlow.Modify(Remote(svar), f)

  lazy val genZFlowFold: Gen[Sized, ZFlow.Fold[Any, Nothing, ZNothing, Instant, Any]] =
    for {
      flow                  <- genZFlowNow
      successSchemaAndValue <- genPrimitiveSchemaAndValue
      ifSuccess =
        EvaluatedRemoteFunction.make[Instant, ZFlow[Any, ZNothing, Any]]((_: Remote[Instant]) =>
          ZFlow.Return(Remote.Literal(successSchemaAndValue)).asInstanceOf[ZFlow[Any, ZNothing, Any]].toRemote
        )
      ifError =
        EvaluatedRemoteFunction.make[Nothing, ZFlow[Any, ZNothing, Any]]((_: Remote[Nothing]) =>
          ZFlow.Return(Remote.Literal(successSchemaAndValue)).asInstanceOf[ZFlow[Any, ZNothing, Any]].toRemote
        )
    } yield ZFlow.Fold[Any, Nothing, ZNothing, Instant, Any](flow, ifError, ifSuccess)(
      zio.flow.schemaZNothing,
      successSchemaAndValue.schema.asInstanceOf[Schema[Any]]
    )

  lazy val genZFlowLog: Gen[Sized, ZFlow.Log] =
    Gen.string.map(ZFlow.Log(_))

  lazy val genZFlowRunActivity: Gen[Sized, ZFlow.RunActivity[Any, Any]] =
    for {
      input    <- genLiteral
      activity <- genActivity
    } yield ZFlow.RunActivity(input, activity)

  lazy val genZFlowTransaction: Gen[Sized, ZFlow.Transaction[Any, Any, Any]] =
    Gen.oneOf(genZFlowFail, genZFlowReturn, genZFlowLog, genZFlowFold).map(ZFlow.Transaction(_))

  lazy val genZFlowInput: Gen[Sized, ZFlow.Input[Any]] =
    for {
      input <- Gen.oneOf(
                 genPrimitiveSchemaAndValue.map(schemaAndValue => ZFlow.Input()(schemaAndValue.schema)),
                 Gen.const(ZFlow.Input()(ZFlow.schemaAny)),
                 Gen.const(ZFlow.Input()(Remote.schemaAny))
               )
    } yield input.asInstanceOf[ZFlow.Input[Any]]

  lazy val genZFlowEnsuring: Gen[Sized, ZFlow.Ensuring[Any, Any, Any]] =
    for {
      flow    <- Gen.oneOf(genZFlowFail, genZFlowReturn, genZFlowLog, genZFlowFold, genZFlowTransaction, genZFlowInput)
      ensuring = ZFlow.log("done")
    } yield ZFlow.Ensuring(flow, ensuring)

  lazy val genZFlowUnwrap: Gen[Sized, ZFlow.Unwrap[Any, Any, Any]] =
    for {
      flow      <- Gen.int.map(value => ZFlow.Return(Remote(value)).asInstanceOf[ZFlow[Any, Any, Any]])
      remoteFlow = Remote.Flow(flow)
    } yield ZFlow
      .Unwrap(remoteFlow)(
        schemaZNothing.asInstanceOf[Schema[Any]],
        Schema[Int].asInstanceOf[Schema[Any]]
      )
      .asInstanceOf[ZFlow.Unwrap[Any, Any, Any]]

  lazy val genZFlowUnwrapRemote: Gen[Sized, ZFlow.UnwrapRemote[Any]] =
    for {
      schemaAndValue <- genPrimitiveSchemaAndValue
      nested          = Remote.Nested(schemaAndValue.toRemote)
    } yield ZFlow.UnwrapRemote(nested)(schemaAndValue.schema.asInstanceOf[Schema[Any]])

  lazy val genZFlowFork: Gen[Sized, ZFlow.Fork[Any, Any, Any]] =
    for {
      flow <- Gen.int.map(value => ZFlow.Return(Remote(value)).asInstanceOf[ZFlow[Any, Any, Any]])
    } yield ZFlow
      .Fork(flow)
      .asInstanceOf[ZFlow.Fork[Any, Any, Any]]

  lazy val genZFlowTimeout: Gen[Sized, ZFlow.Timeout[Any, Any, Any]] =
    for {
      flow     <- Gen.int.map(value => ZFlow.Return(Remote(value)).asInstanceOf[ZFlow[Any, Any, Any]])
      duration <- genDurationFromLongs
    } yield ZFlow
      .Timeout(flow, duration.asInstanceOf[Remote[Duration]])
      .asInstanceOf[ZFlow.Timeout[Any, Any, Any]]

  lazy val genZFlowProvide: Gen[Sized, ZFlow.Provide[String, Nothing, String]] =
    for {
      string      <- Gen.string
      remoteString = Remote(string)
      input        = ZFlow.Input[String]()
    } yield ZFlow.Provide(remoteString, input)

  lazy val genZFlowDie: Gen[Any, ZFlow.Die.type]               = Gen.const(ZFlow.Die)
  lazy val genZFlowRetryUntil: Gen[Any, ZFlow.RetryUntil.type] = Gen.const(ZFlow.RetryUntil)

  lazy val genZFlowOrTry: Gen[Sized, ZFlow.OrTry[Any, Any, Any]] =
    for {
      left  <- Gen.oneOf(genZFlowFail, genZFlowReturn, genZFlowLog, genZFlowFold)
      right <- Gen.oneOf(genZFlowFail, genZFlowReturn, genZFlowLog, genZFlowFold)
    } yield ZFlow.OrTry(left, right)

  lazy val genZFlowAwait: Gen[Sized, ZFlow.Await[String, Int]] =
    for {
      executingFlow <- genExecutingFlow[String, Int]
    } yield ZFlow.Await[String, Int](executingFlow)(Schema[String], Schema[Int])

  lazy val genZFlowInterrupt: Gen[Sized, ZFlow.Interrupt[String, Int]] =
    for {
      executingFlow <- genExecutingFlow[String, Int]
    } yield ZFlow.Interrupt[String, Int](executingFlow)

  lazy val genZFlowNewVar: Gen[Sized, ZFlow.NewVar[Any]] =
    for {
      name    <- Gen.string1(Gen.alphaNumericChar)
      initial <- Gen.oneOf(genLiteral, genBinaryNumeric, genRemoteApply)
    } yield ZFlow.NewVar(name, initial)

  lazy val genZFlowIterate: Gen[Any, ZFlow.Iterate[Any, Nothing, Int]] =
    for {
      initial <- Gen.int.map(Remote(_))
      delta   <- Gen.int
      iterate = EvaluatedRemoteFunction.make((a: Remote[Int]) =>
                  Remote.Flow(
                    ZFlow.Return(Remote.BinaryNumeric(a, Remote(delta), Numeric.NumericInt, BinaryNumericOperator.Add))
                  )
                )
      limit <- Gen.int
      predicate = EvaluatedRemoteFunction.make((a: Remote[Int]) =>
                    Remote.Equal(
                      a,
                      Remote.BinaryNumeric(
                        initial,
                        Remote
                          .BinaryNumeric(Remote(delta), Remote(limit), Numeric.NumericInt, BinaryNumericOperator.Mul),
                        Numeric.NumericInt,
                        BinaryNumericOperator.Add
                      )
                    )
                  )
    } yield ZFlow.Iterate[Any, Nothing, Int](initial, iterate, predicate)

  lazy val genHttpApiPath: Gen[Sized, http.Path[Any]] =
    Gen.oneOf(
      Gen.const(http.string.asInstanceOf[http.Path[Any]]),
      Gen.const(http.int.asInstanceOf[http.Path[Any]]),
      Gen.const(http.boolean.asInstanceOf[http.Path[Any]]),
      Gen.const(http.uuid.asInstanceOf[http.Path[Any]]),
      Gen.string.map(s => http.stringToPath(s).asInstanceOf[http.Path[Any]])
    )

  lazy val genHttpApiQuery: Gen[Sized, http.Query[Any]] =
    Gen.string.flatMap { paramName =>
      Gen.oneOf(
        Gen.const(http.string(paramName).asInstanceOf[http.Query[Any]]),
        Gen.const(http.int(paramName).asInstanceOf[http.Query[Any]]),
        Gen.const(http.boolean(paramName).asInstanceOf[http.Query[Any]])
      )
    }

  lazy val genHttpApiHeader: Gen[Sized, http.Header[Any]] =
    Gen.string.flatMap { paramName =>
      Gen.oneOf(
        Gen.const(http.Header.string(paramName).asInstanceOf[http.Header[Any]])
      )
    }

  lazy val genOperationHttp: Gen[Sized, Operation.Http[Any, Any]] =
    for {
      url    <- Gen.oneOf(Gen.const("http://test.com/x"), Gen.const("https://100.0.0.1?test"))
      path   <- genHttpApiPath
      query  <- genHttpApiQuery
      header <- genHttpApiHeader
      api     = flow.operation.http.API.post(path).query(query).header(header).input[Int].output[Double]
    } yield Operation.Http(
      url,
      api.asInstanceOf[http.API[Any, Any]]
    )

  lazy val genOperation: Gen[Sized, Operation[Any, Any]] =
    Gen.oneOf(genOperationHttp).map(_.asInstanceOf[Operation[Any, Any]])

  lazy val genActivity: Gen[Sized, Activity[Any, Any]] =
    for {
      name        <- Gen.string1(Gen.unicodeChar)
      description <- Gen.string
      operation   <- genOperation
      check        = ZFlow.Fail(Remote(ActivityError("test", None)))
      compensate   = ZFlow.Fail(Remote(ActivityError("test", None)))
    } yield Activity(
      name,
      description,
      operation,
      check,
      compensate
    )

  def genExecutingFlow[E, A]: Gen[Sized, ExecutingFlow[E, A]] =
    for {
      id        <- Gen.string1(Gen.asciiChar)
      promiseId <- Gen.string1(Gen.asciiChar)
      promise    = DurablePromise[E, A](promiseId)
    } yield ExecutingFlow(FlowId(id), promise)
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
