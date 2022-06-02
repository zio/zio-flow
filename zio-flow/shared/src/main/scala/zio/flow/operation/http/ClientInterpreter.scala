package zio.flow.operation.http

import zhttp.http.HttpData
import zhttp.service.{ChannelFactory, Client, EventLoopGroup}
import zio.ZIO

import scala.collection.mutable
import zhttp.http.Response
import zio.Chunk

private[http] object ClientInterpreter {
  def interpret[Input, Output](host: String)(
      api: API[Input, Output]
  )(input: Input): ZIO[EventLoopGroup with ChannelFactory, Throwable, Response] = {
    val method = api.method.toZioHttpMethod
    val state  = new RequestState()
    parseUrl(api.requestInput, state)(input)
    val (url, headers, body) = state.result
    val data = body.fold(HttpData.empty)(HttpData.fromChunk)
    Client.request(s"$host$url", method, zhttp.http.Headers(headers.toList), content = data)
  }

  private[http] class RequestState {
    private val query: mutable.Map[String, String]   = mutable.Map.empty
    private val headers: mutable.Map[String, String] = mutable.Map.empty
    private val pathBuilder: StringBuilder           = new StringBuilder()
    private var body: Option[Chunk[Byte]]            = None

    def addPath(path: String): Unit =
      pathBuilder.addAll(path)

    def addQuery(key: String, value: String): Unit =
      query.put(key, value)

    def addHeader(key: String, value: String): Unit =
      headers.put(key, value)

    def setBody(body: Chunk[Byte]): Unit =
      this.body = Option(body)

    def result: (String, Map[String, String], Option[Chunk[Byte]]) = {

      val queryString =
        if (query.nonEmpty)
          query.map { case (key, value) => s"$key=$value" }.mkString("?", "&", "")
        else
          ""

      (pathBuilder.result + queryString, headers.toMap, body)
    }
  }

  private[http] def parseUrl[Params](
      requestInput: RequestInput[Params],
      state: RequestState
  )(params: Params): Unit =
    requestInput match {
      case RequestInput.ZipWith(left, right, g) =>
        g(params) match {
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
    }

  private def parsePath[Params](
      route: Path[Params],
      state: RequestState
  )(params: Params): Unit =
    route match {
      case Path.ZipWith(left, right, g) =>
        g(params) match {
          case (a, b) =>
            parsePath(left, state)(a)
            parsePath(right, state)(b)
        }
      case Path.Literal(literal) =>
        state.addPath("/" + literal)
      case Path.Match(_, _) =>
        state.addPath("/" + params.toString)
    }

  private def parseQuery[Params](
      query: Query[Params],
      state: RequestState
  )(params: Params): Unit =
    query match {
      case Query.SingleParam(name, _) =>
        state.addQuery(name, params.toString)
      case Query.Optional(p) =>
        params match {
          case Some(params) =>
            parseQuery(p, state)(params)
          case None =>
            ()
        }

      case Query.ZipWith(left, right, g) =>
        g(params) match {
          case (a, b) =>
            parseQuery(left, state)(a)
            parseQuery(right, state)(b)
        }
    }

  private def parseHeaders[Params](
      headers: Header[Params],
      state: RequestState
  )(params: Params): Unit =
    headers match {
      case Header.ZipWith(left, right, g) =>
        g(params) match {
          case (a, b) =>
            parseHeaders(left, state)(a)
            parseHeaders(right, state)(b)
        }
      case Header.Optional(headers) =>
        params match {
          case Some(params) =>
            parseHeaders(headers, state)(params)
          case None =>
            ()
        }
      case Header.SingleHeader(name) =>
        state.addHeader(name, params.toString)
    }
}
