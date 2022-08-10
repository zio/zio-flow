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

package zio.flow.debug

import zio.flow.{BindingName, Remote, RemoteVariableName, ZFlow}

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
      val _ = builder.append("\n").append(" " * i)
    }

    val _ = remote match {
      case Remote.Literal(value) =>
        value.toTypedValue(ZFlow.schemaAny) match {
          case Left(value) =>
            builder.append(value.toString)
          case Right(flow) =>
            prettyPrintFlow(flow, builder, indent)
        }
      case Remote.Flow(flow) =>
        builder.append("flow")
        prettyPrintFlow(flow, builder, indent + 2)
      case Remote.Nested(remote) =>
        builder.append("nested ")
        prettyPrintRemote(remote, builder, indent)
      case Remote.Ignore() =>
        builder.append("ignore")
      case Remote.Variable(identifier) =>
        builder.append("[[")
        builder.append(RemoteVariableName.unwrap(identifier))
        builder.append("]]")
      case Remote.Unbound(identifier) =>
        builder.append("<")
        builder.append(BindingName.unwrap(identifier))
        builder.append(">")
      case Remote.UnboundRemoteFunction(input, result) =>
        prettyPrintRemote(input, builder, indent)
        builder.append(" => ")
        prettyPrintRemote(result, builder, indent)
      case Remote.EvaluateUnboundRemoteFunction(f, a) =>
        prettyPrintRemote(f, builder, indent)
        builder.append(" called with ")
        prettyPrintRemote(a, builder, indent)
      case Remote.UnaryNumeric(value, _, operator) =>
        builder.append(operator)
        prettyPrintRemote(value, builder, indent)
      case Remote.BinaryNumeric(left, right, _, operator) =>
        prettyPrintRemote(left, builder, indent)
        builder.append(operator)
        prettyPrintRemote(right, builder, indent)
      case Remote.UnaryFractional(value, _, operator) =>
        builder.append(operator)
        prettyPrintRemote(value, builder, indent)
      case Remote.RemoteEither(either) =>
        either match {
          case Left(value) =>
            builder.append("left[")
            prettyPrintRemote(value, builder, indent)
            builder.append("]")
          case Right(value) =>
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
          case Left(value) =>
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
      case Remote.Tuple3(t1, t2, t3) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple4(t1, t2, t3, t4) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple5(t1, t2, t3, t4, t5) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple6(t1, t2, t3, t4, t5, t6) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple7(t1, t2, t3, t4, t5, t6, t7) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple8(t1, t2, t3, t4, t5, t6, t7, t8) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple9(t1, t2, t3, t4, t5, t6, t7, t8, t9) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple10(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple11(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple12(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple13(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple14(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple15(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t15, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple16(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t15, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t16, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple17(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t15, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t16, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t17, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple18(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t15, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t16, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t17, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t18, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple19(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t15, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t16, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t17, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t18, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t19, builder, indent + 2)
        builder.append(")")
      case Remote.Tuple20(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11, t12, t13, t14, t15, t16, t17, t18, t19, t20) =>
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t15, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t16, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t17, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t18, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t19, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t20, builder, indent + 2)
        builder.append(")")
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
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t15, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t16, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t17, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t18, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t19, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t20, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t21, builder, indent + 2)
        builder.append(")")
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
        builder.append("(")
        nl(indent + 2)
        prettyPrintRemote(t1, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t2, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t3, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t4, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t5, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t6, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t7, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t8, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t9, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t10, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t11, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t12, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t13, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t14, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t15, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t16, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t17, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t18, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t19, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t20, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t21, builder, indent + 2)
        nl(indent + 2)
        prettyPrintRemote(t22, builder, indent + 2)
        builder.append(")")
      case Remote.TupleAccess(tuple, n) =>
        prettyPrintRemote(tuple, builder, indent)
        builder.append("(")
        builder.append(n)
        builder.append(")")
      case Remote.Branch(_, _, _) => ???
      case Remote.Length(_)       => ???
      case Remote.LessThanEqual(left, right, _) =>
        prettyPrintRemote(left, builder, indent)
        builder.append("<=")
        prettyPrintRemote(right, builder, indent)
      case Remote.Equal(left, right) =>
        prettyPrintRemote(left, builder, indent)
        builder.append("==")
        prettyPrintRemote(right, builder, indent)
      case Remote.Not(value) =>
        builder.append("NOT ")
        prettyPrintRemote(value, builder, indent)
      case Remote.And(left, right) =>
        prettyPrintRemote(left, builder, indent)
        builder.append("&&")
        prettyPrintRemote(right, builder, indent)
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
      case Remote.UnCons(_)                     => ???
      case Remote.InstantFromLongs(_, _)        => ???
      case Remote.InstantFromString(_)          => ???
      case Remote.InstantToTuple(_)             => ???
      case Remote.InstantPlusDuration(_, _)     => ???
      case Remote.InstantTruncate(_, _)         => ???
      case Remote.DurationFromString(_)         => ???
      case Remote.DurationBetweenInstants(_, _) => ???
      case Remote.DurationFromBigDecimal(_)     => ???
      case Remote.DurationFromLongs(_, _)       => ???
      case Remote.DurationFromAmount(_, _)      => ???
      case Remote.DurationToLongs(_)            => ???
      case Remote.DurationPlusDuration(_, _)    => ???
      case Remote.DurationMultipliedBy(_, _)    => ???
      case Remote.Iterate(_, _, _)              => ???
      case Remote.Lazy(value) =>
        prettyPrintRemote(value(), builder, indent)
      case Remote.RemoteSome(_)       => ???
      case Remote.FoldOption(_, _, _) => ???
      case Remote.Recurse(_, _, _)    => ???
      case Remote.RecurseWith(_, _)   => ???
    }
  }

  def prettyPrintFlow[R, E, A](flow: ZFlow[R, E, A], builder: mutable.StringBuilder, indent: Int): Unit = {
    def nl(i: Int): Unit = {
      val _ = builder.append("\n").append(" " * i)
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
      case ZFlow.Read(svar) =>
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
