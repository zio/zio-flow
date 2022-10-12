package zio.flow.server

import zio._
import zio.flow.server.ListFlowTemplatesResponse.Entry
import zio.flow.{TemplateId, ZFlowTemplate}
import zio.schema.{DeriveSchema, Schema}

final case class ListFlowTemplatesResponse(value: Chunk[Entry])
object ListFlowTemplatesResponse {
  case class Entry(templateId: TemplateId, template: ZFlowTemplate)
  object Entry {
    implicit val schema: Schema[Entry] = DeriveSchema.gen
  }

  implicit val schema: Schema[ListFlowTemplatesResponse] = DeriveSchema.gen
}
