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

package zio.flow

import zio.flow.remote.{BinaryOperators, DynamicValueHelpers, RemoteConversions, UnaryOperators}
import zio.flow.remote.RemoteTuples._
import zio.flow.serialization.FlowSchemaAst
import zio.schema.{CaseSet, DynamicValue, Schema}
import zio.{Chunk, ZIO}

import java.math.BigDecimal
import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import scala.collection.immutable.ListMap
import scala.language.implicitConversions

/**
 * A `Remote[A]` is a blueprint for constructing a value of type `A` on a remote
 * machine. Remote values can always be serialized, because they are mere
 * blueprints, and they do not contain any Scala code.
 */
sealed trait Remote[+A] { self =>

  def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue]
  def eval[A1 >: A](implicit schema: Schema[A1]): ZIO[LocalContext with RemoteContext, RemoteEvaluationError, A1] =
    evalDynamic.flatMap(dyn => ZIO.fromEither(dyn.toTypedValue(schema)).mapError(RemoteEvaluationError.TypeError))

  final def iterate[A1 >: A: Schema](
    step: Remote[A1] => Remote[A1]
  )(predicate: Remote[A1] => Remote[Boolean]): Remote[A1] =
    predicate(self).ifThenElse(
      step(self).iterate(step)(predicate),
      self
    )

  final def toFlow: ZFlow[Any, Nothing, A] = ZFlow(self)

  final def widen[B](implicit ev: A <:< B): Remote[B] = {
    val _ = ev

    self.asInstanceOf[Remote[B]]
  }

  final def unit: Remote[Unit] = Remote.Ignore()

  private[flow] def variableUsage: VariableUsage

  final def substitute[B](f: Remote.Substitutions): Remote[A] =
    if (f.cut(variableUsage)) this
    else
      f.matches(this) match {
        case Some(value) => value.substitute(f).asInstanceOf[Remote[A]]
        case None        => substituteRec(f)
      }

  protected def substituteRec[B](f: Remote.Substitutions): Remote[A]

  def toString[A1 >: A: Schema]: Remote[String] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.ToString[A1]()))
}

object Remote {
//
//  /**
//   * Constructs accessors that can be used modify remote versions of user
//   * defined data types.
//   */
//  def makeAccessors[A](implicit
//    schema: Schema[A]
//  ): schema.schema.Accessors[RemoteLens, RemotePrism, RemoteTraversal] =
//    schema.schema.makeAccessors(RemoteAccessorBuilder)

  final case class Literal[A](value: DynamicValue) extends Remote[A] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.succeed(value)

    override def eval[A1 >: A](implicit
      schemaA1: Schema[A1]
    ): ZIO[LocalContext with RemoteContext, RemoteEvaluationError, A1] =
      ZIO.fromEither(value.toTypedValue(schemaA1)).mapError(RemoteEvaluationError.TypeError)

    override def equals(that: Any): Boolean =
      that match {
        case Literal(otherValue) =>
          value == otherValue
        case _ => false
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[A] = this

    override private[flow] val variableUsage = VariableUsage.none
  }

  object Literal {
    def schema[A]: Schema[Literal[A]] =
      Schema[DynamicValue].transform(Literal(_), _.value)

    def schemaCase[A]: Schema.Case[Literal[A], Remote[A]] =
      Schema.Case("Literal", schema[A], _.asInstanceOf[Literal[A]])
  }

  final case class Fail[A](message: String) extends Remote[A] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.fail(RemoteEvaluationError.RemoteFail(message))

    override private[flow] def variableUsage = VariableUsage.none

    override protected def substituteRec[B](f: Substitutions): Remote[A] = this
  }

  object Fail {
    def schema[A]: Schema[Fail[A]] =
      Schema[String].transform(Fail(_), _.message)

    def schemaCase[A]: Schema.Case[Fail[A], Remote[A]] =
      Schema.Case("Fail", schema[A], _.asInstanceOf[Fail[A]])
  }

  final case class Flow[R, E, A](flow: ZFlow[R, E, A]) extends Remote[ZFlow[R, E, A]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.succeed(DynamicValue.fromSchemaAndValue(ZFlow.schema[R, E, A], flow))

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[ZFlow[R, E, A]] =
      Flow(flow.substitute(f))

    override private[flow] val variableUsage = flow.variableUsage
  }

  object Flow {
    def schema[R, E, A]: Schema[Flow[R, E, A]] =
      Schema.defer(
        ZFlow
          .schema[R, E, A]
          .transform(
            Flow(_),
            _.flow
          )
      )

    def schemaCase[A]: Schema.Case[Flow[Any, Any, Any], Remote[A]] =
      Schema.Case("Flow", schema[Any, Any, Any], _.asInstanceOf[Flow[Any, Any, Any]])
  }

  final case class Nested[A](remote: Remote[A]) extends Remote[Remote[A]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.succeed(
        DynamicValue.fromSchemaAndValue(Remote.schema[A], remote)
      )

    override def eval[A1 >: Remote[A]](implicit
      schema: Schema[A1]
    ): ZIO[LocalContext with RemoteContext, RemoteEvaluationError, A1] =
      ZIO.succeed(remote)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Remote[A]] =
      Nested(remote.substitute(f))

    override private[flow] val variableUsage = remote.variableUsage
  }

  object Nested {
    def schema[A]: Schema[Nested[A]] =
      Schema.defer(Remote.schema[A].transform(Nested(_), _.remote))

    def schemaCase[A]: Schema.Case[Nested[Any], Remote[A]] =
      Schema.Case("Nested", schema[Any], _.asInstanceOf[Nested[Any]])
  }

  final case class VariableReference[A](ref: RemoteVariableReference[A]) extends Remote[RemoteVariableReference[A]] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.succeed(DynamicValue.fromSchemaAndValue(RemoteVariableReference.schema[A], ref))

    override private[flow] def variableUsage: VariableUsage =
      VariableUsage.variable(ref.name)

    override protected def substituteRec[B](f: Substitutions): Remote[RemoteVariableReference[A]] =
      this

    /**
     * Gets a [[Remote]] which represents the value stored in this remote
     * variable
     */
    def dereference: Remote.Variable[A] = ref.toRemote
  }

  object VariableReference {

    // NOTE: must be kept identifiable from DynamicValues in Remote.fromDynamic
    def schema[A]: Schema[VariableReference[A]] =
      Schema.CaseClass1[RemoteVariableReference[A], VariableReference[A]](
        Schema.Field("ref", Schema[RemoteVariableReference[A]]),
        VariableReference(_),
        _.ref
      )

    def schemaCase[A]: Schema.Case[VariableReference[Any], Remote[A]] =
      Schema.Case("VariableReference", schema[Any], _.asInstanceOf[VariableReference[Any]])
  }

  final case class Ignore() extends Remote[Unit] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.succeed(
        DynamicValue.fromSchemaAndValue(Schema.primitive[Unit], ())
      )

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Unit] =
      this

    override private[flow] val variableUsage = VariableUsage.none
  }
  object Ignore {
    val schema: Schema[Ignore] = Schema[Unit].transform(_ => Ignore(), _ => ())

    def schemaCase[A]: Schema.Case[Ignore, Remote[A]] =
      Schema.Case("Ignore", schema, _.asInstanceOf[Ignore])
  }

  final case class Variable[A](identifier: RemoteVariableName) extends Remote[A] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      RemoteContext
        .getVariable(identifier)
        .mapError(RemoteEvaluationError.RemoteContextError)
        .flatMap {
          case None        => ZIO.fail(RemoteEvaluationError.VariableNotFound(identifier))
          case Some(value) => ZIO.succeed(value)
        }

    override def equals(that: Any): Boolean =
      that match {
        case Variable(otherIdentifier) =>
          otherIdentifier == identifier
        case _ => false
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[A] =
      this

    override private[flow] val variableUsage = VariableUsage.variable(identifier)
  }

  object Variable {
    def schema[A]: Schema[Variable[A]] =
      Schema.CaseClass1[RemoteVariableName, Variable[A]](
        Schema.Field("identifier", Schema[RemoteVariableName]),
        construct = (identifier: RemoteVariableName) => Variable(identifier),
        extractField = (variable: Variable[A]) => variable.identifier
      )

    def schemaCase[A]: Schema.Case[Variable[A], Remote[A]] =
      Schema.Case("Variable", schema, _.asInstanceOf[Variable[A]])
  }

  final case class Unbound[A](identifier: BindingName) extends Remote[A] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      LocalContext.getBinding(identifier).flatMap {
        case Some(variable) => variable.evalDynamic
        case None           => ZIO.fail(RemoteEvaluationError.BindingNotFound(identifier))
      }

    override def equals(that: Any): Boolean =
      that match {
        case Unbound(otherIdentifier) =>
          otherIdentifier == identifier
        case _ => false
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[A] =
      this

    override private[flow] val variableUsage = VariableUsage.binding(identifier)
  }

  object Unbound {
    def schema[A]: Schema[Unbound[A]] =
      Schema.CaseClass1[BindingName, Unbound[A]](
        Schema.Field("identifier", Schema[BindingName]),
        construct = (identifier: BindingName) => Unbound(identifier),
        extractField = (variable: Unbound[A]) => variable.identifier
      )

    def schemaCase[A]: Schema.Case[Unbound[A], Remote[A]] =
      Schema.Case("Unbound", schema, _.asInstanceOf[Unbound[A]])
  }

  final case class UnboundRemoteFunction[A, B] private[flow] (
    input: Unbound[A],
    result: Remote[B]
  ) extends Remote[EvaluatedRemoteFunction[A, B]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      result.evalDynamic

    def apply(a: Remote[A]): Remote[B] =
      EvaluateUnboundRemoteFunction(this, a)

    override protected def substituteRec[C](f: Remote.Substitutions): Remote[EvaluatedRemoteFunction[A, B]] =
      UnboundRemoteFunction(input, result.substitute(f))

    override private[flow] val variableUsage = input.variableUsage.union(result.variableUsage)
  }

  object UnboundRemoteFunction {
    def make[A, B](fn: Remote[A] => Remote[B]): UnboundRemoteFunction[A, B] = {
      val input = Unbound[A](LocalContext.generateFreshBinding)
      UnboundRemoteFunction(
        input,
        fn(input)
      )
    }

    def schema[A, B]: Schema[UnboundRemoteFunction[A, B]] =
      Schema.CaseClass2[Unbound[A], Remote[B], UnboundRemoteFunction[A, B]](
        Schema.Field("variable", Unbound.schema[A]),
        Schema.Field("result", Schema.defer(Remote.schema[B])),
        UnboundRemoteFunction.apply(_, _),
        _.input,
        _.result
      )

    def schemaCase[A, B]: Schema.Case[UnboundRemoteFunction[A, B], Remote[B]] =
      Schema.Case("UnboundRemoteFunction", schema, _.asInstanceOf[UnboundRemoteFunction[A, B]])
  }

  type ===>[A, B] = UnboundRemoteFunction[A, B]

  final case class EvaluateUnboundRemoteFunction[A, B](f: UnboundRemoteFunction[A, B], a: Remote[A]) extends Remote[B] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        input       <- a.evalDynamic
        paramName    = RemoteContext.generateFreshVariableName
        variable     = Remote.Variable(paramName)
        _           <- RemoteContext.setVariable(paramName, input).mapError(RemoteEvaluationError.RemoteContextError)
        _           <- LocalContext.pushBinding(f.input.identifier, variable)
        evaluated   <- f.evalDynamic
        _           <- LocalContext.popBinding(f.input.identifier)
        resultRemote = Remote.fromDynamic(evaluated)
        finalResult <-
          if (resultRemote.variableUsage.bindings.contains(f.input.identifier)) {
            val substituted = resultRemote.substitute(Substitutions(Map(f.input -> variable)))
            substituted.evalDynamic
          } else ZIO.succeed(evaluated)
      } yield finalResult

    override protected def substituteRec[C](fn: Remote.Substitutions): Remote[B] =
      EvaluateUnboundRemoteFunction(
        f.substitute(fn).asInstanceOf[UnboundRemoteFunction[A, B]],
        a.substitute(fn)
      )

    override private[flow] val variableUsage = f.variableUsage.union(a.variableUsage).removeBinding(f.input.identifier)
  }

  object EvaluateUnboundRemoteFunction {
    def schema[A, B]: Schema[EvaluateUnboundRemoteFunction[A, B]] =
      Schema.CaseClass2[UnboundRemoteFunction[A, B], Remote[A], EvaluateUnboundRemoteFunction[A, B]](
        Schema.Field("f", UnboundRemoteFunction.schema[A, B]),
        Schema.Field("a", Schema.defer(Remote.schema[A])),
        EvaluateUnboundRemoteFunction.apply,
        _.f,
        _.a
      )

    def schemaCase[A, B]: Schema.Case[EvaluateUnboundRemoteFunction[A, B], Remote[B]] =
      Schema.Case[EvaluateUnboundRemoteFunction[A, B], Remote[B]](
        "EvaluateUnboundRemoteFunction",
        schema[A, B],
        _.asInstanceOf[EvaluateUnboundRemoteFunction[A, B]]
      )
  }

  final case class Unary[In, Out](
    value: Remote[In],
    operator: UnaryOperators[In, Out]
  ) extends Remote[Out] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        v <- value.eval(operator.inputSchema)
      } yield DynamicValue.fromSchemaAndValue(operator.outputSchema, operator(v))

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Out] =
      Unary(value.substitute(f), operator)

    override private[flow] val variableUsage = value.variableUsage
  }

  object Unary {
    def schema[In, Out]: Schema[Unary[In, Out]] =
      Schema.CaseClass2[Remote[In], UnaryOperators[In, Out], Unary[In, Out]](
        Schema.Field("value", Schema.defer(Remote.schema[In])),
        Schema.Field("operator", UnaryOperators.schema[In, Out]),
        Unary.apply,
        _.value,
        _.operator
      )

    def schemaCase[In, Out]: Schema.Case[Unary[In, Out], Remote[Out]] =
      Schema.Case("Unary", schema, _.asInstanceOf[Unary[In, Out]])
  }

  final case class Binary[In, Out](
    left: Remote[In],
    right: Remote[In],
    operator: BinaryOperators[In, Out]
  ) extends Remote[Out] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        l <- left.eval(operator.inputSchema)
        r <- right.eval(operator.inputSchema)
      } yield DynamicValue.fromSchemaAndValue(operator.outputSchema, operator(l, r))

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Out] =
      Binary(left.substitute(f), right.substitute(f), operator)

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object Binary {
    def schema[In, Out]: Schema[Binary[In, Out]] =
      Schema.CaseClass3[Remote[In], Remote[In], BinaryOperators[In, Out], Binary[In, Out]](
        Schema.Field("left", Schema.defer(Remote.schema[In])),
        Schema.Field("right", Schema.defer(Remote.schema[In])),
        Schema.Field("operator", BinaryOperators.schema[In, Out]),
        Binary.apply,
        _.left,
        _.right,
        _.operator
      )

    def schemaCase[In, Out]: Schema.Case[Binary[In, Out], Remote[Out]] =
      Schema.Case("Binary", schema, _.asInstanceOf[Binary[In, Out]])
  }

  final case class RemoteEither[A, B](
    either: Either[Remote[A], Remote[B]]
  ) extends Remote[Either[A, B]] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      either match {
        case Left(value) =>
          value.evalDynamic.map { leftValue =>
            DynamicValue.LeftValue(leftValue)
          }
        case Right(value) =>
          value.evalDynamic.map { rightValue =>
            DynamicValue.RightValue(rightValue)
          }
      }
    override def equals(that: Any): Boolean =
      that match {
        case RemoteEither(otherEither) =>
          (either, otherEither) match {
            case (Left(value), Left(otherValue)) =>
              value == otherValue
            case (Right(value), Right(otherValue)) =>
              value == otherValue
            case _ => false
          }
        case _ => false
      }

    override protected def substituteRec[C](f: Remote.Substitutions): Remote[Either[A, B]] =
      RemoteEither(either match {
        case Left(valueA)  => Left(valueA.substitute(f))
        case Right(valueB) => Right(valueB.substitute(f))
      })

    override private[flow] val variableUsage = either match {
      case Left(valueA)  => valueA.variableUsage
      case Right(valueB) => valueB.variableUsage
    }
  }

  object RemoteEither {
    def schema[A, B]: Schema[RemoteEither[A, B]] =
      Schema.defer {
        Schema
          .either(Remote.schema[A], Remote.schema[B])
          .transform(
            value => RemoteEither.apply(value),
            _.either
          )
      }

    def schemaCase[A]: Schema.Case[RemoteEither[Any, Any], Remote[A]] =
      Schema.Case("RemoteEither", schema[Any, Any], _.asInstanceOf[RemoteEither[Any, Any]])
  }

  final case class FoldEither[A, B, C](
    either: Remote[Either[A, B]],
    left: UnboundRemoteFunction[A, C],
    right: UnboundRemoteFunction[B, C]
  ) extends Remote[C] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      either.evalDynamic.flatMap {
        case DynamicValue.LeftValue(value) =>
          left(Remote.fromDynamic(value)).evalDynamic
        case DynamicValue.RightValue(value) =>
          right(Remote.fromDynamic(value)).evalDynamic
        case other: DynamicValue =>
          ZIO.fail(
            RemoteEvaluationError.UnexpectedDynamicValue(
              s"Unexpected value in Remote.FoldEither of type ${other.getClass.getSimpleName}"
            )
          )
      }

    override protected def substituteRec[D](f: Remote.Substitutions): Remote[C] =
      FoldEither(
        either.substitute(f),
        left.substitute(f).asInstanceOf[UnboundRemoteFunction[A, C]],
        right.substitute(f).asInstanceOf[UnboundRemoteFunction[B, C]]
      )

    override private[flow] val variableUsage = either.variableUsage.union(left.variableUsage).union(right.variableUsage)
  }

  object FoldEither {
    def schema[A, B, C]: Schema[FoldEither[A, B, C]] =
      Schema.CaseClass3[
        Remote[Either[A, B]],
        UnboundRemoteFunction[A, C],
        UnboundRemoteFunction[B, C],
        FoldEither[A, B, C]
      ](
        Schema.Field("either", Schema.defer(Remote.schema[Either[A, B]])),
        Schema.Field("left", UnboundRemoteFunction.schema[A, C]),
        Schema.Field("right", UnboundRemoteFunction.schema[B, C]),
        { case (either, left, right) =>
          FoldEither(
            either,
            left,
            right
          )
        },
        _.either,
        _.left,
        _.right
      )

    def schemaCase[A, B, C]: Schema.Case[FoldEither[A, B, C], Remote[C]] =
      Schema.Case("FoldEither", schema[A, B, C], _.asInstanceOf[FoldEither[A, B, C]])
  }

  final case class SwapEither[A, B](
    either: Remote[Either[A, B]]
  ) extends Remote[Either[B, A]] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      either.evalDynamic.flatMap {
        case DynamicValue.LeftValue(value) =>
          ZIO.succeed(DynamicValue.RightValue(value))
        case DynamicValue.RightValue(value) =>
          ZIO.succeed(DynamicValue.LeftValue(value))
        case other: DynamicValue =>
          ZIO.fail(
            RemoteEvaluationError.UnexpectedDynamicValue(
              s"Unexpected value in Remote.SwapEither of type ${other.getClass.getSimpleName}"
            )
          )
      }

    override protected def substituteRec[C](f: Remote.Substitutions): Remote[Either[B, A]] =
      SwapEither(either.substitute(f))

    override private[flow] val variableUsage = either.variableUsage
  }

  object SwapEither {
    def schema[A, B]: Schema[SwapEither[A, B]] =
      Schema
        .defer(
          Remote
            .schema[Either[A, B]]
            .transform(
              SwapEither(_),
              _.either
            )
        )

    def schemaCase[A]: Schema.Case[SwapEither[Any, Any], Remote[A]] =
      Schema.Case("SwapEither", schema[Any, Any], _.asInstanceOf[SwapEither[Any, Any]])
  }

  final case class Try[A](either: Either[Remote[Throwable], Remote[A]]) extends Remote[scala.util.Try[A]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      either match {
        case Left(throwable) =>
          throwable.evalDynamic.map { throwableValue =>
            DynamicValue.Enumeration("Failure" -> DynamicValue.Record(ListMap("exception" -> throwableValue)))
          }
        case Right(success) =>
          success.evalDynamic.map { successValue =>
            DynamicValue.Enumeration("Success" -> DynamicValue.Record(ListMap("value" -> successValue)))
          }
      }

    override def equals(obj: Any): Boolean =
      obj match {
        case Try(otherEither) =>
          (either, otherEither) match {
            case (Left(value), Left(otherValue)) =>
              value == otherValue
            case (Right(value), Right(otherValue)) =>
              value == otherValue
            case _ => false
          }
        case _ => false
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[scala.util.Try[A]] =
      Try(either match {
        case Left(throwable) => Left(throwable.substitute(f))
        case Right(success)  => Right(success.substitute(f))
      })

    override private[flow] val variableUsage = either match {
      case Left(throwable) => throwable.variableUsage
      case Right(success)  => success.variableUsage
    }
  }

  object Try {
    def schema[A]: Schema[Try[A]] =
      Schema.defer(
        Schema
          .either(Remote.schema[Throwable], Remote.schema[A])
          .transform(
            Try.apply,
            _.either
          )
      )

    def schemaCase[A]: Schema.Case[Try[Any], Remote[A]] =
      Schema.Case("Try", schema[Any], _.asInstanceOf[Try[Any]])
  }

  // beginning of generated tuple constructors
  final case class Tuple2[T1, T2](t1: Remote[T1], t2: Remote[T2])
      extends Remote[(T1, T2)]
      with RemoteTuple2.Construct[T1, T2] {
    override protected def substituteRec[B](f: Remote.Substitutions): Remote[(T1, T2)] =
      Tuple2(t1.substitute(f), t2.substitute(f))
    override private[flow] val variableUsage = VariableUsage.none.union(t1.variableUsage).union(t2.variableUsage)
  }

  object Tuple2 extends RemoteTuple2.ConstructStatic[Tuple2] {
    def construct[T1, T2](t1: Remote[T1], t2: Remote[T2]): Tuple2[T1, T2] = Tuple2(t1, t2)
  }

  final case class Tuple3[T1, T2, T3](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3])
      extends Remote[(T1, T2, T3)]
      with RemoteTuple3.Construct[T1, T2, T3] {
    override protected def substituteRec[B](f: Remote.Substitutions): Remote[(T1, T2, T3)] =
      Tuple3(t1.substitute(f), t2.substitute(f), t3.substitute(f))
    override private[flow] val variableUsage =
      VariableUsage.none.union(t1.variableUsage).union(t2.variableUsage).union(t3.variableUsage)
  }

  object Tuple3 extends RemoteTuple3.ConstructStatic[Tuple3] {
    def construct[T1, T2, T3](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3]): Tuple3[T1, T2, T3] = Tuple3(t1, t2, t3)
  }

  final case class Tuple4[T1, T2, T3, T4](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3], t4: Remote[T4])
      extends Remote[(T1, T2, T3, T4)]
      with RemoteTuple4.Construct[T1, T2, T3, T4] {
    override protected def substituteRec[B](f: Remote.Substitutions): Remote[(T1, T2, T3, T4)] =
      Tuple4(t1.substitute(f), t2.substitute(f), t3.substitute(f), t4.substitute(f))
    override private[flow] val variableUsage =
      VariableUsage.none.union(t1.variableUsage).union(t2.variableUsage).union(t3.variableUsage).union(t4.variableUsage)
  }

  object Tuple4 extends RemoteTuple4.ConstructStatic[Tuple4] {
    def construct[T1, T2, T3, T4](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4]
    ): Tuple4[T1, T2, T3, T4] = Tuple4(t1, t2, t3, t4)
  }

  final case class Tuple5[T1, T2, T3, T4, T5](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5]
  ) extends Remote[(T1, T2, T3, T4, T5)]
      with RemoteTuple5.Construct[T1, T2, T3, T4, T5] {
    override protected def substituteRec[B](f: Remote.Substitutions): Remote[(T1, T2, T3, T4, T5)] =
      Tuple5(t1.substitute(f), t2.substitute(f), t3.substitute(f), t4.substitute(f), t5.substitute(f))
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
  }

  object Tuple5 extends RemoteTuple5.ConstructStatic[Tuple5] {
    def construct[T1, T2, T3, T4, T5](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5]
    ): Tuple5[T1, T2, T3, T4, T5] = Tuple5(t1, t2, t3, t4, t5)
  }

  final case class Tuple6[T1, T2, T3, T4, T5, T6](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6]
  ) extends Remote[(T1, T2, T3, T4, T5, T6)]
      with RemoteTuple6.Construct[T1, T2, T3, T4, T5, T6] {
    override protected def substituteRec[B](f: Remote.Substitutions): Remote[(T1, T2, T3, T4, T5, T6)] =
      Tuple6(t1.substitute(f), t2.substitute(f), t3.substitute(f), t4.substitute(f), t5.substitute(f), t6.substitute(f))
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
  }

  object Tuple6 extends RemoteTuple6.ConstructStatic[Tuple6] {
    def construct[T1, T2, T3, T4, T5, T6](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6]
    ): Tuple6[T1, T2, T3, T4, T5, T6] = Tuple6(t1, t2, t3, t4, t5, t6)
  }

  final case class Tuple7[T1, T2, T3, T4, T5, T6, T7](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7)]
      with RemoteTuple7.Construct[T1, T2, T3, T4, T5, T6, T7] {
    override protected def substituteRec[B](f: Remote.Substitutions): Remote[(T1, T2, T3, T4, T5, T6, T7)] =
      Tuple7(
        t1.substitute(f),
        t2.substitute(f),
        t3.substitute(f),
        t4.substitute(f),
        t5.substitute(f),
        t6.substitute(f),
        t7.substitute(f)
      )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
  }

  object Tuple7 extends RemoteTuple7.ConstructStatic[Tuple7] {
    def construct[T1, T2, T3, T4, T5, T6, T7](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7]
    ): Tuple7[T1, T2, T3, T4, T5, T6, T7] = Tuple7(t1, t2, t3, t4, t5, t6, t7)
  }

  final case class Tuple8[T1, T2, T3, T4, T5, T6, T7, T8](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8)]
      with RemoteTuple8.Construct[T1, T2, T3, T4, T5, T6, T7, T8] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8)] = Tuple8(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
  }

  object Tuple8 extends RemoteTuple8.ConstructStatic[Tuple8] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8]
    ): Tuple8[T1, T2, T3, T4, T5, T6, T7, T8] = Tuple8(t1, t2, t3, t4, t5, t6, t7, t8)
  }

  final case class Tuple9[T1, T2, T3, T4, T5, T6, T7, T8, T9](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9)]
      with RemoteTuple9.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9)] = Tuple9(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
  }

  object Tuple9 extends RemoteTuple9.ConstructStatic[Tuple9] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9]
    ): Tuple9[T1, T2, T3, T4, T5, T6, T7, T8, T9] = Tuple9(t1, t2, t3, t4, t5, t6, t7, t8, t9)
  }

  final case class Tuple10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)]
      with RemoteTuple10.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)] = Tuple10(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
  }

  object Tuple10 extends RemoteTuple10.ConstructStatic[Tuple10] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10]
    ): Tuple10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10] = Tuple10(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)
  }

  final case class Tuple11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)]
      with RemoteTuple11.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)] = Tuple11(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
  }

  object Tuple11 extends RemoteTuple11.ConstructStatic[Tuple11] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11]
    ): Tuple11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11] = Tuple11(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)
  }

  final case class Tuple12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12)]
      with RemoteTuple12.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12)] = Tuple12(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
  }

  object Tuple12 extends RemoteTuple12.ConstructStatic[Tuple12] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12]
    ): Tuple12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12] =
      Tuple12(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)
  }

  final case class Tuple13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13)]
      with RemoteTuple13.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13)] = Tuple13(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f),
      t13.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
  }

  object Tuple13 extends RemoteTuple13.ConstructStatic[Tuple13] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13]
    ): Tuple13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13] =
      Tuple13(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13)
  }

  final case class Tuple14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14)]
      with RemoteTuple14.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14)] = Tuple14(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f),
      t13.substitute(f),
      t14.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
  }

  object Tuple14 extends RemoteTuple14.ConstructStatic[Tuple14] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14]
    ): Tuple14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14] =
      Tuple14(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14)
  }

  final case class Tuple15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15)]
      with RemoteTuple15.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15)] = Tuple15(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f),
      t13.substitute(f),
      t14.substitute(f),
      t15.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
      .union(t15.variableUsage)
  }

  object Tuple15 extends RemoteTuple15.ConstructStatic[Tuple15] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15]
    ): Tuple15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15] =
      Tuple15(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15)
  }

  final case class Tuple16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16)]
      with RemoteTuple16.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16)] = Tuple16(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f),
      t13.substitute(f),
      t14.substitute(f),
      t15.substitute(f),
      t16.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
      .union(t15.variableUsage)
      .union(t16.variableUsage)
  }

  object Tuple16 extends RemoteTuple16.ConstructStatic[Tuple16] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16]
    ): Tuple16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16] =
      Tuple16(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16)
  }

  final case class Tuple17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17)]
      with RemoteTuple17.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17)] = Tuple17(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f),
      t13.substitute(f),
      t14.substitute(f),
      t15.substitute(f),
      t16.substitute(f),
      t17.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
      .union(t15.variableUsage)
      .union(t16.variableUsage)
      .union(t17.variableUsage)
  }

  object Tuple17 extends RemoteTuple17.ConstructStatic[Tuple17] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17]
    ): Tuple17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17] =
      Tuple17(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17)
  }

  final case class Tuple18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18)]
      with RemoteTuple18.Construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18)] = Tuple18(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f),
      t13.substitute(f),
      t14.substitute(f),
      t15.substitute(f),
      t16.substitute(f),
      t17.substitute(f),
      t18.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
      .union(t15.variableUsage)
      .union(t16.variableUsage)
      .union(t17.variableUsage)
      .union(t18.variableUsage)
  }

  object Tuple18 extends RemoteTuple18.ConstructStatic[Tuple18] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18]
    ): Tuple18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18] =
      Tuple18(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18)
  }

  final case class Tuple19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18],
    t19: Remote[T19]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19)]
      with RemoteTuple19.Construct[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15,
        T16,
        T17,
        T18,
        T19
      ] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19)] = Tuple19(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f),
      t13.substitute(f),
      t14.substitute(f),
      t15.substitute(f),
      t16.substitute(f),
      t17.substitute(f),
      t18.substitute(f),
      t19.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
      .union(t15.variableUsage)
      .union(t16.variableUsage)
      .union(t17.variableUsage)
      .union(t18.variableUsage)
      .union(t19.variableUsage)
  }

  object Tuple19 extends RemoteTuple19.ConstructStatic[Tuple19] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18],
      t19: Remote[T19]
    ): Tuple19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19] =
      Tuple19(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19)
  }

  final case class Tuple20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18],
    t19: Remote[T19],
    t20: Remote[T20]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20)]
      with RemoteTuple20.Construct[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15,
        T16,
        T17,
        T18,
        T19,
        T20
      ] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20)] = Tuple20(
      t1.substitute(f),
      t2.substitute(f),
      t3.substitute(f),
      t4.substitute(f),
      t5.substitute(f),
      t6.substitute(f),
      t7.substitute(f),
      t8.substitute(f),
      t9.substitute(f),
      t10.substitute(f),
      t11.substitute(f),
      t12.substitute(f),
      t13.substitute(f),
      t14.substitute(f),
      t15.substitute(f),
      t16.substitute(f),
      t17.substitute(f),
      t18.substitute(f),
      t19.substitute(f),
      t20.substitute(f)
    )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
      .union(t15.variableUsage)
      .union(t16.variableUsage)
      .union(t17.variableUsage)
      .union(t18.variableUsage)
      .union(t19.variableUsage)
      .union(t20.variableUsage)
  }

  object Tuple20 extends RemoteTuple20.ConstructStatic[Tuple20] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18],
      t19: Remote[T19],
      t20: Remote[T20]
    ): Tuple20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20] =
      Tuple20(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20)
  }

  final case class Tuple21[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21
  ](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18],
    t19: Remote[T19],
    t20: Remote[T20],
    t21: Remote[T21]
  ) extends Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21)]
      with RemoteTuple21.Construct[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15,
        T16,
        T17,
        T18,
        T19,
        T20,
        T21
      ] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21)] =
      Tuple21(
        t1.substitute(f),
        t2.substitute(f),
        t3.substitute(f),
        t4.substitute(f),
        t5.substitute(f),
        t6.substitute(f),
        t7.substitute(f),
        t8.substitute(f),
        t9.substitute(f),
        t10.substitute(f),
        t11.substitute(f),
        t12.substitute(f),
        t13.substitute(f),
        t14.substitute(f),
        t15.substitute(f),
        t16.substitute(f),
        t17.substitute(f),
        t18.substitute(f),
        t19.substitute(f),
        t20.substitute(f),
        t21.substitute(f)
      )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
      .union(t15.variableUsage)
      .union(t16.variableUsage)
      .union(t17.variableUsage)
      .union(t18.variableUsage)
      .union(t19.variableUsage)
      .union(t20.variableUsage)
      .union(t21.variableUsage)
  }

  object Tuple21 extends RemoteTuple21.ConstructStatic[Tuple21] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18],
      t19: Remote[T19],
      t20: Remote[T20],
      t21: Remote[T21]
    ): Tuple21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21] =
      Tuple21(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21)
  }

  final case class Tuple22[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21,
    T22
  ](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5],
    t6: Remote[T6],
    t7: Remote[T7],
    t8: Remote[T8],
    t9: Remote[T9],
    t10: Remote[T10],
    t11: Remote[T11],
    t12: Remote[T12],
    t13: Remote[T13],
    t14: Remote[T14],
    t15: Remote[T15],
    t16: Remote[T16],
    t17: Remote[T17],
    t18: Remote[T18],
    t19: Remote[T19],
    t20: Remote[T20],
    t21: Remote[T21],
    t22: Remote[T22]
  ) extends Remote[
        (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22)
      ]
      with RemoteTuple22.Construct[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15,
        T16,
        T17,
        T18,
        T19,
        T20,
        T21,
        T22
      ] {
    override protected def substituteRec[B](
      f: Remote.Substitutions
    ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22)] =
      Tuple22(
        t1.substitute(f),
        t2.substitute(f),
        t3.substitute(f),
        t4.substitute(f),
        t5.substitute(f),
        t6.substitute(f),
        t7.substitute(f),
        t8.substitute(f),
        t9.substitute(f),
        t10.substitute(f),
        t11.substitute(f),
        t12.substitute(f),
        t13.substitute(f),
        t14.substitute(f),
        t15.substitute(f),
        t16.substitute(f),
        t17.substitute(f),
        t18.substitute(f),
        t19.substitute(f),
        t20.substitute(f),
        t21.substitute(f),
        t22.substitute(f)
      )
    override private[flow] val variableUsage = VariableUsage.none
      .union(t1.variableUsage)
      .union(t2.variableUsage)
      .union(t3.variableUsage)
      .union(t4.variableUsage)
      .union(t5.variableUsage)
      .union(t6.variableUsage)
      .union(t7.variableUsage)
      .union(t8.variableUsage)
      .union(t9.variableUsage)
      .union(t10.variableUsage)
      .union(t11.variableUsage)
      .union(t12.variableUsage)
      .union(t13.variableUsage)
      .union(t14.variableUsage)
      .union(t15.variableUsage)
      .union(t16.variableUsage)
      .union(t17.variableUsage)
      .union(t18.variableUsage)
      .union(t19.variableUsage)
      .union(t20.variableUsage)
      .union(t21.variableUsage)
      .union(t22.variableUsage)
  }

  object Tuple22 extends RemoteTuple22.ConstructStatic[Tuple22] {
    def construct[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22](
      t1: Remote[T1],
      t2: Remote[T2],
      t3: Remote[T3],
      t4: Remote[T4],
      t5: Remote[T5],
      t6: Remote[T6],
      t7: Remote[T7],
      t8: Remote[T8],
      t9: Remote[T9],
      t10: Remote[T10],
      t11: Remote[T11],
      t12: Remote[T12],
      t13: Remote[T13],
      t14: Remote[T14],
      t15: Remote[T15],
      t16: Remote[T16],
      t17: Remote[T17],
      t18: Remote[T18],
      t19: Remote[T19],
      t20: Remote[T20],
      t21: Remote[T21],
      t22: Remote[T22]
    ): Tuple22[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22] =
      Tuple22(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20, t21, t22)
  }
  // end of generated tuple constructors

  final case class TupleAccess[T, A](tuple: Remote[T], n: Int) extends Remote[A] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        dynTuple <- tuple.evalDynamic
        value     = TupleAccess.findValueIn(dynTuple, n)
      } yield value

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[A] =
      TupleAccess(tuple.substitute(f), n)

    override private[flow] val variableUsage = tuple.variableUsage
  }

  object TupleAccess {
    private def findValueIn(value: DynamicValue, n: Int): DynamicValue = {
      def find(value: DynamicValue, current: Int): Either[Int, DynamicValue] =
        value match {
          case DynamicValue.Tuple(a, b) =>
            if (current == n) {
              find(a, current)
            } else {
              find(a, current) match {
                case Left(updated) =>
                  find(b, updated)
                case Right(value) =>
                  Right(value)
              }
            }
          case _ if current == n =>
            Right(value)
          case _ =>
            Left(current + 1)
        }

      find(value, 0) match {
        case Left(_)      => throw new IllegalStateException(s"Cannot find value for index $n in dynamic tuple")
        case Right(value) => value
      }
    }

    def schema[T, A]: Schema[TupleAccess[T, A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[T], Int, TupleAccess[T, A]](
          Schema.Field("tuple", Remote.schema[T]),
          Schema.Field("n", Schema[Int]),
          TupleAccess(_, _),
          _.tuple,
          _.n
        )
      )

    def schemaCase[A]: Schema.Case[TupleAccess[Any, A], Remote[A]] =
      Schema.Case("TupleAccess", schema[Any, A], _.asInstanceOf[TupleAccess[Any, A]])
  }

  final case class Branch[A](predicate: Remote[Boolean], ifTrue: Remote[A], ifFalse: Remote[A]) extends Remote[A] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      predicate.eval.flatMap {
        case false => ifFalse.evalDynamic
        case true  => ifTrue.evalDynamic
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[A] =
      Branch(
        predicate.substitute(f),
        ifTrue.substitute(f),
        ifFalse.substitute(f)
      )

    override private[flow] val variableUsage =
      predicate.variableUsage.union(ifTrue.variableUsage).union(ifFalse.variableUsage)
  }

  object Branch {
    def schema[A]: Schema[Branch[A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[Boolean], Remote[A], Remote[A], Branch[A]](
          Schema.Field("predicate", Remote.schema[Boolean]),
          Schema.Field("ifTrue", Remote.schema[A]),
          Schema.Field("ifFalse", Remote.schema[A]),
          { case (predicate, ifTrue, ifFalse) => Branch(predicate, ifTrue, ifFalse) },
          _.predicate,
          _.ifTrue,
          _.ifFalse
        )
      )

    def schemaCase[A]: Schema.Case[Branch[A], Remote[A]] =
      Schema.Case("Branch", schema[A], _.asInstanceOf[Branch[A]])
  }

  case class Length(remoteString: Remote[String]) extends Remote[Int] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      remoteString.eval.map { value =>
        DynamicValue.fromSchemaAndValue(Schema[Int], value.length)
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Int] =
      Length(remoteString.substitute(f))

    override private[flow] val variableUsage = remoteString.variableUsage
  }

  object Length {
    val schema: Schema[Length] = Schema.defer(
      Remote
        .schema[String]
        .transform(
          Length.apply,
          _.remoteString
        )
    )

    def schemaCase[A]: Schema.Case[Length, Remote[A]] =
      Schema.Case("Length", schema, _.asInstanceOf[Length])
  }

  final case class LessThanEqual[A](left: Remote[A], right: Remote[A], schema: Schema[A]) extends Remote[Boolean] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        leftVal      <- left.eval(schema)
        rightVal     <- right.eval(schema)
        ordering      = schema.ordering
        compareResult = ordering.compare(leftVal, rightVal)
      } yield DynamicValueHelpers.of(compareResult <= 0)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Boolean] =
      LessThanEqual(left.substitute(f), right.substitute(f), schema)

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object LessThanEqual {
    def schema[A]: Schema[LessThanEqual[A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[A], Remote[A], FlowSchemaAst, LessThanEqual[A]](
          Schema.Field("left", Remote.schema[A]),
          Schema.Field("right", Remote.schema[A]),
          Schema.Field("schema", FlowSchemaAst.schema),
          { case (left, right, schema) => LessThanEqual(left, right, schema.toSchema[A]) },
          _.left,
          _.right,
          lte => FlowSchemaAst.fromSchema(lte.schema)
        )
      )

    def schemaCase[A]: Schema.Case[LessThanEqual[Any], Remote[A]] =
      Schema.Case("LessThanEqual", schema[Any], _.asInstanceOf[LessThanEqual[Any]])
  }

  final case class Equal[A](left: Remote[A], right: Remote[A]) extends Remote[Boolean] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        leftDyn  <- left.evalDynamic
        rightDyn <- right.evalDynamic
        result    = leftDyn == rightDyn
      } yield DynamicValue.fromSchemaAndValue(Schema[Boolean], result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Boolean] =
      Equal(left.substitute(f), right.substitute(f))

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object Equal {
    def schema[A]: Schema[Equal[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[A], Remote[A], Equal[A]](
          Schema.Field("left", Remote.schema[A]),
          Schema.Field("right", Remote.schema[A]),
          { case (left, right) => Equal(left, right) },
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[Equal[Any], Remote[A]] =
      Schema.Case("Equal", schema[Any], _.asInstanceOf[Equal[Any]])
  }

  final case class Not(value: Remote[Boolean]) extends Remote[Boolean] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      value.eval.map { boolValue =>
        DynamicValue.fromSchemaAndValue(Schema[Boolean], !boolValue)
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Boolean] =
      Not(value.substitute(f))

    override private[flow] val variableUsage = value.variableUsage
  }

  object Not {
    val schema: Schema[Not] = Schema.defer(
      Remote
        .schema[Boolean]
        .transform(
          Not.apply,
          _.value
        )
    )

    def schemaCase[A]: Schema.Case[Not, Remote[A]] =
      Schema.Case("Not", schema, _.asInstanceOf[Not])
  }

  final case class And(left: Remote[Boolean], right: Remote[Boolean]) extends Remote[Boolean] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        lval <- left.eval
        rval <- right.eval
      } yield DynamicValue.fromSchemaAndValue(Schema[Boolean], lval && rval)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Boolean] =
      And(left.substitute(f), right.substitute(f))

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object And {
    val schema: Schema[And] =
      Schema.defer(
        Schema.CaseClass2[Remote[Boolean], Remote[Boolean], And](
          Schema.Field("left", Remote.schema[Boolean]),
          Schema.Field("right", Remote.schema[Boolean]),
          { case (left, right) => And(left, right) },
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[And, Remote[A]] =
      Schema.Case("And", schema, _.asInstanceOf[And])
  }

  final case class Fold[A, B](list: Remote[List[A]], initial: Remote[B], body: UnboundRemoteFunction[(B, A), B])
      extends Remote[B] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      list.evalDynamic.flatMap { listDyn =>
        for {
          initialDyn <- initial.evalDynamic
          result <- listDyn match {
                      case DynamicValue.Sequence(elemsDyn) =>
                        ZIO.foldLeft(elemsDyn)(initialDyn) { case (b, a) =>
                          val appliedBody =
                            body
                              .apply(
                                Remote
                                  .Tuple2(
                                    Remote.fromDynamic(b),
                                    Remote.fromDynamic(a)
                                  )
                              )

                          appliedBody.evalDynamic
                        }
                      case _ =>
                        ZIO.fail(
                          RemoteEvaluationError.UnexpectedDynamicValue(s"Fold's list did not evaluate into a sequence")
                        )
                    }
        } yield result
      }

    override protected def substituteRec[C](f: Remote.Substitutions): Remote[B] =
      Fold(
        list.substitute(f),
        initial.substitute(f),
        body.substitute(f).asInstanceOf[UnboundRemoteFunction[(B, A), B]]
      )

    override private[flow] val variableUsage = list.variableUsage.union(initial.variableUsage).union(body.variableUsage)
  }

  object Fold {
    def schema[A, B]: Schema[Fold[A, B]] =
      Schema.defer(
        Schema.CaseClass3[Remote[List[A]], Remote[B], UnboundRemoteFunction[(B, A), B], Fold[A, B]](
          Schema.Field("list", Remote.schema[List[A]]),
          Schema.Field("initial", Remote.schema[B]),
          Schema.Field("body", UnboundRemoteFunction.schema[(B, A), B]),
          { case (list, initial, body) => Fold(list, initial, body) },
          _.list,
          _.initial,
          _.body
        )
      )

    def schemaCase[A]: Schema.Case[Fold[Any, A], Remote[A]] =
      Schema.Case("Fold", schema[Any, A], _.asInstanceOf[Fold[Any, A]])
  }

  final case class Cons[A](list: Remote[List[A]], head: Remote[A]) extends Remote[List[A]] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      head.evalDynamic.flatMap { headDyn =>
        list.evalDynamic.flatMap {
          case DynamicValue.Sequence(values) =>
            ZIO.succeed(DynamicValue.Sequence(headDyn +: values))
          case other: DynamicValue =>
            ZIO.fail(
              RemoteEvaluationError.UnexpectedDynamicValue(
                s"Unexpected list value for Remote.Cons: ${other.getClass.getSimpleName}"
              )
            )
        }
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[List[A]] =
      Cons(list.substitute(f), head.substitute(f))

    override private[flow] val variableUsage = list.variableUsage.union(head.variableUsage)
  }

  object Cons {
    def schema[A]: Schema[Cons[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[List[A]], Remote[A], Cons[A]](
          Schema.Field("list", Remote.schema[List[A]]),
          Schema.Field("head", Remote.schema[A]),
          Cons.apply,
          _.list,
          _.head
        )
      )

    def schemaCase[A]: Schema.Case[Cons[A], Remote[A]] =
      Schema.Case("Cons", schema[A], _.asInstanceOf[Cons[A]])
  }

  final case class UnCons[A](list: Remote[List[A]]) extends Remote[Option[(A, List[A])]] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      list.evalDynamic.flatMap {
        case DynamicValue.Sequence(values) =>
          val lst = values.toList
          lst match {
            case head :: tail =>
              ZIO.succeed(
                DynamicValue.SomeValue(DynamicValueHelpers.tuple(head, DynamicValue.Sequence(Chunk.fromIterable(tail))))
              )
            case _ =>
              ZIO.succeed(DynamicValue.NoneValue)
          }

        case other: DynamicValue =>
          ZIO.fail(
            RemoteEvaluationError.UnexpectedDynamicValue(
              s"Unexpected list value for Remote.UnCons: ${other.getClass.getSimpleName}"
            )
          )
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Option[(A, List[A])]] =
      UnCons(list.substitute(f))

    override private[flow] val variableUsage = list.variableUsage
  }

  object UnCons {
    def schema[A]: Schema[UnCons[A]] = Schema.defer(
      Remote
        .schema[List[A]]
        .transform(
          UnCons.apply,
          _.list
        )
    )

    def schemaCase[A]: Schema.Case[UnCons[A], Remote[A]] =
      Schema.Case("UnCons", schema[A], _.asInstanceOf[UnCons[A]])
  }

  final case class InstantFromLongs(seconds: Remote[Long], nanos: Remote[Long]) extends Remote[Instant] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        s <- seconds.eval[Long]
        n <- nanos.eval[Long]
      } yield DynamicValueHelpers.of(Instant.ofEpochSecond(s, n))

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Instant] =
      InstantFromLongs(seconds.substitute(f), nanos.substitute(f))

    override private[flow] val variableUsage = seconds.variableUsage.union(nanos.variableUsage)
  }

  object InstantFromLongs {

    val schema: Schema[InstantFromLongs] =
      Schema.defer(
        Schema.CaseClass2[Remote[Long], Remote[Long], InstantFromLongs](
          Schema.Field("seconds", Remote.schema[Long]),
          Schema.Field("nanos", Remote.schema[Long]),
          InstantFromLongs.apply,
          _.seconds,
          _.nanos
        )
      )

    def schemaCase[A]: Schema.Case[InstantFromLongs, Remote[A]] =
      Schema.Case("InstantFromLongs", schema, _.asInstanceOf[InstantFromLongs])
  }

  final case class InstantFromString(charSeq: Remote[String]) extends Remote[Instant] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      charSeq.eval[String].map(s => DynamicValueHelpers.of(Instant.parse(s)))

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Instant] =
      InstantFromString(charSeq.substitute(f))

    override private[flow] val variableUsage = charSeq.variableUsage
  }

  object InstantFromString {
    val schema: Schema[InstantFromString] = Schema.defer(
      Remote
        .schema[String]
        .transform(
          InstantFromString.apply,
          _.charSeq
        )
    )

    def schemaCase[A]: Schema.Case[InstantFromString, Remote[A]] =
      Schema.Case("InstantFromString", schema, _.asInstanceOf[InstantFromString])
  }

  final case class InstantToTuple(instant: Remote[Instant]) extends Remote[(Long, Int)] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      instant.eval[Instant].map { instant =>
        DynamicValue
          .fromSchemaAndValue(Schema.tuple2(Schema[Long], Schema[Int]), (instant.getEpochSecond, instant.getNano))
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[(Long, Int)] =
      InstantToTuple(instant.substitute(f))

    override private[flow] val variableUsage = instant.variableUsage
  }

  object InstantToTuple {
    val schema: Schema[InstantToTuple] = Schema.defer(
      Remote
        .schema[Instant]
        .transform(
          InstantToTuple.apply,
          _.instant
        )
    )

    def schemaCase[A]: Schema.Case[InstantToTuple, Remote[A]] =
      Schema.Case("InstantToTuple", schema, _.asInstanceOf[InstantToTuple])
  }

  final case class InstantPlusDuration(instant: Remote[Instant], duration: Remote[Duration]) extends Remote[Instant] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        instant  <- instant.eval[Instant]
        duration <- duration.eval[Duration]
        result    = instant.plusSeconds(duration.getSeconds).plusNanos(duration.getNano.toLong)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Instant] =
      InstantPlusDuration(instant.substitute(f), duration.substitute(f))

    override private[flow] val variableUsage = instant.variableUsage.union(duration.variableUsage)
  }

  object InstantPlusDuration {
    val schema: Schema[InstantPlusDuration] =
      Schema.defer(
        Schema.CaseClass2[Remote[Instant], Remote[Duration], InstantPlusDuration](
          Schema.Field("instant", Remote.schema[Instant]),
          Schema.Field("duration", Remote.schema[Duration]),
          InstantPlusDuration.apply,
          _.instant,
          _.duration
        )
      )

    def schemaCase[A]: Schema.Case[InstantPlusDuration, Remote[A]] =
      Schema.Case("InstantPlusDuration", schema, _.asInstanceOf[InstantPlusDuration])
  }

  final case class InstantTruncate(instant: Remote[Instant], temporalUnit: Remote[ChronoUnit]) extends Remote[Instant] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        instant      <- instant.eval[Instant]
        temporalUnit <- temporalUnit.eval[ChronoUnit].tapError(s => ZIO.debug(s"Failed to evaluate temporal unit: $s"))
        result        = instant.truncatedTo(temporalUnit)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Instant] =
      InstantTruncate(instant.substitute(f), temporalUnit.substitute(f))

    override private[flow] val variableUsage = instant.variableUsage.union(temporalUnit.variableUsage)
  }

  object InstantTruncate {
    val schema: Schema[InstantTruncate] =
      Schema.defer(
        Schema.CaseClass2[Remote[Instant], Remote[ChronoUnit], InstantTruncate](
          Schema.Field("instant", Remote.schema[Instant]),
          Schema.Field("temporalUnit", Remote.schema[ChronoUnit]),
          InstantTruncate.apply,
          _.instant,
          _.temporalUnit
        )
      )

    def schemaCase[A]: Schema.Case[InstantTruncate, Remote[A]] =
      Schema.Case("InstantTruncate", schema, _.asInstanceOf[InstantTruncate])
  }

  final case class DurationFromString(charSeq: Remote[String]) extends Remote[Duration] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      charSeq.eval[String].map(s => DynamicValueHelpers.of(Duration.parse(s)))

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Duration] =
      DurationFromString(charSeq.substitute(f))

    override private[flow] val variableUsage = charSeq.variableUsage
  }

  object DurationFromString {
    val schema: Schema[DurationFromString] = Schema.defer(
      Remote
        .schema[String]
        .transform(
          DurationFromString.apply,
          _.charSeq
        )
    )

    def schemaCase[A]: Schema.Case[DurationFromString, Remote[A]] =
      Schema.Case("DurationFromString", schema, _.asInstanceOf[DurationFromString])
  }

  final case class DurationBetweenInstants(startInclusive: Remote[Instant], endExclusive: Remote[Instant])
      extends Remote[Duration] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        start <- startInclusive.eval[Instant]
        end   <- endExclusive.eval[Instant]
        result = Duration.between(start, end)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Duration] =
      DurationBetweenInstants(
        startInclusive.substitute(f),
        endExclusive.substitute(f)
      )

    override private[flow] val variableUsage = startInclusive.variableUsage.union(endExclusive.variableUsage)
  }

  object DurationBetweenInstants {
    val schema: Schema[DurationBetweenInstants] =
      Schema.defer(
        Schema.CaseClass2[Remote[Instant], Remote[Instant], DurationBetweenInstants](
          Schema.Field("startInclusive", Remote.schema[Instant]),
          Schema.Field("endExclusive", Remote.schema[Instant]),
          DurationBetweenInstants.apply,
          _.startInclusive,
          _.endExclusive
        )
      )

    def schemaCase[A]: Schema.Case[DurationBetweenInstants, Remote[A]] =
      Schema.Case("DurationBetweenInstants", schema, _.asInstanceOf[DurationBetweenInstants])
  }

  final case class DurationFromBigDecimal(seconds: Remote[BigDecimal]) extends Remote[Duration] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        bd     <- seconds.eval[BigDecimal]
        seconds = bd.longValue()
        nanos   = bd.subtract(new BigDecimal(seconds)).multiply(DurationFromBigDecimal.oneBillion).intValue()
        result  = Duration.ofSeconds(seconds, nanos.toLong)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Duration] =
      DurationFromBigDecimal(seconds.substitute(f))

    override private[flow] val variableUsage = seconds.variableUsage
  }

  object DurationFromBigDecimal {
    private val oneBillion = new BigDecimal(1000000000L)

    val schema: Schema[DurationFromBigDecimal] = Schema.defer(
      Remote
        .schema[BigDecimal]
        .transform(
          DurationFromBigDecimal.apply,
          _.seconds
        )
    )

    def schemaCase[A]: Schema.Case[DurationFromBigDecimal, Remote[A]] =
      Schema.Case("DurationFromBigDecimal", schema, _.asInstanceOf[DurationFromBigDecimal])
  }

  final case class DurationFromLongs(seconds: Remote[Long], nanoAdjustment: Remote[Long]) extends Remote[Duration] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        seconds        <- seconds.eval[Long]
        nanoAdjustment <- nanoAdjustment.eval[Long]
        result          = Duration.ofSeconds(seconds, nanoAdjustment)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Duration] =
      DurationFromLongs(seconds.substitute(f), nanoAdjustment.substitute(f))

    override private[flow] val variableUsage = seconds.variableUsage.union(nanoAdjustment.variableUsage)
  }

  object DurationFromLongs {
    val schema: Schema[DurationFromLongs] =
      Schema.defer(
        Schema.CaseClass2[Remote[Long], Remote[Long], DurationFromLongs](
          Schema.Field("seconds", Remote.schema[Long]),
          Schema.Field("nanoAdjusment", Remote.schema[Long]),
          DurationFromLongs.apply,
          _.seconds,
          _.nanoAdjustment
        )
      )

    def schemaCase[A]: Schema.Case[DurationFromLongs, Remote[A]] =
      Schema.Case("DurationFromLongs", schema, _.asInstanceOf[DurationFromLongs])
  }

  final case class DurationFromAmount(amount: Remote[Long], temporalUnit: Remote[ChronoUnit]) extends Remote[Duration] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        amount       <- amount.eval[Long]
        temporalUnit <- temporalUnit.eval[ChronoUnit]
        result        = Duration.of(amount, temporalUnit)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Duration] =
      DurationFromAmount(amount.substitute(f), temporalUnit.substitute(f))

    override private[flow] val variableUsage = amount.variableUsage.union(temporalUnit.variableUsage)
  }

  object DurationFromAmount {
    val schema: Schema[DurationFromAmount] =
      Schema.defer(
        Schema.CaseClass2[Remote[Long], Remote[ChronoUnit], DurationFromAmount](
          Schema.Field("amount", Remote.schema[Long]),
          Schema.Field("temporalUnit", Remote.schema[ChronoUnit]),
          DurationFromAmount.apply,
          _.amount,
          _.temporalUnit
        )
      )

    def schemaCase[A]: Schema.Case[DurationFromAmount, Remote[A]] =
      Schema.Case("DurationFromAmount", schema, _.asInstanceOf[DurationFromAmount])
  }

  final case class DurationToLongs(duration: Remote[Duration]) extends Remote[(Long, Long)] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      duration.eval[Duration].map { duration =>
        DynamicValue
          .fromSchemaAndValue(Schema.tuple2(Schema[Long], Schema[Long]), (duration.getSeconds, duration.getNano.toLong))
      }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[(Long, Long)] =
      DurationToLongs(duration.substitute(f))

    override private[flow] val variableUsage = duration.variableUsage
  }

  object DurationToLongs {
    val schema: Schema[DurationToLongs] = Schema.defer(
      Remote
        .schema[Duration]
        .transform(
          DurationToLongs.apply,
          _.duration
        )
    )

    def schemaCase[A]: Schema.Case[DurationToLongs, Remote[A]] =
      Schema.Case("DurationToLongs", schema, _.asInstanceOf[DurationToLongs])
  }

  final case class DurationPlusDuration(left: Remote[Duration], right: Remote[Duration]) extends Remote[Duration] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        left  <- left.eval[Duration]
        right <- right.eval[Duration]
        result = left.plus(right)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Duration] =
      DurationPlusDuration(left.substitute(f), right.substitute(f))

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object DurationPlusDuration {
    val schema: Schema[DurationPlusDuration] =
      Schema.defer(
        Schema.CaseClass2[Remote[Duration], Remote[Duration], DurationPlusDuration](
          Schema.Field("left", Remote.schema[Duration]),
          Schema.Field("right", Remote.schema[Duration]),
          DurationPlusDuration.apply,
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[DurationPlusDuration, Remote[A]] =
      Schema.Case("DurationPlusDuration", schema, _.asInstanceOf[DurationPlusDuration])
  }

  final case class DurationMultipliedBy(left: Remote[Duration], right: Remote[Long]) extends Remote[Duration] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        left  <- left.eval[Duration]
        right <- right.eval[Long]
        result = left.multipliedBy(right)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Duration] =
      DurationMultipliedBy(left.substitute(f), right.substitute(f))

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object DurationMultipliedBy {
    val schema: Schema[DurationMultipliedBy] =
      Schema.defer(
        Schema.CaseClass2[Remote[Duration], Remote[Long], DurationMultipliedBy](
          Schema.Field("left", Remote.schema[Duration]),
          Schema.Field("right", Remote.schema[Long]),
          DurationMultipliedBy.apply,
          _.left,
          _.right
        )
      )

    def schemaCase[A]: Schema.Case[DurationMultipliedBy, Remote[A]] =
      Schema.Case("DurationMultipliedBy", schema, _.asInstanceOf[DurationMultipliedBy])
  }

  final case class Iterate[A](
    initial: Remote[A],
    iterate: UnboundRemoteFunction[A, A],
    predicate: UnboundRemoteFunction[A, Boolean]
  ) extends Remote[A] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] = {
      def loop(current: Remote[A]): ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
        predicate(current).eval[Boolean].flatMap {
          case false => current.evalDynamic
          case true  => loop(iterate(current))
        }

      loop(initial)
    }

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[A] =
      Iterate(
        initial.substitute(f),
        iterate.substitute(f).asInstanceOf[UnboundRemoteFunction[A, A]],
        predicate.substitute(f).asInstanceOf[UnboundRemoteFunction[A, Boolean]]
      )

    override private[flow] val variableUsage =
      initial.variableUsage.union(iterate.variableUsage).union(predicate.variableUsage)
  }

  object Iterate {
    def schema[A]: Schema[Iterate[A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[A], UnboundRemoteFunction[A, A], UnboundRemoteFunction[A, Boolean], Iterate[A]](
          Schema.Field("initial", Remote.schema[A]),
          Schema.Field("iterate", UnboundRemoteFunction.schema[A, A]),
          Schema.Field("predicate", UnboundRemoteFunction.schema[A, Boolean]),
          Iterate.apply,
          _.initial,
          _.iterate,
          _.predicate
        )
      )

    def schemaCase[A]: Schema.Case[Iterate[A], Remote[A]] =
      Schema.Case("Iterate", schema, _.asInstanceOf[Iterate[A]])
  }

  final case class Lazy[A](value: () => Remote[A]) extends Remote[A] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      value().evalDynamic

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[A] =
      Lazy(() => value().substitute(f))

    override private[flow] lazy val variableUsage = value().variableUsage
  }

  object Lazy {
    def schema[A]: Schema[Lazy[A]] =
      Schema.defer(Remote.schema[A].transform((a: Remote[A]) => Lazy(() => a), _.value()))

    def schemaCase[A]: Schema.Case[Lazy[A], Remote[A]] =
      Schema.Case("Lazy", schema, _.asInstanceOf[Lazy[A]])
  }

  final case class RemoteSome[A](value: Remote[A]) extends Remote[Option[A]] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        dyn <- value.evalDynamic
      } yield DynamicValue.SomeValue(dyn)

    override protected def substituteRec[B](f: Remote.Substitutions): Remote[Option[A]] =
      RemoteSome(value.substitute(f))

    override private[flow] val variableUsage = value.variableUsage
  }

  object RemoteSome {
    def schema[A]: Schema[RemoteSome[A]] =
      Schema.defer(Remote.schema[A].transform(RemoteSome(_), _.value))

    def schemaCase[A]: Schema.Case[RemoteSome[A], Remote[A]] =
      Schema.Case("RemoteSome", schema, _.asInstanceOf[RemoteSome[A]])
  }

  final case class FoldOption[A, B](
    option: Remote[Option[A]],
    ifEmpty: Remote[B],
    ifNonEmpty: UnboundRemoteFunction[A, B]
  ) extends Remote[B] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      option.evalDynamic.flatMap {
        case DynamicValue.NoneValue =>
          ifEmpty.evalDynamic
        case DynamicValue.SomeValue(value) =>
          ifNonEmpty(Remote.fromDynamic(value)).evalDynamic
        case other: DynamicValue =>
          ZIO.fail(
            RemoteEvaluationError.UnexpectedDynamicValue(
              s"Unexpected value in Remote.FoldOption: ${other.getClass.getSimpleName}"
            )
          )
      }

    override protected def substituteRec[C](f: Remote.Substitutions): Remote[B] =
      FoldOption(
        option.substitute(f),
        ifEmpty.substitute(f),
        ifNonEmpty.substitute(f).asInstanceOf[UnboundRemoteFunction[A, B]]
      )

    override private[flow] val variableUsage =
      option.variableUsage.union(ifEmpty.variableUsage).union(ifNonEmpty.variableUsage)
  }

  object FoldOption {
    def schema[A, B]: Schema[FoldOption[A, B]] =
      Schema.defer(
        Schema.CaseClass3[Remote[Option[A]], Remote[B], UnboundRemoteFunction[A, B], FoldOption[A, B]](
          Schema.Field("option", Remote.schema[Option[A]]),
          Schema.Field("ifEmpty", Remote.schema[B]),
          Schema.Field("ifNonEmpty", UnboundRemoteFunction.schema[A, B]),
          FoldOption.apply,
          _.option,
          _.ifEmpty,
          _.ifNonEmpty
        )
      )

    def schemaCase[A]: Schema.Case[FoldOption[Any, A], Remote[A]] =
      Schema.Case("FoldOption", schema, _.asInstanceOf[FoldOption[Any, A]])
  }

  final case class Recurse[A](
    id: RecursionId,
    initial: Remote[A],
    body: UnboundRemoteFunction[A, A]
  ) extends Remote[A] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        _ <- RemoteContext
               .setVariable(
                 id.toRemoteVariableName,
                 DynamicValue.fromSchemaAndValue(UnboundRemoteFunction.schema[A, A], body)
               )
               .mapError(RemoteEvaluationError.RemoteContextError)
        result <- body(initial).evalDynamic
      } yield result

    override private[flow] def variableUsage =
      initial.variableUsage.union(body.variableUsage)

    override protected def substituteRec[B](f: Substitutions): Remote[A] =
      Recurse(
        id,
        initial.substitute(f),
        body.substitute(f).asInstanceOf[UnboundRemoteFunction[A, A]]
      )
  }

  object Recurse {
    def schema[A]: Schema[Recurse[A]] =
      Schema.defer {
        Schema
          .CaseClass3[RecursionId, Remote[A], UnboundRemoteFunction[A, A], Recurse[A]](
            Schema.Field("id", Schema[RecursionId]),
            Schema.Field("initial", Remote.schema[A]),
            Schema.Field("body", UnboundRemoteFunction.schema[A, A]),
            Recurse(_, _, _),
            _.id,
            _.initial,
            _.body
          )
      }

    def schemaCase[A]: Schema.Case[Recurse[A], Remote[A]] =
      Schema.Case("Recurse", schema, _.asInstanceOf[Recurse[A]])
  }

  final case class RecurseWith[A](id: RecursionId, value: Remote[A]) extends Remote[A] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      RemoteContext
        .getVariable(id.toRemoteVariableName)
        .mapError(RemoteEvaluationError.RemoteContextError)
        .flatMap {
          case None =>
            ZIO.fail(RemoteEvaluationError.RecursionNotFound(id))
          case Some(dynamicBody) =>
            ZIO
              .fromEither(dynamicBody.toTypedValue(UnboundRemoteFunction.schema[A, A]))
              .mapError(RemoteEvaluationError.TypeError)
              .flatMap { body =>
                body(value).evalDynamic
              }
        }

    override private[flow] def variableUsage =
      value.variableUsage.union(VariableUsage.variable(id.toRemoteVariableName))

    override protected def substituteRec[B](f: Substitutions): Remote[A] =
      RecurseWith(id, value.substitute(f))
  }

  object RecurseWith {
    def schema[A]: Schema[RecurseWith[A]] =
      Schema.defer {
        Schema.CaseClass2[RecursionId, Remote[A], RecurseWith[A]](
          Schema.Field("id", Schema[RecursionId]),
          Schema.Field("value", Remote.schema[A]),
          RecurseWith(_, _),
          _.id,
          _.value
        )
      }

    def schemaCase[A]: Schema.Case[RecurseWith[A], Remote[A]] =
      Schema.Case("RecurseWith", schema, _.asInstanceOf[RecurseWith[A]])
  }

  final case class ListToSet[A](list: Remote[List[A]]) extends Remote[Set[A]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      list.evalDynamic.flatMap {
        case DynamicValue.Sequence(values) =>
          ZIO.succeed(DynamicValue.SetValue(values.toSet))
        case other: DynamicValue =>
          ZIO.fail(
            RemoteEvaluationError.UnexpectedDynamicValue(
              s"Unexpected value in Remote.ListToSet of type ${other.getClass.getSimpleName}"
            )
          )
      }

    override private[flow] def variableUsage: VariableUsage =
      list.variableUsage

    override protected def substituteRec[B](f: Substitutions): Remote[Set[A]] =
      ListToSet(list.substituteRec(f))
  }

  object ListToSet {
    def schema[A]: Schema[ListToSet[A]] =
      Schema.defer(Remote.schema[List[A]].transform(ListToSet(_), _.list))

    def schemaCase[A]: Schema.Case[ListToSet[A], Remote[A]] =
      Schema.Case("ListToSet", schema, _.asInstanceOf[ListToSet[A]])
  }

  final case class SetToList[A](set: Remote[Set[A]]) extends Remote[List[A]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      set.evalDynamic.flatMap {
        case DynamicValue.SetValue(values) =>
          ZIO.succeed(DynamicValue.Sequence(Chunk.fromIterable(values)))
        case other: DynamicValue =>
          ZIO.fail(
            RemoteEvaluationError.UnexpectedDynamicValue(
              s"Unexpected value in Remote.SetToList of type ${other.getClass.getSimpleName}"
            )
          )
      }

    override private[flow] def variableUsage: VariableUsage =
      set.variableUsage

    override protected def substituteRec[B](f: Substitutions): Remote[List[A]] =
      SetToList(set.substituteRec(f))
  }

  object SetToList {
    def schema[A]: Schema[SetToList[A]] =
      Schema.defer(Remote.schema[Set[A]].transform(SetToList(_), _.set))

    def schemaCase[A]: Schema.Case[SetToList[A], Remote[A]] =
      Schema.Case("SetToList", schema, _.asInstanceOf[SetToList[A]])
  }

  final case class ListToString(
    list: Remote[List[String]],
    start: Remote[String],
    sep: Remote[String],
    end: Remote[String]
  ) extends Remote[String] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        list  <- list.eval
        start <- start.eval
        sep   <- sep.eval
        end   <- end.eval
      } yield DynamicValue.fromSchemaAndValue(Schema[String], list.mkString(start, sep, end))

    override private[flow] def variableUsage: VariableUsage =
      list.variableUsage union
        start.variableUsage union
        sep.variableUsage union
        end.variableUsage

    override protected def substituteRec[B](f: Substitutions): Remote[String] =
      ListToString(list.substituteRec(f), start.substituteRec(f), sep.substituteRec(f), end.substituteRec(f))
  }

  object ListToString {
    val schema: Schema[ListToString] =
      Schema.defer(
        Schema.CaseClass4[Remote[List[String]], Remote[String], Remote[String], Remote[String], ListToString](
          Schema.Field("list", Remote.schema[List[String]]),
          Schema.Field("start", Remote.schema[String]),
          Schema.Field("sep", Remote.schema[String]),
          Schema.Field("end", Remote.schema[String]),
          ListToString(_, _, _, _),
          _.list,
          _.start,
          _.sep,
          _.end
        )
      )

    def schemaCase[A]: Schema.Case[ListToString, Remote[A]] =
      Schema.Case("ListToString", schema, _.asInstanceOf[ListToString])
  }

//  final case class LensGet[S, A](whole: Remote[S], lens: RemoteLens[S, A]) extends Remote[A] {
//    val schema: Schema[A] = SchemaOrNothing.fromSchema(lens.schemaPiece)
//
//    def evalWithSchema: ZIO[LocalContext with RemoteContext, Nothing, Either[Remote[A], SchemaAndValue[A]]] =
//      whole.evalWithSchema.map {
//        case Right(SchemaAndValue(_, whole)) => Right(SchemaAndValue(lens.schemaPiece, lens.unsafeGet(whole)))
//        case _                               => Left(self)
//      }
//  }
//
//  final case class LensSet[S, A](whole: Remote[S], piece: Remote[A], lens: RemoteLens[S, A]) extends Remote[S] {
//    val schema: Schema[S] = SchemaOrNothing.fromSchema(lens.schemaWhole)
//
//    def evalWithSchema: ZIO[LocalContext with RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
//      whole.evalWithSchema.flatMap {
//        case Right(SchemaAndValue(_, whole)) =>
//          piece.evalWithSchema.flatMap {
//            case Right(SchemaAndValue(_, piece)) =>
//              val newValue = lens.unsafeSet(piece)(whole)
//              ZIO.right(SchemaAndValue(lens.schemaWhole, newValue))
//            case _ => ZIO.left(self)
//          }
//        case _ => ZIO.left(self)
//      }
//  }
//
//  final case class PrismGet[S, A](whole: Remote[S], prism: RemotePrism[S, A]) extends Remote[Option[A]] {
//    val schema: Schema[Option[A]] = SchemaOrNothing.fromSchema(Schema.option(prism.schemaPiece))
//
//    def evalWithSchema: ZIO[LocalContext with RemoteContext, Nothing, Either[Remote[Option[A]], SchemaAndValue[Option[A]]]] =
//      whole.evalWithSchema.map {
//        case Right(SchemaAndValue(_, whole)) =>
//          Right(SchemaAndValue(Schema.option(prism.schemaPiece), prism.unsafeGet(whole)))
//        case _ => Left(self)
//      }
//  }
//
//  final case class PrismSet[S, A](piece: Remote[A], prism: RemotePrism[S, A]) extends Remote[S] {
//    val schema: Schema[S] = SchemaOrNothing.fromSchema(prism.schemaWhole)
//
//    def evalWithSchema: ZIO[LocalContext with RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
//      piece.evalWithSchema.map {
//        case Right(SchemaAndValue(_, piece)) => Right(SchemaAndValue(prism.schemaWhole, prism.unsafeSet(piece)))
//        case _                               => Left(self)
//      }
//  }
//
//  final case class TraversalGet[S, A](whole: Remote[S], traversal: RemoteTraversal[S, A]) extends Remote[Chunk[A]] {
//    val schema: Schema[Chunk[A]] = SchemaOrNothing.fromSchema(Schema.chunk(traversal.schemaPiece))
//
//    def evalWithSchema: ZIO[LocalContext with RemoteContext, Nothing, Either[Remote[Chunk[A]], SchemaAndValue[Chunk[A]]]] =
//      whole.evalWithSchema.map {
//        case Right(SchemaAndValue(_, whole)) =>
//          Right(SchemaAndValue(Schema.chunk(traversal.schemaPiece), traversal.unsafeGet(whole)))
//        case _ => Left(self)
//      }
//  }
//
//  final case class TraversalSet[S, A](whole: Remote[S], piece: Remote[Chunk[A]], traversal: RemoteTraversal[S, A])
//      extends Remote[S] {
//    val schema: Schema[S] = SchemaOrNothing.fromSchema(traversal.schemaWhole)
//
//    def evalWithSchema: ZIO[LocalContext with RemoteContext, Nothing, Either[Remote[S], SchemaAndValue[S]]] =
//      whole.evalWithSchema.flatMap {
//        case Right(SchemaAndValue(_, whole)) =>
//          piece.evalWithSchema.flatMap {
//            case Right(SchemaAndValue(_, piece)) =>
//              val newValue = traversal.unsafeSet(whole)(piece)
//              ZIO.right(SchemaAndValue(traversal.schemaWhole, newValue))
//            case _ => ZIO.left(self)
//          }
//        case _ => ZIO.left(self)
//      }
//  }

  case class EvaluatedRemoteFunction[-A, +B](result: DynamicValue) extends AnyVal

  case class Substitutions(
    bindings: Map[Remote.Unbound[_], Remote[_]]
  ) {
    def matches(remote: Remote[_]): Option[Remote[_]] =
      remote match {
        case unbound: Remote.Unbound[_] => bindings.get(unbound)
        case _                          => None
      }

    lazy val bindingNames: Set[BindingName] = bindings.keySet.map(_.identifier)

    def cut(usage: VariableUsage): Boolean =
      usage.bindings.intersect(bindingNames).isEmpty
  }

  implicit def apply[A: Schema](value: A): Remote[A] =
    // TODO: can we do this on type level instead?
    value match {
      case dynamicValue: DynamicValue =>
        Literal(dynamicValue)
      case flow: ZFlow[_, _, _] =>
        Flow(flow).asInstanceOf[Remote[A]]
      case remote: Remote[Any] =>
        Nested(remote).asInstanceOf[Remote[A]]
      case _ =>
        Literal(DynamicValue.fromSchemaAndValue(Schema[A], value))
    }

  def fail[A](message: String): Remote[A] =
    Remote.Fail(message)

  def fromDynamic[A](dynamicValue: DynamicValue): Remote[A] =
    // TODO: either avoid this or do it nicer
    dynamicValue.toTypedValue(RemoteVariableReference.schema[Any]) match {
      case Left(_) =>
        dynamicValue.toTypedValue(ZFlow.schemaAny) match {
          case Left(_) =>
            // Not a ZFlow
            dynamicValue.toTypedValue(Remote.schemaAny) match {
              case Left(_) =>
                // Not a Remote
                dynamicValue match {
                  case dynamicTuple: DynamicValue.Tuple =>
                    Tuple2(
                      Remote.fromDynamic(dynamicTuple.left),
                      Remote.fromDynamic(dynamicTuple.right)
                    ).asInstanceOf[Remote[A]]
                  // TODO: flatten tuple?
                  case _ =>
                    Literal(dynamicValue)
                }
              case Right(remote) =>
                Nested(remote).asInstanceOf[Remote[A]]
            }
          case Right(zflow) =>
            Flow(zflow).asInstanceOf[Remote[A]]
        }
      case Right(ref) =>
        VariableReference(ref).asInstanceOf[Remote[A]]
    }

  def left[A, B](value: Remote[A]): Remote[Either[A, B]] =
    Remote.RemoteEither(Left(value))

  def list[A](values: Remote[A]*): Remote[List[A]] =
    values.foldLeft(nil[A])(Remote.Cons.apply)

  def ofEpochSecond(second: Remote[Long]): Remote[Instant] = Remote.InstantFromLongs(second, Remote(0L))

  def ofEpochSecond(second: Remote[Long], nanos: Remote[Long]): Remote[Instant] = Remote.InstantFromLongs(second, nanos)

  def ofEpochMilli(milliSecond: Remote[Long]): Remote[Instant] =
    Remote.InstantFromLongs(
      milliSecond / 1000L,
      (milliSecond % 1000L) * 1000000L
    )

  def ofSeconds(seconds: Remote[Long]): Remote[Duration] = Remote.DurationFromLongs(seconds, Remote(0L))

  def ofSeconds(seconds: Remote[Long], nanos: Remote[Long]): Remote[Duration] = Remote.DurationFromLongs(seconds, nanos)

  def ofMinutes(minutes: Remote[Long]): Remote[Duration] = Remote.ofSeconds(minutes * Remote(60L))

  def ofHours(hours: Remote[Long]): Remote[Duration] = Remote.ofMinutes(hours * Remote(60L))

  def ofDays(days: Remote[Long]): Remote[Duration] = Remote.ofHours(days * Remote(24L))

  def ofMillis(milliseconds: Remote[Long]): Remote[Duration] =
    Remote.DurationFromAmount(milliseconds, Remote(ChronoUnit.MILLIS))

  def ofNanos(nanoseconds: Remote[Long]): Remote[Duration] =
    Remote.DurationFromAmount(nanoseconds, Remote(ChronoUnit.NANOS))

  def nil[A]: Remote[List[A]] = Remote.Literal(DynamicValue.Sequence(Chunk.empty))

  def none[A]: Remote[Option[A]] = Remote.Literal(DynamicValue.NoneValue)

  def recurse[A](
    initial: Remote[A]
  )(body: (Remote[A], (Remote[A] => Remote.RecurseWith[A])) => Remote[A]): Remote[A] = {
    val id = LocalContext.generateFreshRecursionId
    Remote.Recurse(
      id,
      initial,
      UnboundRemoteFunction.make((value: Remote[A]) => body(value, (next: Remote[A]) => Remote.RecurseWith(id, next)))
    )
  }

  def right[A, B](value: Remote[B]): Remote[Either[A, B]] = Remote.RemoteEither(Right(value))

  def sequenceEither[A, B](
    either: Either[Remote[A], Remote[B]]
  ): Remote[Either[A, B]] =
    RemoteEither(either)

  def some[A](value: Remote[A]): Remote[Option[A]] = Remote.RemoteSome(value)

  def suspend[A](remote: Remote[A]): Remote[A] = Lazy(() => remote)

  implicit def tuple2[T1, T2](t: (Remote[T1], Remote[T2])): Remote[(T1, T2)] =
    Tuple2(t._1, t._2)

  implicit def tuple3[T1, T2, T3](t: (Remote[T1], Remote[T2], Remote[T3])): Remote[(T1, T2, T3)] =
    Tuple3(t._1, t._2, t._3)

  implicit def tuple4[T1, T2, T3, T4](t: (Remote[T1], Remote[T2], Remote[T3], Remote[T4])): Remote[(T1, T2, T3, T4)] =
    Tuple4(t._1, t._2, t._3, t._4)

  implicit def tuple5[T1, T2, T3, T4, T5](
    t: (Remote[T1], Remote[T2], Remote[T3], Remote[T4], Remote[T5])
  ): Remote[(T1, T2, T3, T4, T5)] =
    Tuple5(t._1, t._2, t._3, t._4, t._5)

  implicit def tuple6[T1, T2, T3, T4, T5, T6](
    t: (Remote[T1], Remote[T2], Remote[T3], Remote[T4], Remote[T5], Remote[T6])
  ): Remote[(T1, T2, T3, T4, T5, T6)] =
    Tuple6(t._1, t._2, t._3, t._4, t._5, t._6)

  implicit def tuple7[T1, T2, T3, T4, T5, T6, T7](
    t: (Remote[T1], Remote[T2], Remote[T3], Remote[T4], Remote[T5], Remote[T6], Remote[T7])
  ): Remote[(T1, T2, T3, T4, T5, T6, T7)] =
    Tuple7(t._1, t._2, t._3, t._4, t._5, t._6, t._7)

  implicit def tuple8[T1, T2, T3, T4, T5, T6, T7, T8](
    t: (Remote[T1], Remote[T2], Remote[T3], Remote[T4], Remote[T5], Remote[T6], Remote[T7], Remote[T8])
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8)] =
    Tuple8(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8)

  implicit def tuple9[T1, T2, T3, T4, T5, T6, T7, T8, T9](
    t: (Remote[T1], Remote[T2], Remote[T3], Remote[T4], Remote[T5], Remote[T6], Remote[T7], Remote[T8], Remote[T9])
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9)] =
    Tuple9(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9)

  implicit def tuple10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)] =
    Tuple10(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10)

  implicit def tuple11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)] =
    Tuple11(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11)

  implicit def tuple12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12)] =
    Tuple12(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11, t._12)

  implicit def tuple13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13)] =
    Tuple13(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11, t._12, t._13)

  implicit def tuple14[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14)] =
    Tuple14(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11, t._12, t._13, t._14)

  implicit def tuple15[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14],
      Remote[T15]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15)] =
    Tuple15(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11, t._12, t._13, t._14, t._15)

  implicit def tuple16[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14],
      Remote[T15],
      Remote[T16]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16)] =
    Tuple16(t._1, t._2, t._3, t._4, t._5, t._6, t._7, t._8, t._9, t._10, t._11, t._12, t._13, t._14, t._15, t._16)

  implicit def tuple17[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14],
      Remote[T15],
      Remote[T16],
      Remote[T17]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17)] =
    Tuple17(
      t._1,
      t._2,
      t._3,
      t._4,
      t._5,
      t._6,
      t._7,
      t._8,
      t._9,
      t._10,
      t._11,
      t._12,
      t._13,
      t._14,
      t._15,
      t._16,
      t._17
    )

  implicit def tuple18[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14],
      Remote[T15],
      Remote[T16],
      Remote[T17],
      Remote[T18]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18)] =
    Tuple18(
      t._1,
      t._2,
      t._3,
      t._4,
      t._5,
      t._6,
      t._7,
      t._8,
      t._9,
      t._10,
      t._11,
      t._12,
      t._13,
      t._14,
      t._15,
      t._16,
      t._17,
      t._18
    )

  implicit def tuple19[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14],
      Remote[T15],
      Remote[T16],
      Remote[T17],
      Remote[T18],
      Remote[T19]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19)] =
    Tuple19(
      t._1,
      t._2,
      t._3,
      t._4,
      t._5,
      t._6,
      t._7,
      t._8,
      t._9,
      t._10,
      t._11,
      t._12,
      t._13,
      t._14,
      t._15,
      t._16,
      t._17,
      t._18,
      t._19
    )

  implicit def tuple20[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14],
      Remote[T15],
      Remote[T16],
      Remote[T17],
      Remote[T18],
      Remote[T19],
      Remote[T20]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20)] =
    Tuple20(
      t._1,
      t._2,
      t._3,
      t._4,
      t._5,
      t._6,
      t._7,
      t._8,
      t._9,
      t._10,
      t._11,
      t._12,
      t._13,
      t._14,
      t._15,
      t._16,
      t._17,
      t._18,
      t._19,
      t._20
    )

  implicit def tuple21[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14],
      Remote[T15],
      Remote[T16],
      Remote[T17],
      Remote[T18],
      Remote[T19],
      Remote[T20],
      Remote[T21]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21)] =
    Tuple21(
      t._1,
      t._2,
      t._3,
      t._4,
      t._5,
      t._6,
      t._7,
      t._8,
      t._9,
      t._10,
      t._11,
      t._12,
      t._13,
      t._14,
      t._15,
      t._16,
      t._17,
      t._18,
      t._19,
      t._20,
      t._21
    )

  implicit def tuple22[
    T1,
    T2,
    T3,
    T4,
    T5,
    T6,
    T7,
    T8,
    T9,
    T10,
    T11,
    T12,
    T13,
    T14,
    T15,
    T16,
    T17,
    T18,
    T19,
    T20,
    T21,
    T22
  ](
    t: (
      Remote[T1],
      Remote[T2],
      Remote[T3],
      Remote[T4],
      Remote[T5],
      Remote[T6],
      Remote[T7],
      Remote[T8],
      Remote[T9],
      Remote[T10],
      Remote[T11],
      Remote[T12],
      Remote[T13],
      Remote[T14],
      Remote[T15],
      Remote[T16],
      Remote[T17],
      Remote[T18],
      Remote[T19],
      Remote[T20],
      Remote[T21],
      Remote[T22]
    )
  ): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13, T14, T15, T16, T17, T18, T19, T20, T21, T22)] =
    Tuple22(
      t._1,
      t._2,
      t._3,
      t._4,
      t._5,
      t._6,
      t._7,
      t._8,
      t._9,
      t._10,
      t._11,
      t._12,
      t._13,
      t._14,
      t._15,
      t._16,
      t._17,
      t._18,
      t._19,
      t._20,
      t._21,
      t._22
    )

  implicit def toFlow[A](remote: Remote[A]): ZFlow[Any, Nothing, A] = remote.toFlow

  implicit def capturedRemoteToRemote[A, B](f: Remote[A] => Remote[B]): UnboundRemoteFunction[A, B] =
    UnboundRemoteFunction.make((a: Remote[A]) => f(a))

  val unit: Remote[Unit] = Remote.Ignore()

  private def createSchema[A]: Schema[Remote[A]] = Schema.EnumN(
    CaseSet
      .Cons(Literal.schemaCase[A], CaseSet.Empty[Remote[A]]())
      .:+:(Fail.schemaCase[A])
      .:+:(Flow.schemaCase[A])
      .:+:(Nested.schemaCase[A])
      .:+:(VariableReference.schemaCase[A])
      .:+:(Ignore.schemaCase[A])
      .:+:(Variable.schemaCase[A])
      .:+:(Unbound.schemaCase[A])
      .:+:(Unary.schemaCase[Any, A])
      .:+:(Binary.schemaCase[Any, A])
      .:+:(UnboundRemoteFunction.schemaCase[Any, A])
      .:+:(EvaluateUnboundRemoteFunction.schemaCase[Any, A])
      .:+:(RemoteEither.schemaCase[A])
      .:+:(FoldEither.schemaCase[Any, Any, A])
      .:+:(SwapEither.schemaCase[A])
      .:+:(Try.schemaCase[A])
      .:+:(Tuple2.schemaCase[A])
      .:+:(Tuple3.schemaCase[A])
      .:+:(Tuple4.schemaCase[A])
      .:+:(Tuple5.schemaCase[A])
      .:+:(Tuple6.schemaCase[A])
      .:+:(Tuple7.schemaCase[A])
      .:+:(Tuple8.schemaCase[A])
      .:+:(Tuple9.schemaCase[A])
      .:+:(Tuple10.schemaCase[A])
      .:+:(Tuple11.schemaCase[A])
      .:+:(Tuple12.schemaCase[A])
      .:+:(Tuple13.schemaCase[A])
      .:+:(Tuple14.schemaCase[A])
      .:+:(Tuple15.schemaCase[A])
      .:+:(Tuple16.schemaCase[A])
      .:+:(Tuple17.schemaCase[A])
      .:+:(Tuple18.schemaCase[A])
      .:+:(Tuple19.schemaCase[A])
      .:+:(Tuple20.schemaCase[A])
      .:+:(Tuple21.schemaCase[A])
      .:+:(Tuple22.schemaCase[A])
      .:+:(TupleAccess.schemaCase[A])
      .:+:(Branch.schemaCase[A])
      .:+:(Length.schemaCase[A])
      .:+:(LessThanEqual.schemaCase[A])
      .:+:(Equal.schemaCase[A])
      .:+:(Not.schemaCase[A])
      .:+:(And.schemaCase[A])
      .:+:(Fold.schemaCase[A])
      .:+:(Cons.schemaCase[A])
      .:+:(UnCons.schemaCase[A])
      .:+:(InstantFromLongs.schemaCase[A])
      .:+:(InstantFromString.schemaCase[A])
      .:+:(InstantToTuple.schemaCase[A])
      .:+:(InstantPlusDuration.schemaCase[A])
      .:+:(InstantTruncate.schemaCase[A])
      .:+:(DurationFromString.schemaCase[A])
      .:+:(DurationBetweenInstants.schemaCase[A])
      .:+:(DurationFromBigDecimal.schemaCase[A])
      .:+:(DurationFromLongs.schemaCase[A])
      .:+:(DurationFromAmount.schemaCase[A])
      .:+:(DurationToLongs.schemaCase[A])
      .:+:(DurationPlusDuration.schemaCase[A])
      .:+:(DurationMultipliedBy.schemaCase[A])
      .:+:(Iterate.schemaCase[A])
      .:+:(Lazy.schemaCase[A])
      .:+:(RemoteSome.schemaCase[A])
      .:+:(FoldOption.schemaCase[A])
      .:+:(Recurse.schemaCase[A])
      .:+:(RecurseWith.schemaCase[A])
      .:+:(ListToSet.schemaCase[A])
      .:+:(SetToList.schemaCase[A])
      .:+:(ListToString.schemaCase[A])
  )

  implicit val schemaAny: Schema[Remote[Any]] = createSchema[Any]
  def schema[A]: Schema[Remote[A]]            = schemaAny.asInstanceOf[Schema[Remote[A]]]
}
