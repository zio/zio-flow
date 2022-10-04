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

  private[http] def ++[B](that: RequestInput[B])(implicit zipper: Zipper[A, B]): RequestInput[zipper.Out] =
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

  private[http] final case class ZipWith[A, B, C](
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
        Schema.Field("left", Schema.defer(RequestInput.schema[A])),
        Schema.Field("right", Schema.defer(RequestInput.schema[B])),
        Schema.Field("zipper", Zipper.schema[A, B, C]),
        ZipWith(_, _, _),
        _.left,
        _.right,
        _.zipper
      )

    def schemaCase[A]: Schema.Case[ZipWith[Any, Any, Any], RequestInput[A]] =
      Schema.Case("ZipWith", schema[Any, Any, Any], _.asInstanceOf[ZipWith[Any, Any, Any]])
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

  def schemaCase[A]: Schema.Case[Header[A], RequestInput[A]] =
    Schema.Case("Header", schema[A], _.asInstanceOf[Header[A]])

  private[http] final case class SingleHeader[A](name: String, override val schema: Schema[A]) extends Header[A]
  object SingleHeader {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header.SingleHeader")

    def schema[A]: Schema[SingleHeader[A]] = Schema.CaseClass2[String, FlowSchemaAst, SingleHeader[A]](
      typeId,
      Schema.Field("name", Schema[String]),
      Schema.Field("schema", FlowSchemaAst.schema),
      (name, schema) => SingleHeader(name, schema.toSchema[A]),
      _.name,
      header => FlowSchemaAst.fromSchema(header.schema)
    )

    def schemaCase[A]: Schema.Case[SingleHeader[A], Header[A]] =
      Schema.Case("SingleHeader", schema[A], _.asInstanceOf[SingleHeader[A]])
  }

  private[http] final case class ZipWith[A, B, C](left: Header[A], right: Header[B], zipper: Zipper.WithOut[A, B, C])
      extends Header[C] {

    lazy val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header.ZipWith")

    def schema[A, B, C] =
      Schema.CaseClass3[Header[A], Header[B], Zipper.WithOut[A, B, C], ZipWith[A, B, C]](
        typeId,
        Schema.Field("left", Schema.defer(Header.schema[A])),
        Schema.Field("right", Schema.defer(Header.schema[B])),
        Schema.Field("zipper", Zipper.schema[A, B, C]),
        ZipWith(_, _, _),
        _.left,
        _.right,
        _.zipper
      )

    def schemaCase[A]: Schema.Case[ZipWith[Any, Any, Any], Header[A]] =
      Schema.Case("ZipWith", schema[Any, Any, Any], _.asInstanceOf[ZipWith[Any, Any, Any]])

  }

  private[http] case class Optional[A](headers: Header[A]) extends Header[Option[A]] {
    lazy val schema: Schema[Option[A]] = Schema.option(headers.schema)
  }

  object Optional {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Header.Optional")

    def schema[A]: Schema[Optional[A]] = Schema.CaseClass1(
      typeId,
      Schema.Field("headers", Schema.defer(Header.schema[A])),
      Optional.apply,
      _.headers
    )

    def schemaCase[A]: Schema.Case[Optional[A], Header[A]] =
      Schema.Case("Optional", schema[A], _.asInstanceOf[Optional[A]])
  }
}

case class Body[A](override val schema: Schema[A]) extends RequestInput[A] { self =>
  def ++[B](that: Body[B]): Body[B] = that
}

object Body {
  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Body")

  def schema[A]: Schema[Body[A]] = Schema.CaseClass1[FlowSchemaAst, Body[A]](
    typeId,
    Schema.Field("schema", FlowSchemaAst.schema),
    a => Body(a.toSchema[A]),
    s => FlowSchemaAst.fromSchema(s.schema)
  )

  def schemaCase[A]: Schema.Case[Body[A], RequestInput[A]] =
    Schema.Case("Body", schema[A], _.asInstanceOf[Body[A]])
}

/**
 * =QUERY PARAMS=
 */
sealed trait Query[A] extends RequestInput[A] { self =>
  def ? : Query[Option[A]] = Query.Optional(self)

  def ++[B](that: Query[B])(zipper: Zipper[A, B]): Query[zipper.Out] =
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

  def schemaCase[A]: Schema.Case[Query[A], RequestInput[A]] =
    Schema.Case("Query", schema[A], _.asInstanceOf[Query[A]])

  private[http] final case class SingleParam[A](name: String, override val schema: Schema[A]) extends Query[A]

  object SingleParam {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.SingleParam")

    def schema[A]: Schema[SingleParam[A]] = Schema.CaseClass2[String, FlowSchemaAst, SingleParam[A]](
      typeId,
      Schema.Field("name", Schema[String]),
      Schema.Field("schema", FlowSchemaAst.schema),
      (name, schema) => SingleParam(name, schema.toSchema[A]),
      _.name,
      param => FlowSchemaAst.fromSchema(param.schema)
    )

    def schemaCase[A]: Schema.Case[SingleParam[A], Query[A]] =
      Schema.Case("SingleParam", schema[A], _.asInstanceOf[SingleParam[A]])

  }

  private[http] final case class ZipWith[A, B, C](left: Query[A], right: Query[B], zipper: Zipper.WithOut[A, B, C])
      extends Query[C] {
    val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.ZipWith")

    def schema[A, B, C] =
      Schema.CaseClass3[Query[A], Query[B], Zipper.WithOut[A, B, C], ZipWith[A, B, C]](
        typeId,
        Schema.Field("left", Schema.defer(Query.schema[A])),
        Schema.Field("right", Schema.defer(Query.schema[B])),
        Schema.Field("zipper", Zipper.schema[A, B, C]),
        ZipWith(_, _, _),
        _.left,
        _.right,
        _.zipper
      )

    def schemaCase[A]: Schema.Case[ZipWith[Any, Any, Any], Query[A]] =
      Schema.Case("ZipWith", schema[Any, Any, Any], _.asInstanceOf[ZipWith[Any, Any, Any]])

  }

  private[http] case class Optional[A](params: Query[A]) extends Query[Option[A]] {
    lazy val schema: Schema[Option[A]] = Schema.option(params.schema)
  }

  object Optional {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Query.Optional")

    def schema[A]: Schema[Optional[A]] = Schema.CaseClass1(
      typeId,
      Schema.Field("params", Schema.defer(Query.schema[A])),
      Optional.apply,
      _.params
    )

    def schemaCase[A]: Schema.Case[Optional[A], Query[A]] =
      Schema.Case("Optional", schema[A], _.asInstanceOf[Optional[A]])
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
}

final case class PathState(var input: List[String])

object Path {
  def path(name: String): Path[Unit] = Path.Literal(name).asInstanceOf[Path[Unit]]

  private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Path")

  def schema[A]: Schema[Path[A]] = Schema.EnumN(
    typeId,
    CaseSet
      .Cons(Literal.schemaCase[A], CaseSet.Empty[Path[A]]())
      .:+:(Match.schemaCase[A])
      .:+:(ZipWith.schemaCase[A])
  )

  def schemaCase[A]: Schema.Case[Path[A], RequestInput[A]] =
    Schema.Case("Path", schema[A], _.asInstanceOf[Path[A]])

  private[http] final case class Literal(string: String) extends Path[Unit] {
    lazy val schema: Schema[Unit] = Schema.singleton(())
  }

  object Literal {
    def schema: Schema[Literal] = DeriveSchema.gen[Literal]

    def schemaCase[A]: Schema.Case[Literal, Path[A]] =
      Schema.Case("Literal", schema, _.asInstanceOf[Literal])
  }

  private[http] final case class Match[A](schema: Schema[A]) extends Path[A]

  object Match {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Path.Match")

    def schema[A]: Schema[Match[A]] = Schema.CaseClass1[FlowSchemaAst, Match[A]](
      typeId,
      Schema.Field("schema", FlowSchemaAst.schema),
      schema => Match(schema.toSchema[A]),
      m => FlowSchemaAst.fromSchema(m.schema)
    )

    def schemaCase[A]: Schema.Case[Match[A], Path[A]] =
      Schema.Case("Match", schema[A], _.asInstanceOf[Match[A]])
  }

  private[http] final case class ZipWith[A, B, C](left: Path[A], right: Path[B], zipper: Zipper.WithOut[A, B, C])
      extends Path[C] {
    lazy val schema: Schema[C] = zipper.zipSchema(left.schema, right.schema)
  }

  object ZipWith {
    private val typeId: TypeId = TypeId.parse("zio.flow.operation.http.Path.ZipWith")

    def schema[A, B, C]: Schema[ZipWith[A, B, C]] =
      Schema.CaseClass3[Path[A], Path[B], Zipper.WithOut[A, B, C], ZipWith[A, B, C]](
        typeId,
        Schema.Field("left", Schema.defer(Path.schema[A])),
        Schema.Field("right", Schema.defer(Path.schema[B])),
        Schema.Field("zipper", Zipper.schema[A, B, C]),
        ZipWith(_, _, _),
        _.left,
        _.right,
        _.zipper
      )

    def schemaCase[A]: Schema.Case[ZipWith[Any, Any, Any], Path[A]] =
      Schema.Case("ZipWith", schema[Any, Any, Any], _.asInstanceOf[ZipWith[Any, Any, Any]])
  }
}
