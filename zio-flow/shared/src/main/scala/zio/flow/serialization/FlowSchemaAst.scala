package zio.flow.serialization

import zio.flow.{Remote, ZFlow}
import zio.schema.{DeriveSchema, Schema}
import zio.schema.ast.SchemaAst

/**
 * Wrapper for SchemaAst to prevent serialization of some predefined zio-flow
 * types
 */
sealed trait FlowSchemaAst {
  def toSchema[A]: Schema[A]
}
object FlowSchemaAst {
  final case object RemoteAst extends FlowSchemaAst {
    override def toSchema[A]: Schema[A] =
      Remote.schemaAny.asInstanceOf[Schema[A]]
  }
  final case object FlowAst extends FlowSchemaAst {
    override def toSchema[A]: Schema[A] =
      ZFlow.schemaAny.asInstanceOf[Schema[A]]
  }
  final case class CustomAst(ast: SchemaAst) extends FlowSchemaAst {
    override def toSchema[A]: Schema[A] =
      ast.toSchema.asInstanceOf[Schema[A]]
  }

  def fromSchema[A](schema: Schema[A]): FlowSchemaAst =
    if (schema eq Remote.schemaAny)
      RemoteAst
    else if (schema eq ZFlow.schemaAny)
      FlowAst
    else
      CustomAst(schema.ast)

  implicit val schema: Schema[FlowSchemaAst] = DeriveSchema.gen
}
