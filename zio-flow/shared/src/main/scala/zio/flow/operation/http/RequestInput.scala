/*
 * Copyright 2021-2023 John A. De Goes and the ZIO Contributors
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

package zio.flow.operation.http

import zio.flow.serialization.FlowSchemaAst
import zio.schema.{CaseSet, DeriveSchema, Schema, TypeId}

/**
 * A RequestInput is a description of a Path, Query Parameters, Headers and Body
 *   - Path: /users/:id/posts
 *   - Query Parameters: ?page=1&limit=10
 *   - Headers: X-User-Id: 1 or Accept: application/json
 *   - Body: anything that has a schema
 */
sealed trait RequestInput[A] extends Product with Serializable { self =>
  def schema: Schema[A]

  private[flow] def ++[B](that: RequestInput[B])(implicit zipper: Zipper[A, B]): RequestInput[zipper.Out] =
    RequestInput.ZipWith[A, B, zipper.Out](self, that, zipper)
}

object RequestInput {
  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.RequestInput")

  def schema[A]: Schema[RequestInput[A]] =
    schemaAny.asInstanceOf[Schema[RequestInput[A]]]

  lazy val schemaAny: Schema[RequestInput[Any]] =
    Schema.EnumN(
      typeId,
      CaseSet
        .Cons(ZipWith.schemaCase[Any], CaseSet.Empty[RequestInput[Any]]())
        .:+:(Header.schemaCase[Any])
        .:+:(Query.schemaCase[Any])
        .:+:(Path.schemaCase[Any])
        .:+:(Body.schemaCase[Any])
    )

  private[flow] final case class ZipWith[A, B, C](
    left: RequestInput[A],
    right: RequestInput[B],
    zipper: Zipper.WithOut[A, B, C]
  ) extends RequestInput[C] {
    override lazy val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.RequestInput.ZipWith")

    def schema[A, B, C]: Schema[ZipWith[A, B, C]] =
      schemaAny.asInstanceOf[Schema[ZipWith[A, B, C]]]

    lazy val schemaAny: Schema[ZipWith[Any, Any, Any]] =
      Schema.CaseClass3[RequestInput[Any], RequestInput[Any], Zipper.WithOut[Any, Any, Any], ZipWith[Any, Any, Any]](
        typeId,
        Schema.Field("left", Schema.defer(RequestInput.schema[Any]), get0 = _.left, set0 = (a, b) => a.copy(left = b)),
        Schema
          .Field("right", Schema.defer(RequestInput.schema[Any]), get0 = _.right, set0 = (a, b) => a.copy(right = b)),
        Schema.Field("zipper", Zipper.schema[Any, Any, Any], get0 = _.zipper, set0 = (a, b) => a.copy(zipper = b)),
        ZipWith(_, _, _)
      )

    def schemaCase[A]: Schema.Case[RequestInput[A], ZipWith[Any, Any, Any]] =
      Schema.Case(
        "ZipWith",
        schemaAny,
        _.asInstanceOf[ZipWith[Any, Any, Any]],
        _.asInstanceOf[RequestInput[A]],
        _.isInstanceOf[ZipWith[_, _, _]]
      )
  }

}

/**
 * =HEADERS=
 */
sealed trait Header[A] extends RequestInput[A] {
  self =>

  def ? : Header[Option[A]] =
    Header.Optional(self)

  def ++[B](that: Header[B])(implicit zipper: Zipper[A, B]): Header[zipper.Out] =
    Header.ZipWith[A, B, zipper.Out](self, that, zipper)
}

object Header {
  lazy val AcceptEncoding: Header[String] = string("Accept-Encoding")
  lazy val UserAgent: Header[String]      = string("User-Agent")
  lazy val Host: Header[String]           = string("Host")
  lazy val Accept: Header[String]         = string("Accept")

  def string(name: String): Header[String] = SingleHeader(name, Schema[String])

  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header")

  def schema[A]: Schema[Header[A]] =
    schemaAny.asInstanceOf[Schema[Header[A]]]

  lazy val schemaAny: Schema[Header[Any]] =
    Schema.EnumN(
      typeId,
      CaseSet
        .Cons(SingleHeader.schemaCase[Any], CaseSet.Empty[Header[Any]]())
        .:+:(ZipWith.schemaCase[Any])
        .:+:(Optional.schemaCase[Any])
    )

  def schemaCase[A]: Schema.Case[RequestInput[A], Header[A]] =
    Schema.Case(
      "Header",
      schema[A],
      _.asInstanceOf[Header[A]],
      _.asInstanceOf[RequestInput[A]],
      _.isInstanceOf[Header[A]]
    )

  private[flow] final case class SingleHeader[A](name: String, override val schema: Schema[A]) extends Header[A]
  object SingleHeader {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header.SingleHeader")

    def schema[A]: Schema[SingleHeader[A]] =
      schemaAny.asInstanceOf[Schema[SingleHeader[A]]]

    lazy val schemaAny: Schema[SingleHeader[Any]] =
      Schema.CaseClass2[String, FlowSchemaAst, SingleHeader[Any]](
        typeId,
        Schema.Field("name", Schema[String], get0 = _.name, set0 = (a, b) => a.copy(name = b)),
        Schema.Field(
          "schema",
          FlowSchemaAst.schema,
          get0 = header => FlowSchemaAst.fromSchema(header.schema),
          set0 = (a, b) => a.copy(schema = b.toSchema.asInstanceOf[Schema[Any]])
        ),
        (name, schema) => SingleHeader(name, schema.toSchema.asInstanceOf[Schema[Any]])
      )

    def schemaCase[A]: Schema.Case[Header[A], SingleHeader[A]] =
      Schema.Case(
        "SingleHeader",
        schema[A],
        _.asInstanceOf[SingleHeader[A]],
        _.asInstanceOf[Header[A]],
        _.isInstanceOf[SingleHeader[A]]
      )
  }

  private[flow] final case class ZipWith[A, B, C](left: Header[A], right: Header[B], zipper: Zipper.WithOut[A, B, C])
      extends Header[C] {

    override lazy val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header.ZipWith")

    def schema[A, B, C] =
      schemaAny.asInstanceOf[Schema[ZipWith[A, B, C]]]

    lazy val schemaAny =
      Schema.CaseClass3[Header[Any], Header[Any], Zipper.WithOut[Any, Any, Any], ZipWith[Any, Any, Any]](
        typeId,
        Schema.Field("left", Schema.defer(Header.schema[Any]), get0 = _.left, set0 = (a, b) => a.copy(left = b)),
        Schema.Field("right", Schema.defer(Header.schema[Any]), get0 = _.right, set0 = (a, b) => a.copy(right = b)),
        Schema.Field("zipper", Zipper.schema[Any, Any, Any], get0 = _.zipper, set0 = (a, b) => a.copy(zipper = b)),
        ZipWith(_, _, _)
      )

    def schemaCase[A]: Schema.Case[Header[A], ZipWith[Any, Any, Any]] =
      Schema.Case(
        "ZipWith",
        schema[Any, Any, Any],
        _.asInstanceOf[ZipWith[Any, Any, Any]],
        _.asInstanceOf[Header[A]],
        _.isInstanceOf[ZipWith[_, _, _]]
      )

  }

  private[flow] case class Optional[A](headers: Header[A]) extends Header[Option[A]] {
    override lazy val schema: Schema[Option[A]] = Schema.option(headers.schema)
  }

  object Optional {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header.Optional")

    def schema[A]: Schema[Optional[A]] =
      schemaAny.asInstanceOf[Schema[Optional[A]]]

    lazy val schemaAny: Schema[Optional[Any]] =
      Schema.CaseClass1[Header[Any], Optional[Any]](
        typeId,
        Schema
          .Field("headers", Schema.defer(Header.schema[Any]), get0 = _.headers, set0 = (a, b) => a.copy(headers = b)),
        Optional(_)
      )

    def schemaCase[A]: Schema.Case[Header[A], Optional[A]] =
      Schema.Case(
        "Optional",
        schema[A],
        _.asInstanceOf[Optional[A]],
        _.asInstanceOf[Header[A]],
        _.isInstanceOf[Optional[_]]
      )
  }
}

case class Body[A](override val schema: Schema[A], contentType: ContentType) extends RequestInput[A] { self =>
  def ++[B](that: Body[B]): Body[B] = that
}

object Body {
  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Body")

  def schema[A]: Schema[Body[A]] = Schema.CaseClass2[FlowSchemaAst, ContentType, Body[A]](
    typeId,
    Schema.Field(
      "schema",
      FlowSchemaAst.schema,
      get0 = body => FlowSchemaAst.fromSchema(body.schema),
      set0 = (a, b) => a.copy(schema = b.toSchema.asInstanceOf[Schema[A]])
    ),
    Schema.Field("contentType", Schema[ContentType], get0 = _.contentType, set0 = (a, b) => a.copy(contentType = b)),
    (ast, contentType) => Body(ast.toSchema.asInstanceOf[Schema[A]], contentType)
  )

  def schemaCase[A]: Schema.Case[RequestInput[A], Body[A]] =
    Schema.Case("Body", schema[A], _.asInstanceOf[Body[A]], _.asInstanceOf[RequestInput[A]], _.isInstanceOf[Body[A]])
}

/**
 * =QUERY PARAMS=
 */
sealed trait Query[A] extends RequestInput[A] { self =>
  def ? : Query[Option[A]] = Query.Optional(self)

  def ++[B](that: Query[B])(implicit zipper: Zipper[A, B]): Query[zipper.Out] =
    Query.ZipWith[A, B, zipper.Out](self, that, zipper)
}

object Query {
  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query")

  def schema[A]: Schema[Query[A]] =
    schemaAny.asInstanceOf[Schema[Query[A]]]

  lazy val schemaAny: Schema[Query[Any]] =
    Schema.EnumN(
      typeId,
      CaseSet
        .Cons(SingleParam.schemaCase[Any], CaseSet.Empty[Query[Any]]())
        .:+:(ZipWith.schemaCase[Any])
        .:+:(Optional.schemaCase[Any])
    )

  def schemaCase[A]: Schema.Case[RequestInput[A], Query[A]] =
    Schema.Case("Query", schema[A], _.asInstanceOf[Query[A]], _.asInstanceOf[RequestInput[A]], _.isInstanceOf[Query[A]])

  private[flow] final case class SingleParam[A](name: String, override val schema: Schema[A]) extends Query[A]

  object SingleParam {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.SingleParam")

    def schema[A]: Schema[SingleParam[A]] =
      schemaAny.asInstanceOf[Schema[SingleParam[A]]]

    lazy val schemaAny: Schema[SingleParam[Any]] =
      Schema.CaseClass2[String, FlowSchemaAst, SingleParam[Any]](
        typeId,
        Schema.Field("name", Schema[String], get0 = _.name, set0 = (a, b) => a.copy(name = b)),
        Schema.Field(
          "schema",
          FlowSchemaAst.schema,
          get0 = param => FlowSchemaAst.fromSchema(param.schema),
          set0 = (a, b) => a.copy(schema = b.toSchema.asInstanceOf[Schema[Any]])
        ),
        (name, schema) => SingleParam(name, schema.toSchema.asInstanceOf[Schema[Any]])
      )

    def schemaCase[A]: Schema.Case[Query[A], SingleParam[A]] =
      Schema.Case(
        "SingleParam",
        schema[A],
        _.asInstanceOf[SingleParam[A]],
        _.asInstanceOf[Query[A]],
        _.isInstanceOf[SingleParam[A]]
      )

  }

  private[flow] final case class ZipWith[A, B, C](left: Query[A], right: Query[B], zipper: Zipper.WithOut[A, B, C])
      extends Query[C] {
    override val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.ZipWith")

    def schema[A, B, C]: Schema[ZipWith[A, B, C]] =
      schemaAny.asInstanceOf[Schema[ZipWith[A, B, C]]]

    lazy val schemaAny: Schema[ZipWith[Any, Any, Any]] =
      Schema.CaseClass3[Query[Any], Query[Any], Zipper.WithOut[Any, Any, Any], ZipWith[Any, Any, Any]](
        typeId,
        Schema.Field("left", Schema.defer(Query.schema[Any]), get0 = _.left, set0 = (a, b) => a.copy(left = b)),
        Schema.Field("right", Schema.defer(Query.schema[Any]), get0 = _.right, set0 = (a, b) => a.copy(right = b)),
        Schema.Field("zipper", Zipper.schema[Any, Any, Any], get0 = _.zipper, set0 = (a, b) => a.copy(zipper = b)),
        ZipWith(_, _, _)
      )

    def schemaCase[A]: Schema.Case[Query[A], ZipWith[Any, Any, Any]] =
      Schema.Case(
        "ZipWith",
        schema[Any, Any, Any],
        _.asInstanceOf[ZipWith[Any, Any, Any]],
        _.asInstanceOf[Query[A]],
        _.isInstanceOf[ZipWith[_, _, _]]
      )

  }

  private[flow] case class Optional[A](params: Query[A]) extends Query[Option[A]] {
    override lazy val schema: Schema[Option[A]] = Schema.option(params.schema)
  }

  object Optional {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.Optional")

    def schema[A]: Schema[Optional[A]] =
      schemaAny.asInstanceOf[Schema[Optional[A]]]

    lazy val schemaAny: Schema[Optional[Any]] =
      Schema.CaseClass1[Query[Any], Optional[Any]](
        typeId,
        Schema.Field("params", Schema.defer(Query.schema[Any]), get0 = _.params, set0 = (a, b) => a.copy(params = b)),
        Optional.apply
      )

    def schemaCase[A]: Schema.Case[Query[A], Optional[A]] =
      Schema.Case(
        "Optional",
        schema[A],
        _.asInstanceOf[Optional[A]],
        _.asInstanceOf[Query[A]],
        _.isInstanceOf[Optional[_]]
      )
  }
}

/**
 * A DSL for describe Paths
 *   - ex: /users
 *   - ex: /users/:id/friends
 *   - ex: /users/:id/friends/:friendId
 *   - ex: /posts/:id/comments/:commentId
 */
sealed trait Path[A] extends RequestInput[A] { self =>
  def /[B](that: Path[B])(implicit zipper: Zipper[A, B]): Path[zipper.Out] =
    Path.ZipWith[A, B, zipper.Out](this, that, zipper)

  def /(string: String): Path[A] =
    Path.ZipWith(this, Path.path(string), Zipper.zipperRightIdentity)

  def +(string: String): Path[A] =
    Path.ZipWith(this, Path.Literal(string), Zipper.zipperRightIdentity)
}

final case class PathState(var input: List[String])

object Path {
  def path(name: String): Path[Unit] = Path.Literal("/" + name)

  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Path")

  def schema[A]: Schema[Path[A]] =
    schemaAny.asInstanceOf[Schema[Path[A]]]

  lazy val schemaAny: Schema[Path[Any]] = Schema.EnumN(
    typeId,
    CaseSet
      .Cons(Literal.schemaCase[Any], CaseSet.Empty[Path[Any]]())
      .:+:(Match.schemaCase[Any])
      .:+:(ZipWith.schemaCase[Any])
  )

  def schemaCase[A]: Schema.Case[RequestInput[A], Path[A]] =
    Schema.Case("Path", schema[A], _.asInstanceOf[Path[A]], _.asInstanceOf[RequestInput[A]], _.isInstanceOf[Path[A]])

  private[flow] final case class Literal(string: String) extends Path[Unit] {
    override lazy val schema: Schema[Unit] = Schema.singleton(())
  }

  object Literal {
    lazy val schema: Schema[Literal] = DeriveSchema.gen[Literal]

    def schemaCase[A]: Schema.Case[Path[A], Literal] =
      Schema.Case("Literal", schema, _.asInstanceOf[Literal], _.asInstanceOf[Path[A]], _.isInstanceOf[Literal])
  }

  private[flow] final case class Match[A](schema: Schema[A]) extends Path[A]

  object Match {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Path.Match")

    def schema[A]: Schema[Match[A]] =
      schemaAny.asInstanceOf[Schema[Match[A]]]

    lazy val schemaAny: Schema[Match[Any]] =
      Schema.CaseClass1[FlowSchemaAst, Match[Any]](
        typeId,
        Schema.Field(
          "schema",
          FlowSchemaAst.schema,
          get0 = m => FlowSchemaAst.fromSchema(m.schema),
          set0 = (a, b) => a.copy(schema = b.toSchema.asInstanceOf[Schema[Any]])
        ),
        schema => Match(schema.toSchema.asInstanceOf[Schema[Any]])
      )

    def schemaCase[A]: Schema.Case[Path[A], Match[A]] =
      Schema.Case("Match", schema[A], _.asInstanceOf[Match[A]], _.asInstanceOf[Path[A]], _.isInstanceOf[Match[A]])
  }

  private[flow] final case class ZipWith[A, B, C](left: Path[A], right: Path[B], zipper: Zipper.WithOut[A, B, C])
      extends Path[C] {
    override lazy val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Path.ZipWith")

    def schema[A, B, C]: Schema[ZipWith[A, B, C]] =
      schemaAny.asInstanceOf[Schema[ZipWith[A, B, C]]]

    lazy val schemaAny: Schema[ZipWith[Any, Any, Any]] =
      Schema.CaseClass3[Path[Any], Path[Any], Zipper.WithOut[Any, Any, Any], ZipWith[Any, Any, Any]](
        typeId,
        Schema.Field("left", Schema.defer(Path.schema[Any]), get0 = _.left, set0 = (a, b) => a.copy(left = b)),
        Schema.Field("right", Schema.defer(Path.schema[Any]), get0 = _.right, set0 = (a, b) => a.copy(right = b)),
        Schema.Field("zipper", Zipper.schema[Any, Any, Any], get0 = _.zipper, set0 = (a, b) => a.copy(zipper = b)),
        ZipWith(_, _, _)
      )

    def schemaCase[A]: Schema.Case[Path[A], ZipWith[Any, Any, Any]] =
      Schema.Case(
        "ZipWith",
        schema[Any, Any, Any],
        _.asInstanceOf[ZipWith[Any, Any, Any]],
        _.asInstanceOf[Path[A]],
        _.isInstanceOf[ZipWith[_, _, _]]
      )
  }
}
