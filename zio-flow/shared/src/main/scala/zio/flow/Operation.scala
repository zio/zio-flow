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

import zio.schema._
import zio.flow.operation.http.API
import zio.flow.serialization.FlowSchemaAst

sealed trait Operation[-Input, +Result] { self =>
  val inputSchema: Schema[_ >: Input]
  val resultSchema: Schema[_ <: Result]

  def contramap[Input2: Schema](f: Remote[Input2] => Remote[Input]): Operation[Input2, Result] =
    Operation.ContraMap(self, f, implicitly[Schema[Input2]])

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
            Schema.Field("inner", Operation.schema[Input, Result]),
            Schema.Field("f", Remote.UnboundRemoteFunction.schema[Input2, Input]),
            Schema.Field("inputSchema", FlowSchemaAst.schema),
            (inner, f, ast) => ContraMap(inner, f, ast.toSchema[Input2]),
            _.inner,
            _.f,
            op => FlowSchemaAst.fromSchema(op.schema)
          )
      }

    def schemaCase[Input, Result]: Schema.Case[ContraMap[Any, Input, Result], Operation[Input, Result]] =
      Schema.Case("ContraMap", schema[Any, Input, Result], _.asInstanceOf[ContraMap[Any, Input, Result]])
  }

  final case class Map[Input, Result, Result2](
    inner: Operation[Input, Result],
    f: Remote.UnboundRemoteFunction[Result, Result2],
    schema: Schema[Result2]
  ) extends Operation[Input, Result2] {
    override val inputSchema: Schema[_ >: Input]    = inner.inputSchema
    override val resultSchema: Schema[_ <: Result2] = schema
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
            Schema.Field("inner", Operation.schema[Input, Result]),
            Schema.Field("f", Remote.UnboundRemoteFunction.schema[Result, Result2]),
            Schema.Field("inputSchema", FlowSchemaAst.schema),
            (inner, f, ast) => Map(inner, f, ast.toSchema[Result2]),
            _.inner,
            _.f,
            op => FlowSchemaAst.fromSchema(op.schema)
          )
      }

    def schemaCase[Input, Result2]: Schema.Case[Map[Input, Any, Result2], Operation[Input, Result2]] =
      Schema.Case("Map", schema[Input, Any, Result2], _.asInstanceOf[Map[Input, Any, Result2]])
  }

  final case class Http[Input, Result](
    host: String,
    api: zio.flow.operation.http.API[Input, Result]
  ) extends Operation[Input, Result] {
    override val inputSchema: Schema[_ >: Input]   = api.requestInput.schema
    override val resultSchema: Schema[_ <: Result] = api.outputSchema
  }

  object Http {
    private val typeId: TypeId = TypeId.parse("zio.flow.Operation.Http")

    def schema[Input, Result]: Schema[Http[Input, Result]] =
      Schema.CaseClass2[String, API[Input, Result], Http[Input, Result]](
        typeId,
        Schema.Field("host", Schema[String]),
        Schema.Field("api", API.schema[Input, Result]),
        (host, api) => Http(host, api),
        _.host,
        _.api
      )

    def schemaCase[Input, Result]: Schema.Case[Http[Input, Result], Operation[Input, Result]] =
      Schema.Case("Http", schema[Input, Result], _.asInstanceOf[Http[Input, Result]])
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
