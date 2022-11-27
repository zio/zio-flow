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

import zio.Scope
import zio.flow.{Remote, ZFlow}
import zio.schema.{Schema, TypeId}
import zio.schema.meta.MetaSchema
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}

import scala.collection.immutable.ListMap

object FlowSchemaAstSpec extends ZIOSpecDefault {
  override def spec: Spec[TestEnvironment with Scope, Any] =
    suite("FlowSchemaAst")(
      test("Tuple of flow and primitive") {
        val schema  = Schema.tuple2(ZFlow.schemaAny, Schema[Int])
        val flowAst = FlowSchemaAst.fromSchema(schema)
        val schema2 = flowAst.toSchema
        assertTrue(Schema.structureEquality.equal(schema, schema2))
      },
      test("List of tuples of flow and remote") {
        val schema  = Schema.list(Schema.tuple2(ZFlow.schemaAny, Remote.schemaAny))
        val flowAst = FlowSchemaAst.fromSchema(schema)
        val schema2 = flowAst.toSchema
        assertTrue(Schema.structureEquality.equal(schema, schema2))
      },
      test("Schemas") {
        val schema  = Schema.tuple2(FlowSchemaAst.schema, MetaSchema.schema)
        val flowAst = FlowSchemaAst.fromSchema(schema)
        val schema2 = flowAst.toSchema
        assertTrue(Schema.structureEquality.equal(schema, schema2))
      },
      test("Record") {
        val schema = Schema.record(
          TypeId.Structural,
          Schema
            .Field(
              "x",
              Schema[Int],
              get0 = _("x").asInstanceOf[Int],
              set0 = (lm: ListMap[String, Any], v: Int) => lm.updated("x", v)
            ),
          Schema.Field(
            "flow",
            ZFlow.schemaAny,
            get0 = _("flow").asInstanceOf[ZFlow[Any, Any, Any]],
            set0 = (lm: ListMap[String, Any], v: ZFlow[Any, Any, Any]) => lm.updated("flow", v)
          ),
          Schema.Field(
            "remote",
            Remote.schemaAny,
            get0 = _("remote").asInstanceOf[Remote[Any]],
            set0 = (lm: ListMap[String, Any], v: Remote[Any]) => lm.updated("remote", v)
          )
        )
        val flowAst = FlowSchemaAst.fromSchema(schema)
        val schema2 = flowAst.toSchema
        assertTrue(Schema.structureEquality.equal(schema, schema2))
      }
    )
}
