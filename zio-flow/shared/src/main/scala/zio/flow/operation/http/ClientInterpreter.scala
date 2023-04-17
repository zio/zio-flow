package zio.flow.operation.http

import zio.http.{Client, Request, Response, URL, Version}
import zio.{Chunk, ZIO}
import zio.schema.codec.JsonCodec.JsonEncoder

import scala.collection.mutable

private[http] object ClientInterpreter {
  def interpret[Input, Output](host: String)(
    api: API[Input, Output]
  )(input: Input): ZIO[Client, HttpFailure, Response] = {
    val method = api.method.toZioHttpMethod
    val state  = new RequestState()
    parseUrl(api.requestInput, state)(input)
    val (url, headers, body) = state.result
    val data                 = body.fold(zio.http.Body.empty)(zio.http.Body.fromChunk)
    ZIO
      .fromEither(URL.decode(s"$host$url"))
      .flatMap { url =>
        Client
          .request(
            Request(
              url = url,
              method = method,
              headers =
                zio.http.Headers(headers.toList.map { case (name, value) => zio.http.Header.Custom(name, value) }),
              body = data,
              version = Version.`HTTP/1.1`,
              remoteAddress = None
            )
          )
      }
      .mapError(HttpFailure.FailedToSendRequest)
  }

  private[http] class RequestState {
    private val query: mutable.Map[String, String]   = mutable.Map.empty
    private val headers: mutable.Map[String, String] = mutable.Map.empty
    private val pathBuilder: mutable.StringBuilder   = new mutable.StringBuilder()
    private var body: Option[Chunk[Byte]]            = None

    def addPath(path: String): Unit =
      pathBuilder ++= path

    def addQuery(key: String, value: String): Option[String] =
      query.put(key, value)

    def addHeader(key: String, value: String): Option[String] =
      headers.put(key, value)

    def setBody(body: Chunk[Byte], contentType: String): Unit = {
      this.headers.put("Content-Type", contentType)
      this.body = Option(body)
    }

    def result: (String, Map[String, String], Option[Chunk[Byte]]) = {

      val queryString =
        if (query.nonEmpty)
          query.map { case (key, value) => s"$key=$value" }.mkString("?", "&", "")
        else
          ""

      (pathBuilder.result() + queryString, headers.toMap, body)
    }
  }

  private[http] def parseUrl[Params](
    requestInput: RequestInput[Params],
    state: RequestState
  )(params: Params): Any =
    requestInput match {
      case RequestInput.ZipWith(left, right, zipper) =>
        zipper.unzip(params) match {
          case (a, b) =>
            parseUrl(left, state)(a)
            parseUrl(right, state)(b)
        }
      case headers: Header[_] =>
        parseHeaders[Params](headers, state)(params)

      case query: Query[_] =>
        parseQuery[Params](query, state)(params)

      case route: Path[_] =>
        parsePath[Params](route, state)(params)

      case body: Body[_] =>
        parseBody[Params](body, state)(params)
    }

  private def parsePath[Params](
    route: Path[Params],
    state: RequestState
  )(params: Params): Unit =
    route match {
      case Path.ZipWith(left, right, zipper) =>
        zipper.unzip(params) match {
          case (a, b) =>
            parsePath(left, state)(a)
            parsePath(right, state)(b)
        }
      case Path.Literal(literal) =>
        state.addPath(literal)
      case Path.Match(_) =>
        state.addPath("/" + params.toString)
    }

  private def parseQuery[Params](
    query: Query[Params],
    state: RequestState
  )(params: Params): Any =
    query match {
      case Query.SingleParam(name, _) =>
        state.addQuery(name, params.toString)
      case Query.Optional(p) =>
        params.asInstanceOf[Option[Any]] match {
          case Some(params) =>
            parseQuery(p, state)(params)
          case None =>
            ()
        }

      case Query.ZipWith(left, right, zipper) =>
        zipper.unzip(params) match {
          case (a, b) =>
            parseQuery(left, state)(a)
            parseQuery(right, state)(b)
        }
    }

  private def parseHeaders[Params](
    headers: Header[Params],
    state: RequestState
  )(params: Params): Any =
    headers match {
      case Header.ZipWith(left, right, zipper) =>
        zipper.unzip(params) match {
          case (a, b) =>
            parseHeaders(left, state)(a)
            parseHeaders(right, state)(b)
        }
      case Header.Optional(headers) =>
        params.asInstanceOf[Option[Any]] match {
          case Some(params) =>
            parseHeaders(headers, state)(params)
          case None =>
            ()
        }
      case Header.SingleHeader(name, _) =>
        state.addHeader(name, params.toString)
    }

  private def parseBody[Params](
    body: Body[Params],
    state: RequestState
  )(params: Params): Unit =
    body.contentType match {
      case ContentType.json =>
        state.setBody(JsonEncoder.encode(body.schema, params), "application/json")
      case ContentType.`x-www-form-urlencoded` =>
        state.setBody(FormUrlEncodedEncoder.encode(body.schema)(params), "application/x-www-form-urlencoded")
    }
}
