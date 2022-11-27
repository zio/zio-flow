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

package zio.flow.operation.http

import zio.schema.{CaseSet, DeriveSchema, Schema, TypeId}
import zio.flow.serialization.FlowSchemaAst

/**
 * A RequestInput is a description of a Path, Query Parameters, Headers and Body
 *   - Path: /users/:id/posts
 *   - Query Parameters: ?page=1&limit=10
 *   - Headers: X-User-Id: 1 or Accept: application/json
 *   - Body: anything that has a schema
 */
sealed trait RequestInput[A] extends Product with Serializable { self =>
  val schema: Schema[A]

  private[flow] def ++[B](that: RequestInput[B])(implicit zipper: Zipper[A, B]): RequestInput[zipper.Out] =
    RequestInput.ZipWith[A, B, zipper.Out](self, that, zipper)
}

object RequestInput {
  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.RequestInput")

  def schema[A]: Schema[RequestInput[A]] = Schema.EnumN(
    typeId,
    CaseSet
      .Cons(ZipWith.schemaCase[A], CaseSet.Empty[RequestInput[A]]())
      .:+:(Header.schemaCase[A])
      .:+:(Query.schemaCase[A])
      .:+:(Path.schemaCase[A])
      .:+:(Body.schemaCase[A])
  )

  private[flow] final case class ZipWith[A, B, C](
    left: RequestInput[A],
    right: RequestInput[B],
    zipper: Zipper.WithOut[A, B, C]
  ) extends RequestInput[C] {
    lazy val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.RequestInput.ZipWith")

    def schema[A, B, C] =
      Schema.CaseClass3[RequestInput[A], RequestInput[B], Zipper.WithOut[A, B, C], ZipWith[A, B, C]](
        typeId,
        Schema.Field("left", Schema.defer(RequestInput.schema[A]), get0 = _.left, set0 = (a, b) => a.copy(left = b)),
        Schema.Field("right", Schema.defer(RequestInput.schema[B]), get0 = _.right, set0 = (a, b) => a.copy(right = b)),
        Schema.Field("zipper", Zipper.schema[A, B, C], get0 = _.zipper, set0 = (a, b) => a.copy(zipper = b)),
        ZipWith(_, _, _)
      )

    def schemaCase[A]: Schema.Case[RequestInput[A], ZipWith[Any, Any, Any]] =
      Schema.Case(
        "ZipWith",
        schema[Any, Any, Any],
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

  def schema[A]: Schema[Header[A]] = Schema.EnumN(
    typeId,
    CaseSet
      .Cons(SingleHeader.schemaCase[A], CaseSet.Empty[Header[A]]())
      .:+:(ZipWith.schemaCase[A])
      .:+:(Optional.schemaCase[A])
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

    def schema[A]: Schema[SingleHeader[A]] = Schema.CaseClass2[String, FlowSchemaAst, SingleHeader[A]](
      typeId,
      Schema.Field("name", Schema[String], get0 = _.name, set0 = (a, b) => a.copy(name = b)),
      Schema.Field(
        "schema",
        FlowSchemaAst.schema,
        get0 = header => FlowSchemaAst.fromSchema(header.schema),
        set0 = (a, b) => a.copy(schema = b.toSchema)
      ),
      (name, schema) => SingleHeader(name, schema.toSchema[A])
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

    lazy val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header.ZipWith")

    def schema[A, B, C] =
      Schema.CaseClass3[Header[A], Header[B], Zipper.WithOut[A, B, C], ZipWith[A, B, C]](
        typeId,
        Schema.Field("left", Schema.defer(Header.schema[A]), get0 = _.left, set0 = (a, b) => a.copy(left = b)),
        Schema.Field("right", Schema.defer(Header.schema[B]), get0 = _.right, set0 = (a, b) => a.copy(right = b)),
        Schema.Field("zipper", Zipper.schema[A, B, C], get0 = _.zipper, set0 = (a, b) => a.copy(zipper = b)),
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
    lazy val schema: Schema[Option[A]] = Schema.option(headers.schema)
  }

  object Optional {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header.Optional")

    def schema[A]: Schema[Optional[A]] = Schema.CaseClass1[Header[A], Optional[A]](
      typeId,
      Schema.Field("headers", Schema.defer(Header.schema[A]), get0 = _.headers, set0 = (a, b) => a.copy(headers = b)),
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
      set0 = (a, b) => a.copy(schema = b.toSchema)
    ),
    Schema.Field("contentType", Schema[ContentType], get0 = _.contentType, set0 = (a, b) => a.copy(contentType = b)),
    (ast, contentType) => Body(ast.toSchema[A], contentType)
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

  def schema[A]: Schema[Query[A]] = Schema.EnumN(
    typeId,
    CaseSet
      .Cons(SingleParam.schemaCase[A], CaseSet.Empty[Query[A]]())
      .:+:(ZipWith.schemaCase[A])
      .:+:(Optional.schemaCase[A])
  )

  def schemaCase[A]: Schema.Case[RequestInput[A], Query[A]] =
    Schema.Case("Query", schema[A], _.asInstanceOf[Query[A]], _.asInstanceOf[RequestInput[A]], _.isInstanceOf[Query[A]])

  private[flow] final case class SingleParam[A](name: String, override val schema: Schema[A]) extends Query[A]

  object SingleParam {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.SingleParam")

    def schema[A]: Schema[SingleParam[A]] = Schema.CaseClass2[String, FlowSchemaAst, SingleParam[A]](
      typeId,
      Schema.Field("name", Schema[String], get0 = _.name, set0 = (a, b) => a.copy(name = b)),
      Schema.Field(
        "schema",
        FlowSchemaAst.schema,
        get0 = param => FlowSchemaAst.fromSchema(param.schema),
        set0 = (a, b) => a.copy(schema = b.toSchema)
      ),
      (name, schema) => SingleParam(name, schema.toSchema[A])
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
    val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.ZipWith")

    def schema[A, B, C] =
      Schema.CaseClass3[Query[A], Query[B], Zipper.WithOut[A, B, C], ZipWith[A, B, C]](
        typeId,
        Schema.Field("left", Schema.defer(Query.schema[A]), get0 = _.left, set0 = (a, b) => a.copy(left = b)),
        Schema.Field("right", Schema.defer(Query.schema[B]), get0 = _.right, set0 = (a, b) => a.copy(right = b)),
        Schema.Field("zipper", Zipper.schema[A, B, C], get0 = _.zipper, set0 = (a, b) => a.copy(zipper = b)),
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
    lazy val schema: Schema[Option[A]] = Schema.option(params.schema)
  }

  object Optional {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.Optional")

    def schema[A]: Schema[Optional[A]] = Schema.CaseClass1[Query[A], Optional[A]](
      typeId,
      Schema.Field("params", Schema.defer(Query.schema[A]), get0 = _.params, set0 = (a, b) => a.copy(params = b)),
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

  def schema[A]: Schema[Path[A]] = Schema.EnumN(
    typeId,
    CaseSet
      .Cons(Literal.schemaCase[A], CaseSet.Empty[Path[A]]())
      .:+:(Match.schemaCase[A])
      .:+:(ZipWith.schemaCase[A])
  )

  def schemaCase[A]: Schema.Case[RequestInput[A], Path[A]] =
    Schema.Case("Path", schema[A], _.asInstanceOf[Path[A]], _.asInstanceOf[RequestInput[A]], _.isInstanceOf[Path[A]])

  private[flow] final case class Literal(string: String) extends Path[Unit] {
    lazy val schema: Schema[Unit] = Schema.singleton(())
  }

  object Literal {
    def schema: Schema[Literal] = DeriveSchema.gen[Literal]

    def schemaCase[A]: Schema.Case[Path[A], Literal] =
      Schema.Case("Literal", schema, _.asInstanceOf[Literal], _.asInstanceOf[Path[A]], _.isInstanceOf[Literal])
  }

  private[flow] final case class Match[A](schema: Schema[A]) extends Path[A]

  object Match {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Path.Match")

    def schema[A]: Schema[Match[A]] = Schema.CaseClass1[FlowSchemaAst, Match[A]](
      typeId,
      Schema.Field(
        "schema",
        FlowSchemaAst.schema,
        get0 = m => FlowSchemaAst.fromSchema(m.schema),
        set0 = (a, b) => a.copy(schema = b.toSchema)
      ),
      schema => Match(schema.toSchema[A])
    )

    def schemaCase[A]: Schema.Case[Path[A], Match[A]] =
      Schema.Case("Match", schema[A], _.asInstanceOf[Match[A]], _.asInstanceOf[Path[A]], _.isInstanceOf[Match[A]])
  }

  private[flow] final case class ZipWith[A, B, C](left: Path[A], right: Path[B], zipper: Zipper.WithOut[A, B, C])
      extends Path[C] {
    lazy val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Path.ZipWith")

    def schema[A, B, C]: Schema[ZipWith[A, B, C]] =
      Schema.CaseClass3[Path[A], Path[B], Zipper.WithOut[A, B, C], ZipWith[A, B, C]](
        typeId,
        Schema.Field("left", Schema.defer(Path.schema[A]), get0 = _.left, set0 = (a, b) => a.copy(left = b)),
        Schema.Field("right", Schema.defer(Path.schema[B]), get0 = _.right, set0 = (a, b) => a.copy(right = b)),
        Schema.Field("zipper", Zipper.schema[A, B, C], get0 = _.zipper, set0 = (a, b) => a.copy(zipper = b)),
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
