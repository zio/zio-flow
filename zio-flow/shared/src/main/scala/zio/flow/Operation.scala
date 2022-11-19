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

import zio.flow.Operation.Http.toPathString
import zio.flow.operation.http.{API, Path, RequestInput}
import zio.flow.serialization.FlowSchemaAst
import zio.schema._

import scala.collection.mutable

/**
 * Operation describes the way a zio-flow workflow communicates with the outside
 * world.
 *
 * An operation always has an input value of type Input, and a result value of
 * type Result. What the operation does with the input value to get the result
 * depends on the actual operation's other properties.
 *
 * Both the input and the result types need to have a schema, because the
 * workflow executor may need to encode/decode values when communicating with an
 * external service.
 *
 * Currently the only supported operation is the [[Operation.Http]] operation.
 *
 * Operations are not directly used from the zio-flow programs, but through
 * [[Activity]] values.
 *
 * When writing tests for workflows the MockedOperation class provides
 * capabilities to mock these operations instead of using the real operation
 * executor.
 */
trait Operation[-Input, +Result] { self =>
  val inputSchema: Schema[_ >: Input]
  val resultSchema: Schema[_ <: Result]

  /**
   * Defines an operation that performs the same thing but it's input gets
   * transformed by the given remote function first.
   */
  def contramap[Input2: Schema](f: Remote[Input2] => Remote[Input]): Operation[Input2, Result] =
    Operation.ContraMap(self, f, implicitly[Schema[Input2]])

  /**
   * Defines an operation that performs the same thing and then transforms the
   * result with the given remote function.
   */
  def map[Result2: Schema](f: Remote[Result] => Remote[Result2]): Operation[Input, Result2] =
    Operation.Map(self, f, implicitly[Schema[Result2]])
}

object Operation {
  final case class ContraMap[Input2, Input, Result](
    inner: Operation[Input, Result],
    f: Remote.UnboundRemoteFunction[Input2, Input],
    schema: Schema[Input2]
  ) extends Operation[Input2, Result] {
    override val inputSchema: Schema[_ >: Input2]  = schema
    override val resultSchema: Schema[_ <: Result] = inner.resultSchema

    override def toString: String =
      s"${inner}.contramap"
  }

  object ContraMap {
    private val typeId: TypeId = TypeId.parse("zio.flow.Operation.ContraMap")

    def schema[Input2, Input, Result]: Schema[ContraMap[Input2, Input, Result]] =
      Schema.defer {
        Schema
          .CaseClass3[Operation[Input, Result], Remote.UnboundRemoteFunction[Input2, Input], FlowSchemaAst, ContraMap[
            Input2,
            Input,
            Result
          ]](
            typeId,
            Schema.Field(
              "inner",
              Operation.schema[Input, Result],
              get0 = _.inner,
              set0 = (a: ContraMap[Input2, Input, Result], v: Operation[Input, Result]) => a.copy(inner = v)
            ),
            Schema.Field(
              "f",
              Remote.UnboundRemoteFunction.schema[Input2, Input],
              get0 = _.f,
              set0 =
                (a: ContraMap[Input2, Input, Result], v: Remote.UnboundRemoteFunction[Input2, Input]) => a.copy(f = v)
            ),
            Schema.Field(
              "inputSchema",
              FlowSchemaAst.schema,
              get0 = op => FlowSchemaAst.fromSchema(op.schema),
              set0 = (a: ContraMap[Input2, Input, Result], v: FlowSchemaAst) => a.copy(schema = v.toSchema)
            ),
            (inner, f, ast) => ContraMap(inner, f, ast.toSchema[Input2])
          )
      }

    def schemaCase[Input, Result]: Schema.Case[Operation[Input, Result], ContraMap[Any, Input, Result]] =
      Schema.Case(
        "ContraMap",
        schema[Any, Input, Result],
        _.asInstanceOf[ContraMap[Any, Input, Result]],
        x => x,
        _.isInstanceOf[ContraMap[_, _, _]]
      )
  }

  final case class Map[Input, Result, Result2](
    inner: Operation[Input, Result],
    f: Remote.UnboundRemoteFunction[Result, Result2],
    schema: Schema[Result2]
  ) extends Operation[Input, Result2] {
    override val inputSchema: Schema[_ >: Input]    = inner.inputSchema
    override val resultSchema: Schema[_ <: Result2] = schema

    override def toString: String =
      s"${inner}.map"
  }

  object Map {
    private val typeId: TypeId = TypeId.parse("zio.flow.Operation.Map")

    def schema[Input, Result, Result2]: Schema[Map[Input, Result, Result2]] =
      Schema.defer {
        Schema
          .CaseClass3[Operation[Input, Result], Remote.UnboundRemoteFunction[Result, Result2], FlowSchemaAst, Map[
            Input,
            Result,
            Result2
          ]](
            typeId,
            Schema.Field(
              "inner",
              Operation.schema[Input, Result],
              get0 = _.inner,
              set0 = (a: Map[Input, Result, Result2], v: Operation[Input, Result]) => a.copy(inner = v)
            ),
            Schema.Field(
              "f",
              Remote.UnboundRemoteFunction.schema[Result, Result2],
              get0 = _.f,
              set0 = (a: Map[Input, Result, Result2], v: Remote.UnboundRemoteFunction[Result, Result2]) => a.copy(f = v)
            ),
            Schema.Field(
              "inputSchema",
              FlowSchemaAst.schema,
              get0 = op => FlowSchemaAst.fromSchema(op.schema),
              set0 = (a: Map[Input, Result, Result2], v: FlowSchemaAst) => a.copy(schema = v.toSchema)
            ),
            (inner, f, ast) => Map(inner, f, ast.toSchema[Result2])
          )
      }

    def schemaCase[Input, Result2]: Schema.Case[Operation[Input, Result2], Map[Input, Any, Result2]] =
      Schema.Case(
        "Map",
        schema[Input, Any, Result2],
        _.asInstanceOf[Map[Input, Any, Result2]],
        x => x,
        _.isInstanceOf[Map[_, _, _]]
      )
  }

  final case class Http[Input, Result](
    host: String,
    api: zio.flow.operation.http.API[Input, Result]
  ) extends Operation[Input, Result] {
    override val inputSchema: Schema[_ >: Input]   = api.requestInput.schema
    override val resultSchema: Schema[_ <: Result] = api.outputSchema

    override def toString: String =
      s"[${api.method} $host/${toPathString(api)}"
  }

  object Http {
    private val typeId: TypeId = TypeId.parse("zio.flow.Operation.Http")

    def schema[Input, Result]: Schema[Http[Input, Result]] =
      Schema.CaseClass2[String, API[Input, Result], Http[Input, Result]](
        typeId,
        Schema
          .Field("host", Schema[String], get0 = _.host, set0 = (a: Http[Input, Result], v: String) => a.copy(host = v)),
        Schema.Field(
          "api",
          API.schema[Input, Result],
          get0 = _.api,
          set0 = (a: Http[Input, Result], v: API[Input, Result]) => a.copy(api = v)
        ),
        (host, api) => Http(host, api)
      )

    def schemaCase[Input, Result]: Schema.Case[Operation[Input, Result], Http[Input, Result]] =
      Schema.Case(
        "Http",
        schema[Input, Result],
        _.asInstanceOf[Http[Input, Result]],
        x => x,
        _.isInstanceOf[Http[Input, Result]]
      )

    def toPathString[Input, Output](api: API[Input, Output]): String = {
      val builder = new mutable.StringBuilder()
      requestInputToPathString(api.requestInput, builder)
      builder.toString()
    }

    private def requestInputToPathString[Input](input: RequestInput[Input], builder: mutable.StringBuilder): Unit =
      input match {
        case RequestInput.ZipWith(left, right, _) =>
          requestInputToPathString(left, builder)
          requestInputToPathString(right, builder)
        case path: Path[_] =>
          pathToPathString(path, builder)
        case _ =>
          ()
      }

    private def pathToPathString[Input](path: Path[Input], builder: mutable.StringBuilder): Unit =
      path match {
        case Path.Literal(s) =>
          builder.append('/')
          builder.append(s)
          ()
        case Path.ZipWith(left, right, _) =>
          pathToPathString(left, builder)
          pathToPathString(right, builder)
        case Path.Match(_) =>
          builder.append('/')
          builder.append("<input>")
          ()
      }

  }

  private val typeId: TypeId = TypeId.parse("zio.flow.Operation")

  implicit def schema[R, A]: Schema[Operation[R, A]] =
    Schema.EnumN(
      typeId,
      CaseSet
        .Cons(Http.schemaCase[R, A], CaseSet.Empty[Operation[R, A]]())
        .:+:(ContraMap.schemaCase[R, A])
        .:+:(Map.schemaCase[R, A])
    )
}
