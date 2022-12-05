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

import zio.flow.Remote.Debug.DebugMode
import zio.flow.remote.{
  BinaryOperators,
  DynamicValueHelpers,
  InternalRemoteTracking,
  RemoteAccessorBuilder,
  RemoteConversions,
  RemoteOptic,
  UnaryOperators
}
import zio.flow.remote.RemoteTuples._
import zio.flow.serialization.FlowSchemaAst
import zio.schema.{CaseSet, DeriveSchema, DynamicValue, Schema, TypeId}
import zio.{Cause, Chunk, Duration, FiberFailure, ZIO}

import java.time.temporal.ChronoUnit
import scala.annotation.tailrec
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

  protected def substituteRec(f: Remote.Substitutions): Remote[A]

  def toString[A1 >: A: Schema]: Remote[String] =
    Remote.Unary(self, UnaryOperators.Conversion(RemoteConversions.ToString[A1]()))

  def debug(message: String): Remote[A] =
    Remote.Debug(self, message, DebugMode.Print)

  def track(message: String): Remote[A] =
    Remote.Debug(self, message, DebugMode.Track)

  private[flow] def trackInternal(message: String)(implicit remoteTracking: InternalRemoteTracking): Remote[A] =
    if (remoteTracking.enabled)
      Remote.Debug(self, message, DebugMode.Track)
    else
      self

  def `match`[A1 >: A: Schema, B](cases: (A1, Remote[B])*)(default: Remote[B]): Remote[B] =
    cases.foldLeft(default) { case (fallback, (test, result)) =>
      (self.widen[A1] === Remote(test)).ifThenElse(
        ifTrue = result,
        ifFalse = fallback
      )
    }
}

object Remote {

  /**
   * Constructs accessors that can be used modify remote versions of user
   * defined data types.
   */
  def makeAccessors[A](implicit
    schema: Schema[A]
  ): schema.Accessors[RemoteOptic.Lens, RemoteOptic.Prism, RemoteOptic.Traversal] =
    schema.makeAccessors(RemoteAccessorBuilder)

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

    override protected def substituteRec(f: Remote.Substitutions): Remote[A] = this

    override private[flow] val variableUsage = VariableUsage.none
  }

  object Literal {
    def schema[A]: Schema[Literal[A]] =
      Schema.Dynamic().transform(Literal(_), _.value)

    def schemaCase[A]: Schema.Case[Remote[A], Literal[A]] =
      Schema.Case("Literal", schema[A], _.asInstanceOf[Literal[A]], x => x, _.isInstanceOf[Literal[A]])
  }

  final case class Fail[A](message: String) extends Remote[A] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.fail(RemoteEvaluationError.RemoteFail(message))

    override private[flow] def variableUsage = VariableUsage.none

    override protected def substituteRec(f: Substitutions): Remote[A] = this
  }

  object Fail {
    def schema[A]: Schema[Fail[A]] =
      Schema[String].transform(Fail(_), _.message)

    def schemaCase[A]: Schema.Case[Remote[A], Fail[A]] =
      Schema.Case("Fail", schema[A], _.asInstanceOf[Fail[A]], x => x, _.isInstanceOf[Fail[A]])
  }

  final case class Debug[A](inner: Remote[A], message: String, debugMode: Debug.DebugMode) extends Remote[A] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      debugMode match {
        case DebugMode.Print =>
          for {
            result <- inner.evalDynamic
            _ <-
              ZIO.logDebug(s"$message " + result.toString)
          } yield result
        case DebugMode.Track =>
          inner.evalDynamic @@ metrics.remoteEvaluationCount(message) @@ metrics.remoteEvaluationTimeMillis(message)
      }

    override private[flow] def variableUsage =
      inner.variableUsage

    override protected def substituteRec(f: Substitutions): Remote[A] =
      Debug(inner.substitute(f), message, debugMode)
  }

  object Debug {
    sealed trait DebugMode
    object DebugMode {
      case object Print extends DebugMode
      case object Track extends DebugMode

      implicit val schema: Schema[DebugMode] = DeriveSchema.gen
    }

    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Debug")

    def schema[A]: Schema[Debug[A]] =
      Schema.defer {
        Schema.CaseClass3[Remote[A], String, DebugMode, Debug[A]](
          typeId,
          Schema
            .Field("inner", Remote.schema[A], get0 = _.inner, set0 = (a: Debug[A], v: Remote[A]) => a.copy(inner = v)),
          Schema
            .Field("message", Schema[String], get0 = _.message, set0 = (a: Debug[A], v: String) => a.copy(message = v)),
          Schema.Field(
            "debugMode",
            Schema[DebugMode],
            get0 = _.debugMode,
            set0 = (a: Debug[A], v: DebugMode) => a.copy(debugMode = v)
          ),
          Debug.apply
        )
      }

    def schemaCase[A]: Schema.Case[Remote[A], Debug[A]] =
      Schema.Case("Debug", schema[A], _.asInstanceOf[Debug[A]], x => x, _.isInstanceOf[Debug[A]])
  }

  final case class Flow[R, E, A](flow: ZFlow[R, E, A]) extends Remote[ZFlow[R, E, A]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.succeed(DynamicValue.fromSchemaAndValue(ZFlow.schema[R, E, A], flow))

    override protected def substituteRec(f: Remote.Substitutions): Remote[ZFlow[R, E, A]] =
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

    def schemaCase[A]: Schema.Case[Remote[A], Flow[Any, Any, Any]] =
      Schema.Case(
        "Flow",
        schema[Any, Any, Any],
        _.asInstanceOf[Flow[Any, Any, Any]],
        x => x.asInstanceOf[Remote[A]],
        _.isInstanceOf[Flow[_, _, _]]
      )
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[Remote[A]] =
      Nested(remote.substitute(f))

    override private[flow] val variableUsage = remote.variableUsage
  }

  object Nested {
    def schema[A]: Schema[Nested[A]] =
      Schema.defer(Remote.schema[A].transform(Nested(_), _.remote))

    def schemaCase[A]: Schema.Case[Remote[A], Nested[Any]] =
      Schema.Case(
        "Nested",
        schema[Any],
        _.asInstanceOf[Nested[Any]],
        x => x.asInstanceOf[Remote[A]],
        _.isInstanceOf[Nested[_]]
      )
  }

  final case class VariableReference[A](ref: RemoteVariableReference[A]) extends Remote[RemoteVariableReference[A]] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.succeed(DynamicValue.fromSchemaAndValue(RemoteVariableReference.schema[A], ref))

    override private[flow] def variableUsage: VariableUsage =
      VariableUsage.variable(ref.name)

    override protected def substituteRec(f: Substitutions): Remote[RemoteVariableReference[A]] =
      this

    /**
     * Gets a [[Remote]] which represents the value stored in this remote
     * variable
     */
    def dereference: Remote.Variable[A] = ref.toRemote
  }

  object VariableReference {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.VariableReference")

    // NOTE: must be kept identifiable from DynamicValues in Remote.fromDynamic
    def schema[A]: Schema[VariableReference[A]] =
      Schema.CaseClass1[RemoteVariableReference[A], VariableReference[A]](
        typeId,
        Schema.Field(
          "ref",
          Schema[RemoteVariableReference[A]],
          get0 = _.ref,
          set0 = (a: VariableReference[A], v: RemoteVariableReference[A]) => a.copy(ref = v)
        ),
        VariableReference(_)
      )

    def schemaCase[A]: Schema.Case[Remote[A], VariableReference[Any]] =
      Schema.Case(
        "VariableReference",
        schema[Any],
        _.asInstanceOf[VariableReference[Any]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[VariableReference[_]]
      )
  }

  final case class Ignore() extends Remote[Unit] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      ZIO.succeed(
        DynamicValue.fromSchemaAndValue(Schema.primitive[Unit], ())
      )

    override protected def substituteRec(f: Remote.Substitutions): Remote[Unit] =
      this

    override private[flow] val variableUsage = VariableUsage.none
  }
  object Ignore {
    val schema: Schema[Ignore] = Schema[Unit].transform(_ => Ignore(), _ => ())

    def schemaCase[A]: Schema.Case[Remote[A], Ignore] =
      Schema.Case("Ignore", schema, _.asInstanceOf[Ignore], _.asInstanceOf[Remote[A]], _.isInstanceOf[Ignore])
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[A] =
      this

    override private[flow] val variableUsage = VariableUsage.variable(identifier)
  }

  object Variable {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Variable")

    def schema[A]: Schema[Variable[A]] =
      Schema.CaseClass1[RemoteVariableName, Variable[A]](
        typeId,
        Schema.Field(
          "identifier",
          Schema[RemoteVariableName],
          get0 = _.identifier,
          set0 = (a: Variable[A], v: RemoteVariableName) => a.copy(identifier = v)
        ),
        (identifier: RemoteVariableName) => Variable(identifier)
      )

    def schemaCase[A]: Schema.Case[Remote[A], Variable[A]] =
      Schema.Case(
        "Variable",
        schema,
        _.asInstanceOf[Variable[A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[Variable[_]]
      )
  }

  final case class Config[A](key: ConfigKey, schema: Schema[A]) extends Remote[A] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      RemoteContext
        .readConfig[A](key)(schema)
        .mapError(RemoteEvaluationError.RemoteContextError)
        .flatMap {
          case None        => ZIO.fail(RemoteEvaluationError.ConfigurationNotFound(key))
          case Some(value) => ZIO.succeed(DynamicValue.fromSchemaAndValue(schema, value))
        }

    override def equals(that: Any): Boolean =
      that match {
        case Config(otherKey, _) =>
          key == otherKey
        case _ => false
      }

    override protected def substituteRec(f: Remote.Substitutions): Remote[A] =
      this

    override private[flow] val variableUsage = VariableUsage.none
  }

  object Config {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Config")

    def schema[A]: Schema[Config[A]] =
      Schema.CaseClass2[ConfigKey, FlowSchemaAst, Config[A]](
        typeId,
        Schema.Field("key", Schema[ConfigKey], get0 = _.key, set0 = (a: Config[A], v: ConfigKey) => a.copy(key = v)),
        Schema.Field(
          "schema",
          FlowSchemaAst.schema,
          get0 = cfg => FlowSchemaAst.fromSchema(cfg.schema),
          set0 = (cfg, schema) => cfg.copy(schema = schema.toSchema)
        ),
        (key: ConfigKey, ast: FlowSchemaAst) => Config(key, ast.toSchema[A])
      )

    def schemaCase[A]: Schema.Case[Remote[A], Config[A]] =
      Schema.Case("Config", schema, _.asInstanceOf[Config[A]], _.asInstanceOf[Remote[A]], _.isInstanceOf[Config[_]])
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[A] =
      this

    override private[flow] val variableUsage = VariableUsage.binding(identifier)
  }

  object Unbound {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Unbound")

    def schema[A]: Schema[Unbound[A]] =
      Schema.CaseClass1[BindingName, Unbound[A]](
        typeId,
        Schema.Field(
          "identifier",
          Schema[BindingName],
          get0 = _.identifier,
          set0 = (a: Unbound[A], v: BindingName) => a.copy(identifier = v)
        ),
        (identifier: BindingName) => Unbound(identifier)
      )

    def schemaCase[A]: Schema.Case[Remote[A], Unbound[A]] =
      Schema.Case("Unbound", schema, _.asInstanceOf[Unbound[A]], _.asInstanceOf[Remote[A]], _.isInstanceOf[Unbound[_]])
  }

  final case class UnboundRemoteFunction[A, B] private[flow] (
    input: Unbound[A],
    result: Remote[B]
  ) extends Remote[EvaluatedRemoteFunction[A, B]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      result.evalDynamic

    def apply(a: Remote[A]): Remote[B] =
      Bind(input, a, result)

    override protected def substituteRec(f: Remote.Substitutions): Remote[EvaluatedRemoteFunction[A, B]] =
      UnboundRemoteFunction(input, result.substitute(f))

    override private[flow] val variableUsage = input.variableUsage.union(result.variableUsage)
  }

  object UnboundRemoteFunction {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.UnboundRemoteFunction")

    def make[A, B](fn: Remote[A] => Remote[B]): UnboundRemoteFunction[A, B] = {
      val input = Unbound[A](LocalContext.generateFreshBinding)
      UnboundRemoteFunction(
        input,
        fn(input)
      )
    }

    def schema[A, B]: Schema[UnboundRemoteFunction[A, B]] =
      Schema.CaseClass2[Unbound[A], Remote[B], UnboundRemoteFunction[A, B]](
        typeId,
        Schema.Field(
          "input",
          Unbound.schema[A],
          get0 = _.input,
          set0 = (a: UnboundRemoteFunction[A, B], v: Unbound[A]) => a.copy(input = v)
        ),
        Schema.Field(
          "result",
          Schema.defer(Remote.schema[B]),
          get0 = _.result,
          set0 = (a: UnboundRemoteFunction[A, B], v: Remote[B]) => a.copy(result = v)
        ),
        UnboundRemoteFunction.apply(_, _)
      )

    def schemaCase[A, B]: Schema.Case[Remote[B], UnboundRemoteFunction[A, B]] =
      Schema.Case(
        "UnboundRemoteFunction",
        schema,
        _.asInstanceOf[UnboundRemoteFunction[A, B]],
        _.asInstanceOf[Remote[B]],
        _.isInstanceOf[UnboundRemoteFunction[_, _]]
      )
  }

  type ===>[A, B] = UnboundRemoteFunction[A, B]

  final case class Bind[A, B](unbound: Unbound[A], value: Remote[A], inner: Remote[B]) extends Remote[B] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        input            <- value.evalDynamic
        boundVariableName = RemoteContext.generateFreshVariableName
        variable          = Remote.Variable(boundVariableName)
        _                <- RemoteContext.setVariable(boundVariableName, input).mapError(RemoteEvaluationError.RemoteContextError)
        _                <- LocalContext.pushBinding(unbound.identifier, variable)
        evaluated        <- inner.evalDynamic
        _                <- LocalContext.popBinding(unbound.identifier)
        resultRemote      = Remote.fromDynamic(evaluated)
        finalResult <-
          if (resultRemote.variableUsage.bindings.contains(unbound.identifier)) {
            val substituted = resultRemote.substitute(Substitutions(Map(unbound -> variable)))
            substituted.evalDynamic
          } else ZIO.succeed(evaluated)
      } yield finalResult

    override protected def substituteRec(fn: Remote.Substitutions): Remote[B] =
      Bind(
        unbound.substitute(fn).asInstanceOf[Unbound[A]],
        value.substitute(fn),
        inner.substitute(fn)
      )

    override private[flow] val variableUsage =
      unbound.variableUsage.union(value.variableUsage).union(inner.variableUsage).removeBinding(unbound.identifier)
  }

  object Bind {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Bind")

    def schema[A, B]: Schema[Bind[A, B]] =
      Schema.defer {
        Schema.CaseClass3[Unbound[A], Remote[A], Remote[B], Bind[A, B]](
          typeId,
          Schema.Field(
            "unbound",
            Unbound.schema[A],
            get0 = _.unbound,
            set0 = (a: Bind[A, B], v: Unbound[A]) => a.copy(unbound = v)
          ),
          Schema.Field(
            "value",
            Remote.schema[A],
            get0 = _.value,
            set0 = (a: Bind[A, B], v: Remote[A]) => a.copy(value = v)
          ),
          Schema.Field(
            "inner",
            Remote.schema[B],
            get0 = _.inner,
            set0 = (a: Bind[A, B], v: Remote[B]) => a.copy(inner = v)
          ),
          Bind.apply
        )
      }

    def schemaCase[A, B]: Schema.Case[Remote[B], Bind[A, B]] =
      Schema.Case(
        "Bind",
        schema,
        _.asInstanceOf[Bind[A, B]],
        _.asInstanceOf[Remote[B]],
        _.isInstanceOf[Bind[_, _]]
      )
  }

  final case class Unary[In, Out](
    value: Remote[In],
    operator: UnaryOperators[In, Out]
  ) extends Remote[Out] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        v      <- value.eval(operator.inputSchema)
        result <- ZIO.attempt(operator(v)).mapError(RemoteEvaluationError.EvaluationException)
      } yield DynamicValue.fromSchemaAndValue(operator.outputSchema, result)

    override protected def substituteRec(f: Remote.Substitutions): Remote[Out] =
      Unary(value.substitute(f), operator)

    override private[flow] val variableUsage = value.variableUsage
  }

  object Unary {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Unary")

    def schema[In, Out]: Schema[Unary[In, Out]] =
      Schema.CaseClass2[Remote[In], UnaryOperators[In, Out], Unary[In, Out]](
        typeId,
        Schema.Field(
          "value",
          Schema.defer(Remote.schema[In]),
          get0 = _.value,
          set0 = (a: Unary[In, Out], v: Remote[In]) => a.copy(value = v)
        ),
        Schema.Field(
          "operator",
          UnaryOperators.schema[In, Out],
          get0 = _.operator,
          set0 = (a: Unary[In, Out], v: UnaryOperators[In, Out]) => a.copy(operator = v)
        ),
        Unary.apply
      )

    def schemaCase[In, Out]: Schema.Case[Remote[Out], Unary[In, Out]] =
      Schema.Case(
        "Unary",
        schema,
        _.asInstanceOf[Unary[In, Out]],
        _.asInstanceOf[Remote[Out]],
        _.isInstanceOf[Unary[_, _]]
      )
  }

  final case class Binary[In1, In2, Out](
    left: Remote[In1],
    right: Remote[In2],
    operator: BinaryOperators[In1, In2, Out]
  ) extends Remote[Out] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        l      <- left.eval(operator.inputSchema1)
        r      <- right.eval(operator.inputSchema2)
        result <- ZIO.attempt(operator(l, r)).mapError(RemoteEvaluationError.EvaluationException)
      } yield DynamicValue.fromSchemaAndValue(operator.outputSchema, result)

    override protected def substituteRec(f: Remote.Substitutions): Remote[Out] =
      Binary(left.substitute(f), right.substitute(f), operator)

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object Binary {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Binary")

    def schema[In1, In2, Out]: Schema[Binary[In1, In2, Out]] =
      Schema.CaseClass3[Remote[In1], Remote[In2], BinaryOperators[In1, In2, Out], Binary[In1, In2, Out]](
        typeId,
        Schema.Field(
          "left",
          Schema.defer(Remote.schema[In1]),
          get0 = _.left,
          set0 = (a: Binary[In1, In2, Out], v: Remote[In1]) => a.copy(left = v)
        ),
        Schema.Field(
          "right",
          Schema.defer(Remote.schema[In2]),
          get0 = _.right,
          set0 = (a: Binary[In1, In2, Out], v: Remote[In2]) => a.copy(right = v)
        ),
        Schema.Field(
          "operator",
          BinaryOperators.schema[In1, In2, Out],
          get0 = _.operator,
          set0 = (a: Binary[In1, In2, Out], v: BinaryOperators[In1, In2, Out]) => a.copy(operator = v)
        ),
        Binary.apply
      )

    def schemaCase[In1, In2, Out]: Schema.Case[Remote[Out], Binary[In1, In2, Out]] =
      Schema.Case(
        "Binary",
        schema,
        _.asInstanceOf[Binary[In1, In2, Out]],
        _.asInstanceOf[Remote[Out]],
        _.isInstanceOf[Binary[_, _, _]]
      )
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[Either[A, B]] =
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

    def schemaCase[A]: Schema.Case[Remote[A], RemoteEither[Any, Any]] =
      Schema.Case(
        "RemoteEither",
        schema[Any, Any],
        _.asInstanceOf[RemoteEither[Any, Any]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[RemoteEither[_, _]]
      )
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[C] =
      FoldEither(
        either.substitute(f),
        left.substitute(f).asInstanceOf[UnboundRemoteFunction[A, C]],
        right.substitute(f).asInstanceOf[UnboundRemoteFunction[B, C]]
      )

    override private[flow] val variableUsage = either.variableUsage.union(left.variableUsage).union(right.variableUsage)
  }

  object FoldEither {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.FoldEither")

    def schema[A, B, C]: Schema[FoldEither[A, B, C]] =
      Schema.CaseClass3[
        Remote[Either[A, B]],
        UnboundRemoteFunction[A, C],
        UnboundRemoteFunction[B, C],
        FoldEither[A, B, C]
      ](
        typeId,
        Schema.Field(
          "either",
          Schema.defer(Remote.schema[Either[A, B]]),
          get0 = _.either,
          set0 = (a: FoldEither[A, B, C], v: Remote[Either[A, B]]) => a.copy(either = v)
        ),
        Schema.Field(
          "left",
          UnboundRemoteFunction.schema[A, C],
          get0 = _.left,
          set0 = (a: FoldEither[A, B, C], v: UnboundRemoteFunction[A, C]) => a.copy(left = v)
        ),
        Schema.Field(
          "right",
          UnboundRemoteFunction.schema[B, C],
          get0 = _.right,
          set0 = (a: FoldEither[A, B, C], v: UnboundRemoteFunction[B, C]) => a.copy(right = v)
        ),
        { case (either, left, right) =>
          FoldEither(
            either,
            left,
            right
          )
        }
      )

    def schemaCase[A, B, C]: Schema.Case[Remote[C], FoldEither[A, B, C]] =
      Schema.Case(
        "FoldEither",
        schema[A, B, C],
        _.asInstanceOf[FoldEither[A, B, C]],
        _.asInstanceOf[Remote[C]],
        _.isInstanceOf[FoldEither[_, _, _]]
      )
  }

  final case class Try[A](either: Either[Remote[Throwable], Remote[A]]) extends Remote[scala.util.Try[A]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      either match {
        case Left(throwable) =>
          throwable.evalDynamic.map { throwableValue =>
            DynamicValue.Enumeration(
              Try.tryTypeId,
              "Failure" -> DynamicValue.Record(Try.failureTypeId, ListMap("exception" -> throwableValue))
            )
          }
        case Right(success) =>
          success.evalDynamic.map { successValue =>
            DynamicValue.Enumeration(
              Try.tryTypeId,
              "Success" -> DynamicValue.Record(Try.successTypeId, ListMap("value" -> successValue))
            )
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[scala.util.Try[A]] =
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
    private val tryTypeId: TypeId     = TypeId.parse("scala.util.Try")
    private val failureTypeId: TypeId = TypeId.parse("scala.util.Failure")
    private val successTypeId: TypeId = TypeId.parse("scala.util.Success")

    def schema[A]: Schema[Try[A]] =
      Schema.defer(
        Schema
          .either(Remote.schema[Throwable], Remote.schema[A])
          .transform(
            Try.apply,
            _.either
          )
      )

    def schemaCase[A]: Schema.Case[Remote[A], Try[Any]] =
      Schema.Case("Try", schema[Any], _.asInstanceOf[Try[Any]], _.asInstanceOf[Remote[A]], _.isInstanceOf[Try[_]])
  }

  // beginning of generated tuple constructors
  final case class Tuple2[T1, T2](t1: Remote[T1], t2: Remote[T2])
      extends Remote[(T1, T2)]
      with RemoteTuple2.Construct[T1, T2] {
    override protected def substituteRec(f: Remote.Substitutions): Remote[(T1, T2)] =
      Tuple2(t1.substitute(f), t2.substitute(f))
    override private[flow] val variableUsage = VariableUsage.none.union(t1.variableUsage).union(t2.variableUsage)
  }

  object Tuple2 extends RemoteTuple2.ConstructStatic[Tuple2] {
    def construct[T1, T2](t1: Remote[T1], t2: Remote[T2]): Tuple2[T1, T2] = Tuple2(t1, t2)
    def checkInstance[A](remote: Remote[A]): Boolean                      = remote.isInstanceOf[Tuple2[_, _]]
  }

  final case class Tuple3[T1, T2, T3](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3])
      extends Remote[(T1, T2, T3)]
      with RemoteTuple3.Construct[T1, T2, T3] {
    override protected def substituteRec(f: Remote.Substitutions): Remote[(T1, T2, T3)] =
      Tuple3(t1.substitute(f), t2.substitute(f), t3.substitute(f))
    override private[flow] val variableUsage =
      VariableUsage.none.union(t1.variableUsage).union(t2.variableUsage).union(t3.variableUsage)
  }

  object Tuple3 extends RemoteTuple3.ConstructStatic[Tuple3] {
    def construct[T1, T2, T3](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3]): Tuple3[T1, T2, T3] = Tuple3(t1, t2, t3)
    def checkInstance[A](remote: Remote[A]): Boolean                                              = remote.isInstanceOf[Tuple3[_, _, _]]
  }

  final case class Tuple4[T1, T2, T3, T4](t1: Remote[T1], t2: Remote[T2], t3: Remote[T3], t4: Remote[T4])
      extends Remote[(T1, T2, T3, T4)]
      with RemoteTuple4.Construct[T1, T2, T3, T4] {
    override protected def substituteRec(f: Remote.Substitutions): Remote[(T1, T2, T3, T4)] =
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple4[_, _, _, _]]
  }

  final case class Tuple5[T1, T2, T3, T4, T5](
    t1: Remote[T1],
    t2: Remote[T2],
    t3: Remote[T3],
    t4: Remote[T4],
    t5: Remote[T5]
  ) extends Remote[(T1, T2, T3, T4, T5)]
      with RemoteTuple5.Construct[T1, T2, T3, T4, T5] {
    override protected def substituteRec(f: Remote.Substitutions): Remote[(T1, T2, T3, T4, T5)] =
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple5[_, _, _, _, _]]
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
    override protected def substituteRec(f: Remote.Substitutions): Remote[(T1, T2, T3, T4, T5, T6)] =
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple6[_, _, _, _, _, _]]
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
    override protected def substituteRec(f: Remote.Substitutions): Remote[(T1, T2, T3, T4, T5, T6, T7)] = Tuple7(
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple7[_, _, _, _, _, _, _]]
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
    override protected def substituteRec(f: Remote.Substitutions): Remote[(T1, T2, T3, T4, T5, T6, T7, T8)] = Tuple8(
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple8[_, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(f: Remote.Substitutions): Remote[(T1, T2, T3, T4, T5, T6, T7, T8, T9)] =
      Tuple9(
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple9[_, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple10[_, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple11[_, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean = remote.isInstanceOf[Tuple12[_, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple13[_, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple14[_, _, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple15[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple16[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple17[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple18[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple19[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple20[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple21[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]
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
    override protected def substituteRec(
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
    def checkInstance[A](remote: Remote[A]): Boolean =
      remote.isInstanceOf[Tuple22[_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _]]
  }
  // end of generated tuple constructors

  final case class TupleAccess[T, A](tuple: Remote[T], n: Int, arity: Int) extends Remote[A] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        dynTuple <- tuple.evalDynamic
        value <- ZIO
                   .fromEither(TupleAccess.findValueIn(dynTuple, n, arity))
                   .mapError(RemoteEvaluationError.UnexpectedDynamicValue)
      } yield value

    override protected def substituteRec(f: Remote.Substitutions): Remote[A] =
      TupleAccess(tuple.substitute(f), n, arity)

    override private[flow] val variableUsage = tuple.variableUsage
  }

  object TupleAccess {
    private[flow] def findValueIn(value: DynamicValue, n: Int, arity: Int): Either[String, DynamicValue] = {
      @tailrec
      def find(value: DynamicValue, current: Int): Option[DynamicValue] =
        value match {
          case DynamicValue.Tuple(left, right) =>
            if (n == current) {
              if (current == 0)
                Some(value)
              else
                Some(right)
            } else {
              find(left, current - 1)
            }
          case _ =>
            if (n == current)
              Some(value)
            else None
        }

      find(value, arity - 1).toRight(s"Cannot find value for index $n in dynamic tuple")
    }

    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.TupleAccess")

    def schema[T, A]: Schema[TupleAccess[T, A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[T], Int, Int, TupleAccess[T, A]](
          typeId,
          Schema.Field("tuple", Remote.schema[T], get0 = _.tuple, set0 = (o, v) => o.copy(tuple = v)),
          Schema.Field("n", Schema[Int], get0 = _.n, set0 = (o, v) => o.copy(n = v)),
          Schema.Field("arity", Schema[Int], get0 = _.arity, set0 = (o, v) => o.copy(arity = v)),
          TupleAccess(_, _, _)
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], TupleAccess[Any, A]] =
      Schema.Case(
        "TupleAccess",
        schema[Any, A],
        _.asInstanceOf[TupleAccess[Any, A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[TupleAccess[_, _]]
      )
  }

  final case class Branch[A](predicate: Remote[Boolean], ifTrue: Remote[A], ifFalse: Remote[A]) extends Remote[A] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      predicate.eval.flatMap {
        case false => ifFalse.evalDynamic
        case true  => ifTrue.evalDynamic
      }

    override protected def substituteRec(f: Remote.Substitutions): Remote[A] =
      Branch(
        predicate.substitute(f),
        ifTrue.substitute(f),
        ifFalse.substitute(f)
      )

    override private[flow] val variableUsage =
      predicate.variableUsage.union(ifTrue.variableUsage).union(ifFalse.variableUsage)
  }

  object Branch {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Branch")

    def schema[A]: Schema[Branch[A]] =
      Schema.defer(
        Schema.CaseClass3[Remote[Boolean], Remote[A], Remote[A], Branch[A]](
          typeId,
          Schema.Field("predicate", Remote.schema[Boolean], get0 = _.predicate, set0 = (o, v) => o.copy(predicate = v)),
          Schema.Field("ifTrue", Remote.schema[A], get0 = _.ifTrue, set0 = (o, v) => o.copy(ifTrue = v)),
          Schema.Field("ifFalse", Remote.schema[A], get0 = _.ifFalse, set0 = (o, v) => o.copy(ifFalse = v)),
          { case (predicate, ifTrue, ifFalse) => Branch(predicate, ifTrue, ifFalse) }
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], Branch[A]] =
      Schema.Case("Branch", schema[A], _.asInstanceOf[Branch[A]], _.asInstanceOf[Remote[A]], _.isInstanceOf[Branch[_]])
  }

  case class StringToCharList(remoteString: Remote[String]) extends Remote[List[Char]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      remoteString.eval.map { value =>
        DynamicValue.fromSchemaAndValue(Schema[List[Char]], value.toList)
      }

    protected def substituteRec(f: Remote.Substitutions): Remote[List[Char]] =
      StringToCharList(remoteString.substitute(f))

    private[flow] val variableUsage: VariableUsage =
      remoteString.variableUsage
  }

  object StringToCharList {
    val schema: Schema[StringToCharList] = Schema.defer(
      Remote
        .schema[String]
        .transform(
          StringToCharList.apply,
          _.remoteString
        )
    )

    def schemaCase[A]: Schema.Case[Remote[A], StringToCharList] =
      Schema.Case(
        "StringToCharList",
        schema,
        _.asInstanceOf[StringToCharList],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[StringToCharList]
      )
  }

  case class CharListToString(remoteString: Remote[List[Char]]) extends Remote[String] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      remoteString.eval.map { value =>
        DynamicValue.fromSchemaAndValue(Schema[String], value.mkString)
      }

    protected def substituteRec(f: Remote.Substitutions): Remote[String] =
      CharListToString(remoteString.substitute(f))

    override private[flow] val variableUsage: VariableUsage =
      remoteString.variableUsage
  }

  object CharListToString {
    val schema: Schema[CharListToString] = Schema.defer(
      Remote
        .schema[List[Char]]
        .transform(
          CharListToString.apply,
          _.remoteString
        )
    )

    def schemaCase[A]: Schema.Case[Remote[A], CharListToString] =
      Schema.Case(
        "CharListToString",
        schema,
        _.asInstanceOf[CharListToString],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[CharListToString]
      )
  }

  final case class Equal[A](left: Remote[A], right: Remote[A]) extends Remote[Boolean] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        leftDyn  <- left.evalDynamic
        rightDyn <- right.evalDynamic
        result    = leftDyn == rightDyn
      } yield DynamicValue.fromSchemaAndValue(Schema[Boolean], result)

    override protected def substituteRec(f: Remote.Substitutions): Remote[Boolean] =
      Equal(left.substitute(f), right.substitute(f))

    override private[flow] val variableUsage = left.variableUsage.union(right.variableUsage)
  }

  object Equal {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Equal")

    def schema[A]: Schema[Equal[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[A], Remote[A], Equal[A]](
          typeId,
          Schema.Field("left", Remote.schema[A], get0 = _.left, set0 = (o, v) => o.copy(left = v)),
          Schema.Field("right", Remote.schema[A], get0 = _.right, set0 = (o, v) => o.copy(right = v)),
          { case (left, right) => Equal(left, right) }
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], Equal[Any]] =
      Schema.Case("Equal", schema[Any], _.asInstanceOf[Equal[Any]], _.asInstanceOf[Remote[A]], _.isInstanceOf[Equal[_]])
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[B] =
      Fold(
        list.substitute(f),
        initial.substitute(f),
        body.substitute(f).asInstanceOf[UnboundRemoteFunction[(B, A), B]]
      )

    override private[flow] val variableUsage = list.variableUsage.union(initial.variableUsage).union(body.variableUsage)
  }

  object Fold {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Fold")

    def schema[A, B]: Schema[Fold[A, B]] =
      Schema.defer(
        Schema.CaseClass3[Remote[List[A]], Remote[B], UnboundRemoteFunction[(B, A), B], Fold[A, B]](
          typeId,
          Schema.Field("list", Remote.schema[List[A]], get0 = _.list, set0 = (o, v) => o.copy(list = v)),
          Schema.Field("initial", Remote.schema[B], get0 = _.initial, set0 = (o, v) => o.copy(initial = v)),
          Schema.Field(
            "body",
            UnboundRemoteFunction.schema[(B, A), B],
            get0 = _.body,
            set0 = (o, v) => o.copy(body = v)
          ),
          { case (list, initial, body) => Fold(list, initial, body) }
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], Fold[Any, A]] =
      Schema.Case(
        "Fold",
        schema[Any, A],
        _.asInstanceOf[Fold[Any, A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[Fold[_, _]]
      )
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[List[A]] =
      Cons(list.substitute(f), head.substitute(f))

    override private[flow] val variableUsage = list.variableUsage.union(head.variableUsage)
  }

  object Cons {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Cons")

    def schema[A]: Schema[Cons[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[List[A]], Remote[A], Cons[A]](
          typeId,
          Schema.Field("list", Remote.schema[List[A]], get0 = _.list, set0 = (o, v) => o.copy(list = v)),
          Schema.Field("head", Remote.schema[A], get0 = _.head, set0 = (o, v) => o.copy(head = v)),
          Cons.apply
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], Cons[A]] =
      Schema.Case("Cons", schema[A], _.asInstanceOf[Cons[A]], _.asInstanceOf[Remote[A]], _.isInstanceOf[Cons[_]])
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[Option[(A, List[A])]] =
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

    def schemaCase[A]: Schema.Case[Remote[A], UnCons[A]] =
      Schema.Case("UnCons", schema[A], _.asInstanceOf[UnCons[A]], _.asInstanceOf[Remote[A]], _.isInstanceOf[UnCons[_]])
  }

  final case class DurationFromAmount(amount: Remote[Long], temporalUnit: Remote[ChronoUnit]) extends Remote[Duration] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        amount       <- amount.eval[Long]
        temporalUnit <- temporalUnit.eval[ChronoUnit]
        result        = java.time.Duration.of(amount, temporalUnit)
      } yield DynamicValueHelpers.of(result)

    override protected def substituteRec(f: Remote.Substitutions): Remote[Duration] =
      DurationFromAmount(amount.substitute(f), temporalUnit.substitute(f))

    override private[flow] val variableUsage = amount.variableUsage.union(temporalUnit.variableUsage)
  }

  object DurationFromAmount {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.DurationFromAmount")

    val schema: Schema[DurationFromAmount] =
      Schema.defer(
        Schema.CaseClass2[Remote[Long], Remote[ChronoUnit], DurationFromAmount](
          typeId,
          Schema.Field("amount", Remote.schema[Long], get0 = _.amount, set0 = (o, v) => o.copy(amount = v)),
          Schema.Field(
            "temporalUnit",
            Remote.schema[ChronoUnit],
            get0 = _.temporalUnit,
            set0 = (o, v) => o.copy(temporalUnit = v)
          ),
          DurationFromAmount.apply
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], DurationFromAmount] =
      Schema.Case(
        "DurationFromAmount",
        schema,
        _.asInstanceOf[DurationFromAmount],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[DurationFromAmount]
      )
  }

  final case class Lazy[A](value: () => Remote[A]) extends Remote[A] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      value().evalDynamic

    override protected def substituteRec(f: Remote.Substitutions): Remote[A] =
      Lazy(() => value().substitute(f))

    override private[flow] lazy val variableUsage = value().variableUsage
  }

  object Lazy {
    def schema[A]: Schema[Lazy[A]] =
      Schema.defer(Remote.schema[A].transform((a: Remote[A]) => Lazy(() => a), _.value()))

    def schemaCase[A]: Schema.Case[Remote[A], Lazy[A]] =
      Schema.Case("Lazy", schema, _.asInstanceOf[Lazy[A]], _.asInstanceOf[Remote[A]], _.isInstanceOf[Lazy[_]])
  }

  final case class RemoteSome[A](value: Remote[A]) extends Remote[Option[A]] {

    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        dyn <- value.evalDynamic
      } yield DynamicValue.SomeValue(dyn)

    override protected def substituteRec(f: Remote.Substitutions): Remote[Option[A]] =
      RemoteSome(value.substitute(f))

    override private[flow] val variableUsage = value.variableUsage
  }

  object RemoteSome {
    def schema[A]: Schema[RemoteSome[A]] =
      Schema.defer(Remote.schema[A].transform(RemoteSome(_), _.value))

    def schemaCase[A]: Schema.Case[Remote[A], RemoteSome[A]] =
      Schema.Case(
        "RemoteSome",
        schema,
        _.asInstanceOf[RemoteSome[A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[RemoteSome[_]]
      )
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

    override protected def substituteRec(f: Remote.Substitutions): Remote[B] =
      FoldOption(
        option.substitute(f),
        ifEmpty.substitute(f),
        ifNonEmpty.substitute(f).asInstanceOf[UnboundRemoteFunction[A, B]]
      )

    override private[flow] val variableUsage =
      option.variableUsage.union(ifEmpty.variableUsage).union(ifNonEmpty.variableUsage)
  }

  object FoldOption {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.FoldOption")

    def schema[A, B]: Schema[FoldOption[A, B]] =
      Schema.defer(
        Schema.CaseClass3[Remote[Option[A]], Remote[B], UnboundRemoteFunction[A, B], FoldOption[A, B]](
          typeId,
          Schema.Field("option", Remote.schema[Option[A]], get0 = _.option, set0 = (o, v) => o.copy(option = v)),
          Schema.Field("ifEmpty", Remote.schema[B], get0 = _.ifEmpty, set0 = (o, v) => o.copy(ifEmpty = v)),
          Schema.Field(
            "ifNonEmpty",
            UnboundRemoteFunction.schema[A, B],
            get0 = _.ifNonEmpty,
            set0 = (o, v) => o.copy(ifNonEmpty = v)
          ),
          FoldOption.apply
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], FoldOption[Any, A]] =
      Schema.Case(
        "FoldOption",
        schema,
        _.asInstanceOf[FoldOption[Any, A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[FoldOption[_, _]]
      )
  }

  final case class Recurse[A, B](
    id: RecursionId,
    initial: Remote[A],
    body: UnboundRemoteFunction[A, B]
  ) extends Remote[B] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        _ <- RemoteContext
               .setVariable(
                 id.toRemoteVariableName,
                 DynamicValue.fromSchemaAndValue(UnboundRemoteFunction.schema[A, B], body)
               )
               .mapError(RemoteEvaluationError.RemoteContextError)
        result <- body(initial).evalDynamic
      } yield result

    override private[flow] def variableUsage =
      initial.variableUsage.union(body.variableUsage)

    override protected def substituteRec(f: Substitutions): Remote[B] =
      Recurse(
        id,
        initial.substitute(f),
        body.substitute(f).asInstanceOf[UnboundRemoteFunction[A, B]]
      )
  }

  object Recurse {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.Recurse")

    def schema[A, B]: Schema[Recurse[A, B]] =
      Schema.defer {
        Schema
          .CaseClass3[RecursionId, Remote[A], UnboundRemoteFunction[A, B], Recurse[A, B]](
            typeId,
            Schema.Field("id", Schema[RecursionId], get0 = _.id, set0 = (o, v) => o.copy(id = v)),
            Schema.Field("initial", Remote.schema[A], get0 = _.initial, set0 = (o, v) => o.copy(initial = v)),
            Schema.Field("body", UnboundRemoteFunction.schema[A, B], get0 = _.body, set0 = (o, v) => o.copy(body = v)),
            Recurse(_, _, _)
          )
      }

    def schemaCase[A, B]: Schema.Case[Remote[B], Recurse[A, B]] =
      Schema.Case(
        "Recurse",
        schema,
        _.asInstanceOf[Recurse[A, B]],
        _.asInstanceOf[Remote[B]],
        _.isInstanceOf[Recurse[_, _]]
      )
  }

  final case class RecurseWith[A, B](id: RecursionId, value: Remote[A]) extends Remote[B] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      RemoteContext
        .getVariable(id.toRemoteVariableName)
        .mapError(RemoteEvaluationError.RemoteContextError)
        .flatMap {
          case None =>
            ZIO.fail(RemoteEvaluationError.RecursionNotFound(id))
          case Some(dynamicBody) =>
            ZIO
              .fromEither(dynamicBody.toTypedValue(UnboundRemoteFunction.schema[A, B]))
              .mapError(RemoteEvaluationError.TypeError)
              .flatMap { body =>
                body(value).evalDynamic
              }
        }

    override private[flow] def variableUsage =
      value.variableUsage.union(VariableUsage.variable(id.toRemoteVariableName))

    override protected def substituteRec(f: Substitutions): Remote[B] =
      RecurseWith(id, value.substitute(f))
  }

  object RecurseWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.RecurseWith")

    def schema[A, B]: Schema[RecurseWith[A, B]] =
      Schema.defer {
        Schema.CaseClass2[RecursionId, Remote[A], RecurseWith[A, B]](
          typeId,
          Schema.Field("id", Schema[RecursionId], get0 = _.id, set0 = (o, v) => o.copy(id = v)),
          Schema.Field("value", Remote.schema[A], get0 = _.value, set0 = (o, v) => o.copy(value = v)),
          RecurseWith(_, _)
        )
      }

    def schemaCase[A, B]: Schema.Case[Remote[B], RecurseWith[A, B]] =
      Schema.Case(
        "RecurseWith",
        schema,
        _.asInstanceOf[RecurseWith[A, B]],
        _.asInstanceOf[Remote[B]],
        _.isInstanceOf[RecurseWith[_, _]]
      )
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

    override protected def substituteRec(f: Substitutions): Remote[Set[A]] =
      ListToSet(list.substitute(f))
  }

  object ListToSet {
    def schema[A]: Schema[ListToSet[A]] =
      Schema.defer(Remote.schema[List[A]].transform(ListToSet(_), _.list))

    def schemaCase[A]: Schema.Case[Remote[A], ListToSet[A]] =
      Schema.Case(
        "ListToSet",
        schema,
        _.asInstanceOf[ListToSet[A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[ListToSet[_]]
      )
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

    override protected def substituteRec(f: Substitutions): Remote[List[A]] =
      SetToList(set.substitute(f))
  }

  object SetToList {
    def schema[A]: Schema[SetToList[A]] =
      Schema.defer(Remote.schema[Set[A]].transform(SetToList(_), _.set))

    def schemaCase[A]: Schema.Case[Remote[A], SetToList[A]] =
      Schema.Case(
        "SetToList",
        schema,
        _.asInstanceOf[SetToList[A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[SetToList[_]]
      )
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

    override protected def substituteRec(f: Substitutions): Remote[String] =
      ListToString(list.substitute(f), start.substitute(f), sep.substitute(f), end.substitute(f))
  }

  object ListToString {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.ListToString")

    val schema: Schema[ListToString] =
      Schema.defer(
        Schema.CaseClass4[Remote[List[String]], Remote[String], Remote[String], Remote[String], ListToString](
          typeId,
          Schema.Field("list", Remote.schema[List[String]], get0 = _.list, set0 = (o, v) => o.copy(list = v)),
          Schema.Field("start", Remote.schema[String], get0 = _.start, set0 = (o, v) => o.copy(start = v)),
          Schema.Field("sep", Remote.schema[String], get0 = _.sep, set0 = (o, v) => o.copy(sep = v)),
          Schema.Field("end", Remote.schema[String], get0 = _.end, set0 = (o, v) => o.copy(end = v)),
          ListToString(_, _, _, _)
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], ListToString] =
      Schema.Case(
        "ListToString",
        schema,
        _.asInstanceOf[ListToString],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[ListToString]
      )
  }

  final case class ListToMap[K, V](list: Remote[List[(K, V)]]) extends Remote[Map[K, V]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      list.evalDynamic.flatMap {
        case DynamicValue.Sequence(values) =>
          ZIO
            .foreach(values) {
              case DynamicValue.Tuple(k, v) => ZIO.succeed((k, v))
              case other: DynamicValue =>
                ZIO.fail(
                  RemoteEvaluationError.UnexpectedDynamicValue(
                    s"Unexpected element value in Remote.ListToMap of type ${other.getClass.getSimpleName}"
                  )
                )
            }
            .map(DynamicValue.Dictionary)
        case other: DynamicValue =>
          ZIO.fail(
            RemoteEvaluationError.UnexpectedDynamicValue(
              s"Unexpected value in Remote.ListToMap of type ${other.getClass.getSimpleName}"
            )
          )
      }

    override private[flow] def variableUsage: VariableUsage =
      list.variableUsage

    override protected def substituteRec(f: Substitutions): Remote[Map[K, V]] =
      ListToMap(list.substitute(f))
  }

  object ListToMap {
    def schema[K, V]: Schema[ListToMap[K, V]] =
      Schema.defer(Remote.schema[List[(K, V)]].transform(ListToMap(_), _.list))

    def schemaCase[A]: Schema.Case[Remote[A], ListToMap[Any, Any]] =
      Schema.Case(
        "ListToMap",
        schema,
        _.asInstanceOf[ListToMap[Any, Any]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[ListToMap[_, _]]
      )
  }

  final case class MapToList[K, V](set: Remote[Map[K, V]]) extends Remote[List[(K, V)]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      set.evalDynamic.flatMap {
        case DynamicValue.Dictionary(values) =>
          ZIO.succeed(DynamicValue.Sequence(values.map { case (k, v) =>
            DynamicValue.Tuple(k, v)
          }))
        case other: DynamicValue =>
          ZIO.fail(
            RemoteEvaluationError.UnexpectedDynamicValue(
              s"Unexpected value in Remote.MapToList of type ${other.getClass.getSimpleName}"
            )
          )
      }

    override private[flow] def variableUsage: VariableUsage =
      set.variableUsage

    override protected def substituteRec(f: Substitutions): Remote[List[(K, V)]] =
      MapToList(set.substitute(f))
  }

  object MapToList {
    def schema[K, V]: Schema[MapToList[K, V]] =
      Schema.defer(Remote.schema[Map[K, V]].transform(MapToList(_), _.set))

    def schemaCase[A]: Schema.Case[Remote[A], MapToList[Any, Any]] =
      Schema.Case(
        "MapToList",
        schema,
        _.asInstanceOf[MapToList[Any, Any]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[MapToList[_, _]]
      )
  }

  final case class OpticGet[S, A, R](optic: RemoteOptic[S, A], value: Remote[S]) extends Remote[R] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      value.evalDynamic.flatMap { dynValue =>
        optic match {
          case RemoteOptic.Lens(fieldName) =>
            dynValue match {
              case DynamicValue.Record(_, fields) =>
                fields.get(fieldName) match {
                  case Some(value) => ZIO.succeed(value)
                  case None        => ZIO.fail(RemoteEvaluationError.FieldNotFound(s"Could not find $fieldName in record"))
                }

              case _ =>
                ZIO.fail(
                  RemoteEvaluationError.UnexpectedDynamicValue(
                    s"Unexpected value in Remote.OpticGet(Lens) of type ${dynValue.getClass.getSimpleName}"
                  )
                )
            }

          case RemoteOptic.Prism(_, termName) =>
            dynValue match {
              case DynamicValue.Enumeration(_, (id, value)) =>
                if (id == termName)
                  ZIO.succeed(DynamicValue.SomeValue(value))
                else
                  ZIO.succeed(DynamicValue.NoneValue)

              case _ =>
                ZIO.fail(
                  RemoteEvaluationError.UnexpectedDynamicValue(
                    s"Unexpected value in Remote.OpticGet(Prism) of type ${dynValue.getClass.getSimpleName}"
                  )
                )
            }

          case RemoteOptic.Traversal() =>
            dynValue match {
              case DynamicValue.Sequence(_) =>
                ZIO.succeed(dynValue)
              case DynamicValue.Dictionary(entries) =>
                ZIO.succeed(
                  DynamicValue.Sequence(
                    entries.map { case (k, v) => DynamicValue.Tuple(k, v) }
                  )
                )
              case DynamicValue.SetValue(values) =>
                ZIO.succeed(DynamicValue.Sequence(Chunk.fromIterable(values)))
              case _ =>
                ZIO.fail(
                  RemoteEvaluationError.UnexpectedDynamicValue(
                    s"Unexpected value in Remote.OpticGet(Traversal) of type ${dynValue.getClass.getSimpleName}"
                  )
                )
            }
        }
      }

    override private[flow] def variableUsage =
      value.variableUsage

    override protected def substituteRec(f: Substitutions): Remote[R] =
      OpticGet(optic, value.substitute(f))
  }

  object OpticGet {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.OpticGet")

    def schema[S, A, R]: Schema[OpticGet[S, A, R]] =
      Schema.defer(
        Schema.CaseClass2[RemoteOptic[S, A], Remote[S], OpticGet[S, A, R]](
          typeId,
          Schema.Field("optic", RemoteOptic.schema[S, A], get0 = _.optic, set0 = (o, v) => o.copy(optic = v)),
          Schema.Field("value", Remote.schema[S], get0 = _.value, set0 = (o, v) => o.copy(value = v)),
          OpticGet(_, _)
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], OpticGet[Any, Any, A]] =
      Schema.Case(
        "OpticGet",
        schema,
        _.asInstanceOf[OpticGet[Any, Any, A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[OpticGet[_, _, _]]
      )
  }

  final case class OpticSet[S, A, V, R](optic: RemoteOptic[S, A], on: Remote[S], value: Remote[V]) extends Remote[R] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      optic match {
        case RemoteOptic.Lens(fieldName) =>
          for {
            dynRecord <- on.evalDynamic
            dynValue  <- value.evalDynamic
            result <- dynRecord match {
                        case DynamicValue.Record(id, fields) =>
                          if (fields.contains(fieldName)) {
                            ZIO.succeed(DynamicValue.Record(id, fields.updated(fieldName, dynValue)))
                          } else {
                            ZIO.fail(RemoteEvaluationError.FieldNotFound(s"Could not find $fieldName in record"))
                          }
                        case _ =>
                          ZIO.fail(
                            RemoteEvaluationError.UnexpectedDynamicValue(
                              s"Unexpected value in Remote.OpticSet(Lens) of type ${dynValue.getClass.getSimpleName}"
                            )
                          )
                      }
          } yield result

        case RemoteOptic.Prism(sumTypeId, termName) =>
          value.evalDynamic.map { dynValue =>
            DynamicValue.Enumeration(sumTypeId, (termName, dynValue))
          }

        case RemoteOptic.Traversal() =>
          for {
            dynRecord <- on.evalDynamic
            dynValue  <- value.evalDynamic
            dynSequence <- dynValue match {
                             case seq @ DynamicValue.Sequence(_) =>
                               ZIO.succeed(seq)
                             case _ =>
                               ZIO.fail(
                                 RemoteEvaluationError.UnexpectedDynamicValue(
                                   s"Unexpected target value in Remote.OpticsSet(Traversal) of type ${dynValue.getClass.getSimpleName}"
                                 )
                               )
                           }
            result <- dynRecord match {
                        case DynamicValue.Sequence(_) =>
                          ZIO.succeed(dynSequence)
                        case DynamicValue.Dictionary(_) =>
                          ZIO
                            .foreach(dynSequence.values) {
                              case DynamicValue.Tuple(key, value) =>
                                ZIO.succeed((key, value))
                              case dynItem =>
                                ZIO.fail(
                                  RemoteEvaluationError.UnexpectedDynamicValue(
                                    s"Unexpected item value in Remote.OpticsSet(Traversal) of type ${dynItem.getClass.getSimpleName}"
                                  )
                                )
                            }
                            .map { pairs =>
                              DynamicValue.Dictionary(pairs)
                            }
                        case DynamicValue.SetValue(_) =>
                          ZIO.succeed(DynamicValue.SetValue(dynSequence.values.toSet))
                        case _ =>
                          ZIO.fail(
                            RemoteEvaluationError.UnexpectedDynamicValue(
                              s"Unexpected source value in Remote.OpticSet(Traversal) of type ${dynValue.getClass.getSimpleName}"
                            )
                          )
                      }
          } yield result
      }

    override private[flow] def variableUsage =
      value.variableUsage.union(on.variableUsage)

    override protected def substituteRec(f: Substitutions): Remote[R] =
      OpticSet(optic, on.substitute(f), value.substitute(f))
  }

  object OpticSet {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.OpticSet")

    def schema[S, A, V, R]: Schema[OpticSet[S, A, V, R]] =
      Schema.defer(
        Schema.CaseClass3[RemoteOptic[S, A], Remote[S], Remote[V], OpticSet[S, A, V, R]](
          typeId,
          Schema.Field("optic", RemoteOptic.schema[S, A], get0 = _.optic, set0 = (o, v) => o.copy(optic = v)),
          Schema.Field("on", Remote.schema[S], get0 = _.on, set0 = (o, v) => o.copy(on = v)),
          Schema.Field("value", Remote.schema[V], get0 = _.value, set0 = (o, v) => o.copy(value = v)),
          OpticSet(_, _, _)
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], OpticSet[Any, Any, Any, A]] =
      Schema.Case(
        "OpticSet",
        schema,
        _.asInstanceOf[OpticSet[Any, Any, Any, A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[OpticSet[_, _, _, _]]
      )
  }

  final case class SortList[A](list: Remote[List[A]], lt: UnboundRemoteFunction[(A, A), Boolean])
      extends Remote[List[A]] {
    override def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
      for {
        dynList <- list.evalDynamic
        runtime <- ZIO.runtime[LocalContext with RemoteContext]
        sortedItems <- dynList match {
                         case DynamicValue.Sequence(values) =>
                           ZIO.attemptUnsafe { implicit u =>
                             values.sortWith { (a, b) =>
                               runtime.unsafe
                                 .run(
                                   lt.apply(Remote.Tuple2(Remote.fromDynamic(a), Remote.fromDynamic(b))).eval[Boolean]
                                 )
                                 .getOrThrowFiberFailure()
                             }
                           }.catchAll {
                             case FiberFailure(cause: Cause[_]) =>
                               ZIO.failCause(cause.asInstanceOf[Cause[RemoteEvaluationError]])
                             case other: Throwable => ZIO.die(other)
                           }
                         case _ =>
                           ZIO.fail(
                             RemoteEvaluationError.UnexpectedDynamicValue(
                               s"Unexpected value in Remote.SortList of type ${dynList.getClass.getSimpleName}"
                             )
                           )
                       }
        sortedList = DynamicValue.Sequence(sortedItems)
      } yield sortedList

    override private[flow] def variableUsage = list.variableUsage.union(lt.variableUsage)

    override protected def substituteRec(f: Substitutions): Remote[List[A]] =
      SortList(list.substitute(f), lt.substitute(f).asInstanceOf[UnboundRemoteFunction[(A, A), Boolean]])
  }

  object SortList {
    private val typeId: TypeId = TypeId.parse("zio.flow.Remote.SortList")

    def schema[A]: Schema[SortList[A]] =
      Schema.defer(
        Schema.CaseClass2[Remote[List[A]], UnboundRemoteFunction[(A, A), Boolean], SortList[A]](
          typeId,
          Schema.Field("list", Remote.schema[List[A]], get0 = _.list, set0 = (o, v) => o.copy(list = v)),
          Schema
            .Field("lt", UnboundRemoteFunction.schema[(A, A), Boolean], get0 = _.lt, set0 = (o, v) => o.copy(lt = v)),
          SortList(_, _)
        )
      )

    def schemaCase[A]: Schema.Case[Remote[A], SortList[A]] =
      Schema.Case(
        "SortList",
        schema,
        _.asInstanceOf[SortList[A]],
        _.asInstanceOf[Remote[A]],
        _.isInstanceOf[SortList[_]]
      )
  }

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

  def bind[A, B](value: Remote[A])(f: Unbound[A] => Remote[B]): Remote[B] = {
    val unbound = Unbound[A](LocalContext.generateFreshBinding)
    Bind(unbound, value, f(unbound))
  }

  def chunk[A](values: Remote[A]*): Remote[Chunk[A]] =
    Chunk.fromList(list(values: _*))

  def config[A: Schema](key: ConfigKey): Remote[A] =
    Config(key, implicitly[Schema[A]])

  def either[A, B](
    either: Either[Remote[A], Remote[B]]
  ): Remote[Either[A, B]] =
    RemoteEither(either)

  def emptyChunk[A]: Remote[Chunk[A]] = Remote.Literal(DynamicValue.Sequence(Chunk.empty))

  def emptyMap[K, V]: Remote[Map[K, V]] = Remote.Literal(DynamicValue.Dictionary(Chunk.empty))

  def emptySet[A]: Remote[Set[A]] = Remote.Literal(DynamicValue.SetValue(Set.empty))

  def fail[A](message: String): Remote[A] =
    Remote.Fail(message)

  def fromDynamic[A](dynamicValue: DynamicValue): Remote[A] =
    // TODO: either avoid this or do it nicer
    dynamicValue.toTypedValueOption(RemoteVariableReference.schema[Any]) match {
      case None =>
        dynamicValue.toTypedValueOption(ZFlow.schemaAny) match {
          case None =>
            // Not a ZFlow
            dynamicValue.toTypedValueOption(Remote.schemaAny) match {
              case None =>
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
              case Some(remote) =>
                Nested(remote).asInstanceOf[Remote[A]]
            }
          case Some(zflow) =>
            Flow(zflow).asInstanceOf[Remote[A]]
        }
      case Some(ref) =>
        VariableReference(ref).asInstanceOf[Remote[A]]
    }

  def left[A, B](value: Remote[A]): Remote[Either[A, B]] =
    Remote.RemoteEither(Left(value))

  def list[A](values: Remote[A]*): Remote[List[A]] =
    values.foldRight(nil[A])((elem, lst) => Remote.Cons(lst, elem))

  def map[K, V](values: Remote[(K, V)]*): Remote[Map[K, V]] =
    list(values: _*).toMap

  def nil[A]: Remote[List[A]] = Remote.Literal(DynamicValue.Sequence(Chunk.empty))

  def none[A]: Remote[Option[A]] = Remote.Literal(DynamicValue.NoneValue)

  def recurse[A, B](
    initial: Remote[A]
  )(body: (Remote[A], (Remote[A] => Remote.RecurseWith[A, B])) => Remote[B]): Remote[B] = {
    val id = LocalContext.generateFreshRecursionId
    Remote.Recurse(
      id,
      initial,
      UnboundRemoteFunction.make((value: Remote[A]) =>
        body(value, (next: Remote[A]) => Remote.RecurseWith[A, B](id, next))
      )
    )
  }

  def recurseSimple[A](
    initial: Remote[A]
  )(body: (Remote[A], (Remote[A] => Remote.RecurseWith[A, A])) => Remote[A]): Remote[A] =
    recurse[A, A](initial)(body)

  def right[A, B](value: Remote[B]): Remote[Either[A, B]] = Remote.RemoteEither(Right(value))

  def set[A](values: Remote[A]*): Remote[Set[A]] =
    list(values: _*).toSet

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

  private val typeId: TypeId = TypeId.parse("zio.flow.Remote")

  private def createSchema[A]: Schema[Remote[A]] = Schema.EnumN(
    typeId,
    CaseSet
      .Cons(Literal.schemaCase[A], CaseSet.Empty[Remote[A]]())
      .:+:(Fail.schemaCase[A])
      .:+:(Debug.schemaCase[A])
      .:+:(Flow.schemaCase[A])
      .:+:(Nested.schemaCase[A])
      .:+:(VariableReference.schemaCase[A])
      .:+:(Ignore.schemaCase[A])
      .:+:(Variable.schemaCase[A])
      .:+:(Config.schemaCase[A])
      .:+:(Unbound.schemaCase[A])
      .:+:(Unary.schemaCase[Any, A])
      .:+:(Binary.schemaCase[Any, Any, A])
      .:+:(UnboundRemoteFunction.schemaCase[Any, A])
      .:+:(Bind.schemaCase[Any, A])
      .:+:(RemoteEither.schemaCase[A])
      .:+:(FoldEither.schemaCase[Any, Any, A])
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
      .:+:(StringToCharList.schemaCase[A])
      .:+:(CharListToString.schemaCase[A])
      .:+:(Equal.schemaCase[A])
      .:+:(Fold.schemaCase[A])
      .:+:(Cons.schemaCase[A])
      .:+:(UnCons.schemaCase[A])
      .:+:(DurationFromAmount.schemaCase[A])
      .:+:(Lazy.schemaCase[A])
      .:+:(RemoteSome.schemaCase[A])
      .:+:(FoldOption.schemaCase[A])
      .:+:(Recurse.schemaCase[Any, A])
      .:+:(RecurseWith.schemaCase[Any, A])
      .:+:(ListToSet.schemaCase[A])
      .:+:(SetToList.schemaCase[A])
      .:+:(ListToString.schemaCase[A])
      .:+:(ListToMap.schemaCase[A])
      .:+:(MapToList.schemaCase[A])
      .:+:(OpticGet.schemaCase[A])
      .:+:(OpticSet.schemaCase[A])
  )

  implicit val schemaAny: Schema[Remote[Any]] = createSchema[Any]
  def schema[A]: Schema[Remote[A]]            = schemaAny.asInstanceOf[Schema[Remote[A]]]
}
