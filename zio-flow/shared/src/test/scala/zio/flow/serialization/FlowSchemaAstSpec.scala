package zio.flow.serialization

import zio.Scope
import zio.flow.{Remote, ZFlow}
import zio.schema.Schema
import zio.schema.ast.SchemaAst
import zio.test.{Spec, TestEnvironment, ZIOSpecDefault, assertTrue}

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
        val schema  = Schema.tuple2(FlowSchemaAst.schema, SchemaAst.schema)
        val flowAst = FlowSchemaAst.fromSchema(schema)
        val schema2 = flowAst.toSchema
        assertTrue(Schema.structureEquality.equal(schema, schema2))
      },
      test("Record") {
        val schema = Schema.record(
          Schema.Field("x", Schema[Int]),
          Schema.Field("flow", ZFlow.schemaAny),
          Schema.Field("remote", Remote.schemaAny)
        )
        val flowAst = FlowSchemaAst.fromSchema(schema)
        val schema2 = flowAst.toSchema
        assertTrue(Schema.structureEquality.equal(schema, schema2))
      }
    )
}
