package zio.flow.remote

import zio.flow.Remote

final class RemoteStringInterpolator(val ctx: StringContext) extends AnyVal {

  def rs(args: Remote[String]*): Remote[String] = {
    val staticParts   = ctx.parts.map(Left.apply)
    val injectedParts = args.map(Right.apply)
    val parts         = staticParts.zipAll(injectedParts, Left(""), Right(Remote(""))).flatMap { case (a, b) => List(a, b) }
    val remoteParts = parts.map {
      case Left(value)  => Remote(value)
      case Right(value) => value
    }
    remoteParts.foldLeft(Remote(""))(_ ++ _)
  }
}
