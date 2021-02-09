package zio.flow

trait ExprEither[+A] { self: Expr[A] =>
  final def either[B, C, D](left: Expr[B] => Expr[D], right: Expr[C] => Expr[D])(implicit
    ev: A <:< Either[B, C]
  ): Expr[D] =
    Expr.FoldEither(self.widen[Either[B, C]], left, right)

  final def left: Expr[Either[A, Nothing]] = Expr.Either0(Left(self))

  final def right: Expr[Either[Nothing, A]] = Expr.Either0(Right(self))
}
