package zio.flow.debug

import zio.flow.{BindingName, LocalVariableName, Remote, RemoteVariableName, ZFlow}

import scala.collection.mutable

object PrettyPrint {

  def prettyPrintRemote[A](remote: Remote[A]): String = {
    val builder = new mutable.StringBuilder()
    prettyPrintRemote(remote, builder, indent = 0)
    builder.toString
  }

  def prettyPrintFlow[R, E, A](flow: ZFlow[R, E, A]): String = {
    val builder = new mutable.StringBuilder()
    prettyPrintFlow(flow, builder, indent = 0)
    builder.toString
  }

  def prettyPrintRemote[A](remote: Remote[A], builder: mutable.StringBuilder, indent: Int): Unit = {
    def nl(i: Int): Unit = {
      val _ = builder.append("\n")
      val _ = builder.append(" " * i)
    }

    val _ = remote match {
      case Remote.Literal(value, schemaA) =>
        if (schemaA eq ZFlow.schemaAny) {
          value.toTypedValue(ZFlow.schemaAny) match {
            case Left(value) =>
              builder.append(s"!! $value")
            case Right(flow) =>
              prettyPrintFlow(flow, builder, indent)
          }
        } else {
          builder.append(value.toString)
        }
      case Remote.Flow(flow) =>
        builder.append("flow")
        prettyPrintFlow(flow, builder, indent + 2)
      case Remote.Nested(remote) =>
        builder.append("nested ")
        prettyPrintRemote(remote, builder, indent)
      case Remote.Ignore() =>
        builder.append("ignore")
      case Remote.Variable(identifier, schemaA) =>
        builder.append("[[")
        builder.append(RemoteVariableName.unwrap(identifier))
        builder.append("]]")
      case Remote.Local(identifier, schemaA) =>
        builder.append("[")
        builder.append(LocalVariableName.unwrap(identifier))
        builder.append("]")
      case Remote.Unbound(identifier, schemaA) =>
        builder.append("<")
        builder.append(BindingName.unwrap(identifier))
        builder.append(">")
      case Remote.EvaluatedRemoteFunction(input, result) =>
        prettyPrintRemote(input, builder, indent)
        builder.append(" => ")
        prettyPrintRemote(result, builder, indent)
      case Remote.ApplyEvaluatedFunction(f, a) =>
        prettyPrintRemote(f, builder, indent)
        builder.append(" called with ")
        prettyPrintRemote(a, builder, indent)
      case Remote.UnaryNumeric(value, numeric, operator) =>
        builder.append(operator)
        prettyPrintRemote(value, builder, indent)
      case Remote.BinaryNumeric(left, right, numeric, operator) =>
        prettyPrintRemote(left, builder, indent)
        builder.append(operator)
        prettyPrintRemote(right, builder, indent)
      case Remote.UnaryFractional(value, fractional, operator) =>
        builder.append(operator)
        prettyPrintRemote(value, builder, indent)
      case Remote.RemoteEither(either) =>
        either match {
          case Left((value, _)) =>
            builder.append("left[")
            prettyPrintRemote(value, builder, indent)
            builder.append("]")
          case Right((_, value)) =>
            builder.append("right[")
            prettyPrintRemote(value, builder, indent)
            builder.append("]")
        }
      case Remote.FoldEither(either, left, right) =>
        builder.append("either ")
        nl(indent + 2)
        prettyPrintRemote(either, builder, indent + 2)
        nl(indent + 2)
        builder.append("on left: ")
        prettyPrintRemote(left, builder, indent + 2)
        nl(indent + 2)
        builder.append("on right: ")
        prettyPrintRemote(right, builder, indent + 2)
      case Remote.SwapEither(either) =>
        builder.append("swap ")
        prettyPrintRemote(either, builder, indent + 2)
      case Remote.Try(either) =>
        either match {
          case Left((value, _)) =>
            builder.append("failure[")
            prettyPrintRemote(value, builder, indent)
            builder.append("]")
          case Right(value) =>
            builder.append("success[")
            prettyPrintRemote(value, builder, indent)
            builder.append("]")
        }
      case Remote.Tuple2(t1, t2) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple3(t1, t2, t3)                                                                            => ???
      case Remote.Tuple4(t1, t2, t3, t4)                                                                        => ???
      case Remote.Tuple5(t1, t2, t3, t4, t5)                                                                    => ???
      case Remote.Tuple6(t1, t2, t3, t4, t5, t6)                                                                => ???
      case Remote.Tuple7(t1, t2, t3, t4, t5, t6, t7)                                                            => ???
      case Remote.Tuple8(t1, t2, t3, t4, t5, t6, t7, t8)                                                        => ???
      case Remote.Tuple9(t1, t2, t3, t4, t5, t6, t7, t8, t9)                                                    => ???
      case Remote.Tuple10(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)                                              => ???
      case Remote.Tuple11(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)                                         => ???
      case Remote.Tuple12(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12)                                    => ???
      case Remote.Tuple13(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13)                               => ???
      case Remote.Tuple14(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14)                          => ???
      case Remote.Tuple15(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15)                     => ???
      case Remote.Tuple16(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16)                => ???
      case Remote.Tuple17(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17)           => ???
      case Remote.Tuple18(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18)      => ???
      case Remote.Tuple19(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19) => ???
      case Remote.Tuple20(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20) =>
        ???
      case Remote.Tuple21(
            t1,
            t2,
            t3,
            t4,
            t5,
            t6,
            t7,
            t8,
            t9,
            t10,
            t11,
            t12,
            t13,
            t14,
            t15,
            t16,
            t17,
            t18,
            t19,
            t20,
            t21
          ) =>
        ???
      case Remote.Tuple22(
            t1,
            t2,
            t3,
            t4,
            t5,
            t6,
            t7,
            t8,
            t9,
            t10,
            t11,
            t12,
            t13,
            t14,
            t15,
            t16,
            t17,
            t18,
            t19,
            t20,
            t21,
            t22
          ) =>
        ???
      case Remote.TupleAccess(tuple, n) =>
        prettyPrintRemote(tuple, builder, indent)
        builder.append("(")
        builder.append(n)
        builder.append(")")
      case Remote.Branch(predicate, ifTrue, ifFalse) => ???
      case Remote.Length(remoteString)               => ???
      case Remote.LessThanEqual(left, right)         => ???
      case Remote.Equal(left, right)                 => ???
      case Remote.Not(value)                         => ???
      case Remote.And(left, right)                   => ???
      case Remote.Fold(list, initial, body) =>
        builder.append("RemoteFold[")
        prettyPrintRemote(list, builder, indent + 2)
        prettyPrintRemote(initial, builder, indent + 2)
        prettyPrintRemote(body, builder, indent + 2)
        builder.append("]")
      case Remote.Cons(list, head) =>
        prettyPrintRemote(head, builder, indent)
        builder.append(" :: ")
        prettyPrintRemote(list, builder, indent)
      case Remote.UnCons(list)                                          => ???
      case Remote.InstantFromLongs(seconds, nanos)                      => ???
      case Remote.InstantFromString(charSeq)                            => ???
      case Remote.InstantToTuple(instant)                               => ???
      case Remote.InstantPlusDuration(instant, duration)                => ???
      case Remote.InstantTruncate(instant, temporalUnit)                => ???
      case Remote.DurationFromString(charSeq)                           => ???
      case Remote.DurationBetweenInstants(startInclusive, endExclusive) => ???
      case Remote.DurationFromBigDecimal(seconds)                       => ???
      case Remote.DurationFromLongs(seconds, nanoAdjustment)            => ???
      case Remote.DurationFromAmount(amount, temporalUnit)              => ???
      case Remote.DurationToLongs(duration)                             => ???
      case Remote.DurationPlusDuration(left, right)                     => ???
      case Remote.DurationMultipliedBy(left, right)                     => ???
      case Remote.Iterate(initial, iterate, predicate)                  => ???
      case Remote.Lazy(value) =>
        prettyPrintRemote(value(), builder, indent)
      case Remote.RemoteSome(value)                       => ???
      case Remote.FoldOption(option, ifEmpty, ifNonEmpty) => ???
    }
  }

  def prettyPrintFlow[R, E, A](flow: ZFlow[R, E, A], builder: mutable.StringBuilder, indent: Int): Unit = {
    def nl(i: Int): Unit = {
      val _ = builder.append("\n")
      val _ = builder.append(" " * i)
    }

    nl(indent)
    val _ = flow match {
      case ZFlow.Return(value) =>
        builder.append("return (")
        prettyPrintRemote(value, builder, indent)
        builder.append(")")
      case ZFlow.Now =>
        builder.append("now")
      case ZFlow.WaitTill(time) =>
        builder.append("waitTill (")
        prettyPrintRemote(time, builder, indent)
        builder.append(")")
      case ZFlow.Read(svar, schema) =>
        builder.append("read (")
        prettyPrintRemote(svar, builder, indent)
        builder.append(")")
      case ZFlow.Modify(svar, f) =>
        builder.append("modify ")
        prettyPrintRemote(svar, builder, indent)
        builder.append(" with ")
        prettyPrintRemote(f, builder, indent)
      case ZFlow.Fold(value, ifError, ifSuccess) =>
        builder.append("fold\n")
        prettyPrintFlow(value, builder, indent + 2)
        nl(indent + 2)
        builder.append("ifError ")
        prettyPrintRemote(ifError, builder, indent + 2)
        nl(indent + 2)
        builder.append("ifSuccess ")
        prettyPrintRemote(ifSuccess, builder, indent + 2)
      case ZFlow.Log(message) =>
        builder.append("log ")
        prettyPrintRemote(message, builder, indent)
      case ZFlow.RunActivity(input, activity) =>
        builder.append("run ")
        builder.append(activity.toString)
        builder.append(" with ")
        prettyPrintRemote(input, builder, indent)
      case ZFlow.Transaction(workflow) =>
        builder.append("transaction")
        prettyPrintFlow(workflow, builder, indent + 2)
      case ZFlow.Input() =>
        builder.append("input")
      case ZFlow.Ensuring(flow, finalizer) =>
        builder.append("ensuring")
        prettyPrintFlow(flow, builder, indent + 2)
        builder.append("with finalizer")
        prettyPrintFlow(finalizer, builder, indent + 2)
      case ZFlow.Unwrap(remote) =>
        builder.append("unwrap ")
        prettyPrintRemote(remote, builder, indent)
      case ZFlow.UnwrapRemote(remote) =>
        builder.append("unwrap remote")
        prettyPrintRemote(remote, builder, indent)
      case ZFlow.Fork(flow) =>
        builder.append("fork")
        prettyPrintFlow(flow, builder, indent + 2)
      case ZFlow.Timeout(flow, duration) =>
        builder.append("timeout ")
        prettyPrintRemote(duration, builder, indent)
        prettyPrintFlow(flow, builder, indent + 2)
      case ZFlow.Provide(value, flow) =>
        builder.append("provide ")
        prettyPrintRemote(value, builder, indent)
        prettyPrintFlow(flow, builder, indent + 2)
      case ZFlow.Die =>
        builder.append("die")
      case ZFlow.RetryUntil =>
        builder.append("retry")
      case ZFlow.OrTry(left, right) =>
        builder.append("try")
        prettyPrintFlow(left, builder, indent + 2)
        builder.append("or")
        prettyPrintFlow(right, builder, indent + 2)
      case ZFlow.Await(exFlow) =>
        builder.append("await ")
        prettyPrintRemote(exFlow, builder, indent)
      case ZFlow.Interrupt(exFlow) =>
        builder.append("interrupt ")
        prettyPrintRemote(exFlow, builder, indent)
      case ZFlow.Fail(error) =>
        builder.append("fail ")
        prettyPrintRemote(error, builder, indent)
      case ZFlow.NewVar(name, initial) =>
        builder.append("newvar ")
        builder.append(name)
        builder.append(" with initial value ")
        prettyPrintRemote(initial, builder, indent)
      case ZFlow.Iterate(initial, step, predicate) =>
        builder.append("iterate from ")
        prettyPrintRemote(initial, builder, indent)
        builder.append(" by ")
        prettyPrintRemote(step, builder, indent)
        builder.append(" while ")
        prettyPrintRemote(predicate, builder, indent)
    }
  }
}
