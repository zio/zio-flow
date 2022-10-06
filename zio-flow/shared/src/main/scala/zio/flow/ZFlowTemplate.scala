package zio.flow

import zio.schema.ast.SchemaAst
import zio.schema.{DeriveSchema, Schema}

final case class ZFlowTemplate(template: ZFlow[Any, Any, Any], inputSchema: Option[SchemaAst])
object ZFlowTemplate {
  implicit val schema: Schema[ZFlowTemplate] = DeriveSchema.gen
}
