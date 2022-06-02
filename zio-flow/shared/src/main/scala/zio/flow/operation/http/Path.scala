package zio.flow.operation.http

import zhttp.http.{Request, Header => ZHeader}
import zio.schema.Schema

import scala.language.implicitConversions
import zio.Zippable
import zio.Unzippable
import zio.json.JsonCodec

/** A RequestInput is a description of a Path, Query Parameters, and Headers and Body
  *   - Path: /users/:id/posts
  *   - Query Parameters: ?page=1&limit=10
  *   - Headers: X-User-Id: 1 or Accept: application/json
  */
sealed trait RequestInput[A] extends Product with Serializable { self =>
  private[http] def ++[B](that: RequestInput[B])(implicit unzipper: Unzippable[A, B]): RequestInput[unzipper.In] =
    RequestInput.ZipWith[A, B, unzipper.In](self, that, unzipper.unzip)
}

object RequestInput {
  private[http] final case class ZipWith[A, B, C](
      left: RequestInput[A],
      right: RequestInput[B],
      g: C => (A, B)
  ) extends RequestInput[C]
}

/** =HEADERS=
  */
sealed trait Header[A] extends RequestInput[A] {
  self =>

  def ? : Header[Option[A]] =
    Header.Optional(self)

  def ++[B](that: Header[B])(implicit unzipper: Unzippable[A, B]): Header[unzipper.In] =
    Header.ZipWith[A, B, unzipper.In](self, that, unzipper.unzip)
}

object Header {
  def AcceptEncoding: Header[String] = string("Accept-Encoding")
  def UserAgent: Header[String]      = string("User-Agent")
  def Host: Header[String]           = string("Host")
  def Accept: Header[String]         = string("Accept")

  def string(name: String): Header[String] = SingleHeader(name)

  private[http] final case class SingleHeader[A](name: String) extends Header[A] 

  private[http] final case class ZipWith[A, B, C](left: Header[A], right: Header[B], g: C => (A, B))
      extends Header[C]

  private[http] case class Optional[A](headers: Header[A]) extends Header[Option[A]]
}

case class Body[A](codec: JsonCodec[A], schema: Schema[A]) extends RequestInput[A] { self =>
  def ++[B](that: Body[B]): Body[B] = that
}

/** QUERY PARAMS
  * ============
  */
sealed trait Query[A] extends RequestInput[A] { self =>
  def ? : Query[Option[A]] = Query.Optional(self)

  def ++[B](that: Query[B])(unzipper: Unzippable[A, B]): Query[unzipper.In] =
    Query.ZipWith[A, B, unzipper.In](self, that, unzipper.unzip)
}

object Query {

  private[http] final case class SingleParam[A](name: String, schema: Schema[A]) extends Query[A]

  private[http] final case class ZipWith[A, B, C](left: Query[A], right: Query[B], g: C => (A, B))
      extends Query[C]

  private[http] case class Optional[A](params: Query[A]) extends Query[Option[A]]
}

/** A DSL for describe Paths
  *   - ex: /users
  *   - ex: /users/:id/friends
  *   - ex: /users/:id/friends/:friendId
  *   - ex: /posts/:id/comments/:commentId
  */
sealed trait Path[A] extends RequestInput[A] { self =>
  def /[B](that: Path[B])(implicit unzipper: Unzippable[A, B]): Path[unzipper.In] =
    Path.ZipWith[A, B, unzipper.In](this, that, unzipper.unzip)

  def /(string: String): Path[A] =
    Path.ZipWith(this, Path.path(string), a => (a, ()))
}

final case class PathState(var input: List[String])

object Path {
  def path(name: String): Path[Unit] = Path.Literal(name).asInstanceOf[Path[Unit]]

  private[http] final case class Literal(string: String) extends Path[Any]

  private[http] final case class Match[A](name: String, schema: Schema[A]) extends Path[A]

  private[http] final case class ZipWith[A, B, C](left: Path[A], right: Path[B], g: C => (A, B))
      extends Path[C]
}