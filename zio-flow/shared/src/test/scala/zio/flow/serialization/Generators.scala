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

package zio.flow.serialization

import zio.flow.Remote.UnboundRemoteFunction
import zio.flow.internal.{DurablePromise, RemoteVariableScope, ScopedRemoteVariableName}
import zio.flow.remote.numeric.{
  BinaryFractionalOperator,
  BinaryIntegralOperator,
  BinaryNumericOperator,
  Fractional,
  FractionalPredicateOperator,
  Integral,
  Numeric,
  NumericPredicateOperator,
  UnaryFractionalOperator,
  UnaryIntegralOperator,
  UnaryNumericOperator
}
import zio.flow.{
  Activity,
  ActivityError,
  BindingName,
  ExecutingFlow,
  FlowId,
  Instant,
  Operation,
  PromiseId,
  RecursionId,
  Remote,
  RemoteVariableName,
  RemoteVariableReference,
  TransactionId,
  ZFlow
}
import zio.schema.{DefaultJavaTimeSchemas, DynamicValue, Schema}
import zio.test.{Gen, Sized}
import zio.{Duration, ZNothing, flow}

import java.time.temporal.ChronoUnit
import zio.flow.operation.http
import zio.flow.remote.boolean.{BinaryBooleanOperator, UnaryBooleanOperator}
import zio.flow.remote.text.{CharConversion, CharToCodeConversion}
import zio.flow.remote.{BinaryOperators, RemoteConversions, UnaryOperators}

trait Generators extends DefaultJavaTimeSchemas {

  lazy val genDynamicValue: Gen[Sized, DynamicValue] =
    Gen.oneOf(
      Gen.string.map(value => DynamicValue.fromSchemaAndValue(Schema.primitive[String], value)),
      Gen.int.map(value => DynamicValue.fromSchemaAndValue(Schema.primitive[Int], value)),
      Gen.double.map(value => DynamicValue.fromSchemaAndValue(Schema.primitive[Double], value)),
      Gen.instant.map(value => DynamicValue.fromSchemaAndValue(instantSchema, value))
    )

  lazy val genRemoteVariableName: Gen[Sized, RemoteVariableName] =
    Gen.string1(Gen.alphaNumericChar).map(RemoteVariableName.unsafeMake)

  lazy val genFlowId: Gen[Sized, FlowId] =
    Gen.alphaNumericStringBounded(1, 16).map(FlowId.unsafeMake)

  lazy val genTransactionId: Gen[Sized, TransactionId] =
    Gen.alphaNumericStringBounded(1, 16).map(TransactionId.unsafeMake)

  lazy val genScope: Gen[Sized, RemoteVariableScope] =
    Gen.suspend {
      Gen.oneOf(
        genFlowId.map(RemoteVariableScope.TopLevel),
        (genFlowId <*> genScope).map { case (id, scope) => RemoteVariableScope.Fiber(id, scope) },
        (genTransactionId <*> genScope).map { case (id, scope) => RemoteVariableScope.Transactional(scope, id) }
      )
    }

  lazy val genScopedRemoteVariableName: Gen[Sized, ScopedRemoteVariableName] =
    (genRemoteVariableName <*> genScope).map { case (name, scope) => ScopedRemoteVariableName(name, scope) }

  lazy val genBindingName: Gen[Sized, BindingName] =
    Gen.uuid.map(BindingName.apply(_))

  lazy val genNumeric: Gen[Any, (Numeric[Any], Gen[Any, Remote[Any]])] =
    Gen.oneOf(
      Gen
        .const(Numeric.NumericShort)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.short.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Short], value)))
          )
        ),
      Gen
        .const(Numeric.NumericInt)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.int.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Int], value)))
          )
        ),
      Gen
        .const(Numeric.NumericLong)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.long.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Long], value)))
          )
        ),
      Gen
        .const(Numeric.NumericFloat)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.float.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Float], value)))
          )
        ),
      Gen
        .const(Numeric.NumericDouble)
        .map(n =>
          (
            n.asInstanceOf[Numeric[Any]],
            Gen.double.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Double], value)))
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
                  .Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[java.math.BigInteger], value.bigInteger))
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
                  .Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[java.math.BigDecimal], value.bigDecimal))
              )
          )
        )
    )

  lazy val genIntegral: Gen[Any, (Integral[Any], Gen[Any, Remote[Any]])] =
    Gen.oneOf(
      Gen
        .const(Numeric.NumericShort)
        .map(n =>
          (
            n.asInstanceOf[Integral[Any]],
            Gen.short.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Short], value)))
          )
        ),
      Gen
        .const(Numeric.NumericInt)
        .map(n =>
          (
            n.asInstanceOf[Integral[Any]],
            Gen.int.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Int], value)))
          )
        ),
      Gen
        .const(Numeric.NumericLong)
        .map(n =>
          (
            n.asInstanceOf[Integral[Any]],
            Gen.long.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Long], value)))
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
            Gen.float.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Float], value)))
          )
        ),
      Gen
        .const(Fractional.FractionalDouble)
        .map(n =>
          (
            n.asInstanceOf[Fractional[Any]],
            Gen.double.map(value => Remote.Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[Double], value)))
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
                  .Literal(DynamicValue.fromSchemaAndValue(Schema.primitive[java.math.BigDecimal], value.bigDecimal))
              )
          )
        )
    )

  lazy val genThrowableDynamicValue: Gen[Sized, DynamicValue] =
    Gen.alphaNumericString.map(msg =>
      DynamicValue.fromSchemaAndValue(
        zio.flow.schemaThrowable,
        new Generators.TestException(msg)
      )
    )

  lazy val genLiteral: Gen[Sized, Remote[Any]] =
    Gen.oneOf(genDynamicValue).map(Remote.Literal(_))

  lazy val genFail: Gen[Sized, Remote[Any]] =
    Gen.alphaNumericString.map(Remote.Fail(_))

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

  lazy val genVariableReference: Gen[Sized, Remote[Any]] =
    for {
      name <- genRemoteVariableName
    } yield Remote.VariableReference(RemoteVariableReference(name))

  lazy val genRemoteVariable: Gen[Sized, Remote[Any]] =
    for {
      name <- genRemoteVariableName
    } yield Remote.Variable(name)

  lazy val genUnbound: Gen[Sized, Remote[Any]] =
    for {
      name <- genBindingName
    } yield Remote.Unbound(name)

  lazy val genUnaryNumericOperator: Gen[Any, UnaryNumericOperator] =
    Gen.oneOf(
      Gen.const(UnaryNumericOperator.Neg),
      Gen.const(UnaryNumericOperator.Abs),
      Gen.const(UnaryNumericOperator.Sign)
    )

  lazy val genUnaryIntegralOperator: Gen[Any, UnaryIntegralOperator] =
    Gen.oneOf(
      Gen.const(UnaryIntegralOperator.NegExact),
      Gen.const(UnaryIntegralOperator.DecExact),
      Gen.const(UnaryIntegralOperator.IncExact),
      Gen.const(UnaryIntegralOperator.BitwiseNeg)
    )

  lazy val genNumericPredicate: Gen[Any, NumericPredicateOperator] =
    Gen.oneOf(
      Gen.const(NumericPredicateOperator.IsWhole),
      Gen.const(NumericPredicateOperator.IsValidInt),
      Gen.const(NumericPredicateOperator.IsValidByte),
      Gen.const(NumericPredicateOperator.IsValidChar),
      Gen.const(NumericPredicateOperator.IsValidLong),
      Gen.const(NumericPredicateOperator.IsValidShort)
    )

  lazy val genUnaryFractionalOperator: Gen[Any, UnaryFractionalOperator] =
    Gen.oneOf(
      Gen.const(UnaryFractionalOperator.Sin),
      Gen.const(UnaryFractionalOperator.ArcSin),
      Gen.const(UnaryFractionalOperator.Tan),
      Gen.const(UnaryFractionalOperator.ArcTan),
      Gen.const(UnaryFractionalOperator.Cos),
      Gen.const(UnaryFractionalOperator.ArcCos),
      Gen.const(UnaryFractionalOperator.Ceil),
      Gen.const(UnaryFractionalOperator.Floor),
      Gen.const(UnaryFractionalOperator.Round),
      Gen.const(UnaryFractionalOperator.ToRadians),
      Gen.const(UnaryFractionalOperator.ToDegrees),
      Gen.const(UnaryFractionalOperator.Rint),
      Gen.const(UnaryFractionalOperator.NextUp),
      Gen.const(UnaryFractionalOperator.NextDown),
      Gen.const(UnaryFractionalOperator.Sqrt),
      Gen.const(UnaryFractionalOperator.Cbrt),
      Gen.const(UnaryFractionalOperator.Exp),
      Gen.const(UnaryFractionalOperator.Expm1),
      Gen.const(UnaryFractionalOperator.Log),
      Gen.const(UnaryFractionalOperator.Log10),
      Gen.const(UnaryFractionalOperator.Log1p),
      Gen.const(UnaryFractionalOperator.Sinh),
      Gen.const(UnaryFractionalOperator.Cosh),
      Gen.const(UnaryFractionalOperator.Tanh),
      Gen.const(UnaryFractionalOperator.Ulp)
    )

  lazy val genFractionalPredicate: Gen[Any, FractionalPredicateOperator] =
    Gen.oneOf(
      Gen.const(FractionalPredicateOperator.IsNaN),
      Gen.const(FractionalPredicateOperator.IsFinite),
      Gen.const(FractionalPredicateOperator.IsInfinity),
      Gen.const(FractionalPredicateOperator.IsNegInifinty),
      Gen.const(FractionalPredicateOperator.IsPosInfinity)
    )

  lazy val genUnaryBooleanOperator: Gen[Any, UnaryBooleanOperator] =
    Gen.oneOf(
      Gen.const(UnaryBooleanOperator.Not)
    )

  lazy val genBinaryNumericOperator: Gen[Any, BinaryNumericOperator] =
    Gen.oneOf(
      Gen.const(BinaryNumericOperator.Add),
      Gen.const(BinaryNumericOperator.Sub),
      Gen.const(BinaryNumericOperator.Mul),
      Gen.const(BinaryNumericOperator.Div),
      Gen.const(BinaryNumericOperator.Mod),
      Gen.const(BinaryNumericOperator.Min),
      Gen.const(BinaryNumericOperator.Max)
    )

  lazy val genBinaryIntegralOperator: Gen[Any, BinaryIntegralOperator] =
    Gen.oneOf(
      Gen.const(BinaryIntegralOperator.And),
      Gen.const(BinaryIntegralOperator.Or),
      Gen.const(BinaryIntegralOperator.Xor),
      Gen.const(BinaryIntegralOperator.AddExact),
      Gen.const(BinaryIntegralOperator.FloorDiv),
      Gen.const(BinaryIntegralOperator.FloorMod),
      Gen.const(BinaryIntegralOperator.LeftShift),
      Gen.const(BinaryIntegralOperator.RightShift),
      Gen.const(BinaryIntegralOperator.UnsignedRightShift),
      Gen.const(BinaryIntegralOperator.AddExact),
      Gen.const(BinaryIntegralOperator.SubExact),
      Gen.const(BinaryIntegralOperator.MulExact)
    )

  lazy val genBinaryFractionalOperator: Gen[Any, BinaryFractionalOperator] =
    Gen.oneOf(
      Gen.const(BinaryFractionalOperator.Pow),
      Gen.const(BinaryFractionalOperator.Scalb),
      Gen.const(BinaryFractionalOperator.Hypot),
      Gen.const(BinaryFractionalOperator.ArcTan2),
      Gen.const(BinaryFractionalOperator.CopySign),
      Gen.const(BinaryFractionalOperator.IEEEremainder),
      Gen.const(BinaryFractionalOperator.NextAfter)
    )

  lazy val genCharToCodeConversion: Gen[Any, CharToCodeConversion] =
    Gen.oneOf(
      Gen.const(CharToCodeConversion.GetType),
      Gen.const(CharToCodeConversion.AsDigit),
      Gen.const(CharToCodeConversion.GetNumericValue),
      Gen.const(CharToCodeConversion.GetDirectionality)
    )

  lazy val genCharConversion: Gen[Any, CharConversion] =
    Gen.oneOf(
      Gen.const(CharConversion.ToUpper),
      Gen.const(CharConversion.ToLower),
      Gen.const(CharConversion.ToTitleCase),
      Gen.const(CharConversion.ReverseBytes)
    )

  lazy val genBinaryBooleanOperator: Gen[Any, BinaryBooleanOperator] =
    Gen.oneOf(
      Gen.const(BinaryBooleanOperator.And),
      Gen.const(BinaryBooleanOperator.Or),
      Gen.const(BinaryBooleanOperator.Xor)
    )

  lazy val genRemoteConversions: Gen[Sized, (RemoteConversions[Any, Any], Gen[Sized, Remote[Any]])] =
    Gen.oneOf(
      for {
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (RemoteConversions.NumericToInt(numeric).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (RemoteConversions.NumericToLong(numeric).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (RemoteConversions.NumericToFloat(numeric).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (RemoteConversions.NumericToShort(numeric).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (RemoteConversions.NumericToDouble(numeric).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair          <- genIntegral
        (numeric, gen) = pair
      } yield (RemoteConversions.NumericToOctalString(numeric).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair          <- genIntegral
        (numeric, gen) = pair
      } yield (RemoteConversions.NumericToHexString(numeric).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair          <- genIntegral
        (numeric, gen) = pair
      } yield (RemoteConversions.NumericToBinaryString(numeric).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (RemoteConversions.ToString()(numeric.schema).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        pair             <- genFractional
        (fractional, gen) = pair
      } yield (RemoteConversions.FractionalGetExponent(fractional).asInstanceOf[RemoteConversions[Any, Any]], gen),
      for {
        op <- genCharToCodeConversion
      } yield (
        RemoteConversions.CharToCode(op).asInstanceOf[RemoteConversions[Any, Any]],
        Gen.char.map(Remote.apply[Char])
      ),
      for {
        op <- genCharConversion
      } yield (
        RemoteConversions.CharToChar(op).asInstanceOf[RemoteConversions[Any, Any]],
        Gen.char.map(Remote.apply[Char])
      ),
      for {
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (
        RemoteConversions.StringToNumeric(numeric).asInstanceOf[RemoteConversions[Any, Any]],
        gen.map(_.toString(numeric.schema))
      ),
      Gen.const(
        (RemoteConversions.StringToDuration.asInstanceOf[RemoteConversions[Any, Any]], Gen.string.map(Remote(_)))
      ),
      Gen.const(
        (
          RemoteConversions.BigDecimalToDuration.asInstanceOf[RemoteConversions[Any, Any]],
          Gen.bigDecimal(BigDecimal(0), BigDecimal(Long.MaxValue)).map(Remote(_))
        )
      ),
      Gen.const(
        (RemoteConversions.DurationToTuple.asInstanceOf[RemoteConversions[Any, Any]], Gen.finiteDuration.map(Remote(_)))
      ),
      Gen.const(
        (RemoteConversions.InstantToTuple.asInstanceOf[RemoteConversions[Any, Any]], Gen.instant.map(Remote(_)))
      ),
      Gen.const(
        (
          RemoteConversions.TupleToInstant.asInstanceOf[RemoteConversions[Any, Any]],
          Gen.long.zip(Gen.int).map(Remote(_))
        )
      ),
      Gen.const(
        (RemoteConversions.StringToInstant.asInstanceOf[RemoteConversions[Any, Any]], Gen.string.map(Remote(_)))
      )
    )

  lazy val genUnaryOperators: Gen[Sized, (UnaryOperators[Any, Any], Gen[Sized, Remote[Any]])] =
    Gen.oneOf(
      for {
        op            <- genUnaryNumericOperator
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (UnaryOperators.Numeric(op, numeric).asInstanceOf[UnaryOperators[Any, Any]], gen),
      for {
        op             <- genUnaryIntegralOperator
        pair           <- genIntegral
        (integral, gen) = pair
      } yield (UnaryOperators.Integral(op, integral).asInstanceOf[UnaryOperators[Any, Any]], gen),
      for {
        op               <- genUnaryFractionalOperator
        pair             <- genFractional
        (fractional, gen) = pair
      } yield (UnaryOperators.Fractional(op, fractional).asInstanceOf[UnaryOperators[Any, Any]], gen),
      for {
        op            <- genNumericPredicate
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (UnaryOperators.NumericPredicate(op, numeric).asInstanceOf[UnaryOperators[Any, Any]], gen),
      for {
        op               <- genFractionalPredicate
        pair             <- genFractional
        (fractional, gen) = pair
      } yield (UnaryOperators.FractionalPredicate(op, fractional).asInstanceOf[UnaryOperators[Any, Any]], gen),
      for {
        pair             <- genRemoteConversions
        (conversion, gen) = pair
      } yield (UnaryOperators.Conversion(conversion).asInstanceOf[UnaryOperators[Any, Any]], gen),
      for {
        op <- genUnaryBooleanOperator
      } yield (UnaryOperators.Bool(op).asInstanceOf[UnaryOperators[Any, Any]], Gen.boolean.map(Remote(_)))
    )

  lazy val genBinaryOperators: Gen[Sized, (BinaryOperators[Any, Any], Gen[Sized, Remote[Any]])] =
    Gen.oneOf(
      for {
        op            <- genBinaryNumericOperator
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (BinaryOperators.Numeric(op, numeric).asInstanceOf[BinaryOperators[Any, Any]], gen),
      for {
        op               <- genBinaryFractionalOperator
        pair             <- genFractional
        (fractional, gen) = pair
      } yield (BinaryOperators.Fractional(op, fractional).asInstanceOf[BinaryOperators[Any, Any]], gen),
      for {
        op             <- genBinaryIntegralOperator
        pair           <- genIntegral
        (integral, gen) = pair
      } yield (BinaryOperators.Integral(op, integral).asInstanceOf[BinaryOperators[Any, Any]], gen),
      for {
        pair          <- genNumeric
        (numeric, gen) = pair
      } yield (BinaryOperators.LessThanEqual(numeric.schema).asInstanceOf[BinaryOperators[Any, Any]], gen),
      for {
        op <- genBinaryBooleanOperator
      } yield (BinaryOperators.Bool(op).asInstanceOf[BinaryOperators[Any, Any]], Gen.boolean.map(Remote(_)))
    )

  lazy val genUnary: Gen[Sized, Remote[Any]] =
    for {
      pair           <- genUnaryOperators
      (operator, gen) = pair
      value          <- gen
    } yield Remote.Unary(value, operator)

  lazy val genBinary: Gen[Sized, Remote[Any]] =
    for {
      pair           <- genBinaryOperators
      (operator, gen) = pair
      left           <- gen
      right          <- gen
    } yield Remote.Binary(left, right, operator)

  lazy val genUnboundRemoteFunction: Gen[Sized, Remote[Any]] =
    for {
      v <- genUnbound
      r <- Gen.oneOf(
             genLiteral,
             genRemoteVariable,
             genBinary
           )
    } yield Remote.UnboundRemoteFunction(v.asInstanceOf[Remote.Unbound[Any]], r)

  lazy val genEvaluateUnboundRemoteFunction: Gen[Sized, Remote[Any]] =
    for {
      f <- genUnboundRemoteFunction
      a <- genLiteral
    } yield Remote.EvaluateUnboundRemoteFunction(f.asInstanceOf[Remote.UnboundRemoteFunction[Any, Any]], a)

  lazy val genRemoteEither: Gen[Sized, Remote[Any]] =
    for {
      left  <- genDynamicValue
      right <- genDynamicValue
      either <- Gen.oneOf(
                  Gen.const(Left(Remote.Literal(left))),
                  Gen.const(Right(Remote.Literal(right)))
                )
    } yield Remote.RemoteEither(either)

  lazy val genFoldEither: Gen[Sized, Remote[Any]] =
    for {
      left  <- genDynamicValue
      right <- genDynamicValue
      either <- Gen.oneOf(
                  Gen.const(Left(Remote.Literal(left))),
                  Gen.const(Right(Remote.Literal(right)))
                )
      // TODO: generate functions compatible with the generated either
      leftF  <- genUnboundRemoteFunction.map(_.asInstanceOf[Remote.UnboundRemoteFunction[Any, Either[Any, Any]]])
      rightF <- genUnboundRemoteFunction.map(_.asInstanceOf[Remote.UnboundRemoteFunction[Any, Either[Any, Any]]])
    } yield Remote.FoldEither(Remote.RemoteEither(either), leftF, rightF)

  lazy val genTry: Gen[Sized, Remote[Any]] =
    for {
      value <- genDynamicValue
      error <- genThrowableDynamicValue
      either <- Gen.either(
                  Gen.const(
                    Remote.Literal(error)
                  ),
                  Gen.const(Remote.Literal(value))
                )
    } yield Remote.Try(either)

  lazy val genTuple2: Gen[Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genBinary
    } yield Remote.Tuple2(a, b)

  lazy val genTuple3: Gen[Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genBinary
      c <- genRemoteVariable
    } yield Remote.Tuple3(a, b, c)

  lazy val genTuple4: Gen[Sized, Remote[Any]] =
    for {
      a <- genLiteral
      b <- genUnboundRemoteFunction
      c <- genBinary
      d <- genRemoteEither
    } yield Remote.Tuple4(a, b, c, d)

  lazy val genTupleAccess: Gen[Sized, Remote[Any]] =
    for {
      tuple <- genTuple4
      n     <- Gen.int(0, 3)
    } yield Remote.TupleAccess[(Any, Any, Any, Any), Any](tuple.asInstanceOf[Remote[(Any, Any, Any, Any)]], n, 4)

  lazy val genStringToCharList: Gen[Sized, Remote[Any]] =
    for {
      s <- Gen.string.map(Remote(_))
    } yield Remote.StringToCharList(s)

  lazy val genCharListToString: Gen[Sized, Remote[Any]] =
    for {
      s <- Gen.string.map(_.toList).map(Remote(_))
    } yield Remote.CharListToString(s)

  lazy val genBranch: Gen[Sized, Remote[Any]] =
    for {
      condition <- Gen.boolean.map(Remote(_))
      ifTrue    <- Gen.int.map(Remote(_))
      ifFalse <-
        Gen.int.map(n =>
          Remote.Binary(Remote(10), Remote(n), BinaryOperators.Numeric(BinaryNumericOperator.Mul, Numeric.NumericInt))
        )
    } yield Remote.Branch(condition, ifTrue, ifFalse)

  lazy val genEqual: Gen[Sized, Remote[Any]] =
    for {
      lv  <- Gen.string
      rv  <- Gen.string
      lLit = Remote(lv)
      rLit = Remote(rv)
    } yield Remote.Equal(lLit, rLit)

  lazy val genFold: Gen[Sized, Remote[Any]] =
    for {
      list    <- Gen.listOf(Gen.double).map(Remote(_))
      initial <- Gen.double(-1000.0, 1000.0).map(Remote(_))
      fun = Remote.UnboundRemoteFunction.make((tuple: Remote[(Double, Double)]) =>
              Remote.Binary(
                Remote.TupleAccess(tuple, 0, 2),
                Remote.TupleAccess(tuple, 1, 2),
                BinaryOperators.Numeric(
                  BinaryNumericOperator.Add,
                  Numeric.NumericDouble
                )
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

  lazy val genDurationFromAmount: Gen[Any, Remote[Any]] =
    for {
      amount <- Gen.long
      unit   <- genChronoUnit
    } yield Remote.DurationFromAmount(Remote(amount), Remote(unit))

  lazy val genRemoteSome: Gen[Sized, Remote[Any]] =
    for {
      value <- genLiteral
    } yield Remote.RemoteSome(value)

  lazy val genFoldOption: Gen[Sized, Remote[Any]] =
    for {
      a <- Gen.int.map(Remote(_))
      b <- genLiteral
    } yield Remote.FoldOption(Remote.RemoteSome(a), b, Remote.UnboundRemoteFunction.make((_: Remote[Int]) => b))

  lazy val genRecurse: Gen[Sized, Remote[Any]] =
    for {
      id      <- Gen.uuid.map(RecursionId(_))
      initial <- genLiteral
      body    <- genUnboundRemoteFunction
    } yield Remote.Recurse(id, initial, body.asInstanceOf[UnboundRemoteFunction[Any, Any]])

  lazy val genRecurseWith: Gen[Sized, Remote[Any]] =
    for {
      id    <- Gen.uuid.map(RecursionId(_))
      value <- genLiteral
    } yield Remote.RecurseWith(id, value)

  lazy val genListToSet: Gen[Sized, Remote[Any]] =
    for {
      lst       <- Gen.listOf(Gen.alphaNumericString)
      remoteList = Remote(lst)
    } yield Remote.ListToSet(remoteList)

  lazy val genSetToList: Gen[Sized, Remote[Any]] =
    for {
      set       <- Gen.setOf(Gen.alphaNumericString)
      remoteList = Remote(set)
    } yield Remote.SetToList(remoteList)

  lazy val genListToString: Gen[Sized, Remote[Any]] =
    for {
      list  <- Gen.listOf(Gen.int)
      start <- Gen.string
      sep   <- Gen.string
      end   <- Gen.string
    } yield Remote.ListToString(Remote(list).map(_.toString), Remote(start), Remote(sep), Remote(end))

  lazy val genZFlowReturn: Gen[Sized, ZFlow.Return[Any]] =
    Gen
      .oneOf(
        genLiteral,
        genRemoteSome,
        genEvaluateUnboundRemoteFunction
      )
      .map(ZFlow.Return(_))

  lazy val genZFlowFail: Gen[Sized, ZFlow.Fail[Any]] =
    Gen
      .oneOf(
        genLiteral,
        genRemoteSome,
        genEvaluateUnboundRemoteFunction
      )
      .map(ZFlow.Fail(_))

  lazy val genZFlowNow: Gen[Any, ZFlow.Now.type] = Gen.const(ZFlow.Now)

  lazy val genZFlowWaitTill: Gen[Any, ZFlow.WaitTill] =
    Gen.instant.map(Remote(_)).map(ZFlow.WaitTill(_))

  lazy val genZFlowRead: Gen[Sized, ZFlow.Read[String]] =
    for {
      varName <- genRemoteVariableName
      svar     = RemoteVariableReference[String](varName)
    } yield ZFlow.Read(Remote(svar))

  lazy val genZFlowModify: Gen[Sized, ZFlow.Modify[Int, String]] =
    for {
      varName <- genRemoteVariableName
      svar     = RemoteVariableReference[Int](varName)
      f = Remote.UnboundRemoteFunction.make((a: Remote[Int]) =>
            Remote.Tuple2(
              Remote("done"),
              Remote.Binary(a, Remote(1), BinaryOperators.Numeric(BinaryNumericOperator.Add, Numeric.NumericInt))
            )
          )
    } yield ZFlow.Modify(Remote(svar), f)

  lazy val genZFlowFold: Gen[Sized, ZFlow.Fold[Any, Nothing, ZNothing, Instant, Any]] =
    for {
      flow         <- genZFlowNow
      successValue <- genDynamicValue
      ifSuccess =
        UnboundRemoteFunction.make[Instant, ZFlow[Any, ZNothing, Any]]((_: Remote[Instant]) =>
          ZFlow.Return(Remote.Literal(successValue)).asInstanceOf[ZFlow[Any, ZNothing, Any]].toRemote
        )
      ifError =
        UnboundRemoteFunction.make[Nothing, ZFlow[Any, ZNothing, Any]]((_: Remote[Nothing]) =>
          ZFlow.Return(Remote.Literal(successValue)).asInstanceOf[ZFlow[Any, ZNothing, Any]].toRemote
        )
    } yield ZFlow.Fold[Any, Nothing, ZNothing, Instant, Any](flow, ifError, ifSuccess)

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
      input <- Gen.const(ZFlow.Input())
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
      .Unwrap(remoteFlow)
      .asInstanceOf[ZFlow.Unwrap[Any, Any, Any]]

  lazy val genZFlowUnwrapRemote: Gen[Sized, ZFlow.UnwrapRemote[Any]] =
    for {
      dynamicValue <- genDynamicValue
      nested        = Remote.Nested(Remote.Literal(dynamicValue))
    } yield ZFlow.UnwrapRemote(nested)

  lazy val genZFlowFork: Gen[Sized, ZFlow.Fork[Any, Any, Any]] =
    for {
      flow <- Gen.int.map(value => ZFlow.Return(Remote(value)).asInstanceOf[ZFlow[Any, Any, Any]])
    } yield ZFlow
      .Fork(flow)
      .asInstanceOf[ZFlow.Fork[Any, Any, Any]]

  lazy val genZFlowTimeout: Gen[Sized, ZFlow.Timeout[Any, Any, Any]] =
    for {
      flow     <- Gen.int.map(value => ZFlow.Return(Remote(value)).asInstanceOf[ZFlow[Any, Any, Any]])
      duration <- genDurationFromAmount
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
    } yield ZFlow.Await[String, Int](executingFlow)

  lazy val genZFlowInterrupt: Gen[Sized, ZFlow.Interrupt[String, Int]] =
    for {
      executingFlow <- genExecutingFlow[String, Int]
    } yield ZFlow.Interrupt[String, Int](executingFlow)

  lazy val genZFlowNewVar: Gen[Sized, ZFlow.NewVar[Any]] =
    for {
      name    <- Gen.string1(Gen.alphaNumericChar)
      initial <- Gen.oneOf(genLiteral, genBinary, genEvaluateUnboundRemoteFunction)
    } yield ZFlow.NewVar(name, initial)

  lazy val genZFlowIterate: Gen[Any, ZFlow.Iterate[Any, Nothing, Int]] =
    for {
      initial <- Gen.int.map(Remote(_))
      delta   <- Gen.int
      iterate =
        UnboundRemoteFunction.make((a: Remote[Int]) =>
          Remote.Flow(
            ZFlow.Return(
              Remote.Binary(a, Remote(delta), BinaryOperators.Numeric(BinaryNumericOperator.Add, Numeric.NumericInt))
            )
          )
        )
      limit <- Gen.int
      predicate = UnboundRemoteFunction.make((a: Remote[Int]) =>
                    Remote.Equal(
                      a,
                      Remote.Binary(
                        initial,
                        Remote
                          .Binary(
                            Remote(delta),
                            Remote(limit),
                            BinaryOperators.Numeric(BinaryNumericOperator.Mul, Numeric.NumericInt)
                          ),
                        BinaryOperators.Numeric(BinaryNumericOperator.Add, Numeric.NumericInt)
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
      id        <- genFlowId
      promiseId <- Gen.string1(Gen.asciiChar)
      promise    = DurablePromise[E, A](PromiseId(promiseId))
    } yield ExecutingFlow(id, promise)
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
