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

package zio.flow.serialization

import zio.flow.{Remote, ZFlow}
import zio.schema.meta.MetaSchema.{Dynamic, FailNode, Lineage, Value}
import zio.schema.meta.{MetaSchema, NodePath}
import zio.schema.{CaseSet, DeriveSchema, Schema, TypeId}
import zio.{Chunk, ChunkBuilder}

import scala.annotation.{nowarn, tailrec}
import scala.collection.immutable.ListMap
import scala.collection.mutable

/**
 * Wrapper for MetaSchema to prevent serialization of some predefined zio-flow
 * types
 */
sealed trait FlowSchemaAst { self =>
  def path: NodePath
  def optional: Boolean

  def toAst: MetaSchema
  def toSchema[A]: Schema[A] = {
    val refMap = mutable.HashMap.empty[NodePath, Schema[_]]
    FlowSchemaAst.materialize(self, refMap).asInstanceOf[Schema[A]]
  }

}
object FlowSchemaAst {
  final case class RemoteAst(path: NodePath, optional: Boolean) extends FlowSchemaAst {
    override def toSchema[A]: Schema[A] =
      Remote.schemaAny.asInstanceOf[Schema[A]]

    override def toAst: MetaSchema = toSchema.ast
  }
  final case class FlowAst(path: NodePath, optional: Boolean) extends FlowSchemaAst {
    override def toSchema[A]: Schema[A] =
      ZFlow.schemaAny.asInstanceOf[Schema[A]]

    override def toAst: MetaSchema = toSchema.ast
  }

  final case class Product(id: TypeId, path: NodePath, fields: Chunk[(String, FlowSchemaAst)], optional: Boolean)
      extends FlowSchemaAst {
    override def toAst: MetaSchema =
      MetaSchema.Product(id, path, fields.map { case (label, fieldAst) => (label, fieldAst.toAst) }, optional)
  }

  final case class Tuple(path: NodePath, left: FlowSchemaAst, right: FlowSchemaAst, optional: Boolean)
      extends FlowSchemaAst {
    override def toAst: MetaSchema =
      MetaSchema.Tuple(path, left.toAst, right.toAst, optional)
  }

  final case class Sum(id: TypeId, path: NodePath, cases: Chunk[(String, FlowSchemaAst)], optional: Boolean)
      extends FlowSchemaAst {
    override def toAst: MetaSchema =
      MetaSchema.Sum(id, path, cases.map { case (label, caseAst) => (label, caseAst.toAst) }, optional)
  }

  final case class Either(path: NodePath, left: FlowSchemaAst, right: FlowSchemaAst, optional: Boolean)
      extends FlowSchemaAst {
    override def toAst: MetaSchema =
      MetaSchema.Either(path, left.toAst, right.toAst, optional)
  }

  final case class ListNode(item: FlowSchemaAst, path: NodePath, optional: Boolean) extends FlowSchemaAst {
    override def toAst: MetaSchema =
      MetaSchema.ListNode(item.toAst, path, optional)
  }

  final case class Dictionary(keys: FlowSchemaAst, values: FlowSchemaAst, path: NodePath, optional: Boolean)
      extends FlowSchemaAst {
    override def toAst: MetaSchema =
      MetaSchema.Dictionary(keys.toAst, values.toAst, path, optional)
  }

  final case class Other(toAst: MetaSchema) extends FlowSchemaAst {
    override def path: NodePath    = toAst.path
    override def optional: Boolean = toAst.optional
  }

  @tailrec
  def fromSchema[A](schema: Schema[A]): FlowSchemaAst =
    if (schema eq Remote.schemaAny)
      RemoteAst(NodePath.root, optional = false)
    else if (schema eq ZFlow.schemaAny)
      FlowAst(NodePath.root, optional = false)
    else
      // NOTE: we can't use fromAst(schema.ast) because that would not find the Remote and Flow schema references
      schema match {
        case Schema.Primitive(typ, _)   => Other(Value(typ, NodePath.root))
        case Schema.Fail(message, _)    => Other(FailNode(message, NodePath.root))
        case Schema.Optional(schema, _) => subtree(NodePath.root, Chunk.empty, schema, optional = true)
        case Schema.Either(left, right, _) =>
          Either(
            NodePath.root,
            subtree(NodePath.root / "left", Chunk.empty, left),
            subtree(NodePath.root / "right", Chunk.empty, right),
            optional = false
          )
        case Schema.Tuple2(left, right, _) =>
          Tuple(
            NodePath.root,
            subtree(NodePath.root / "left", Chunk.empty, left),
            subtree(NodePath.root / "right", Chunk.empty, right),
            optional = false
          )
        case Schema.Sequence(schema, _, _, _, _) =>
          ListNode(item = subtree(NodePath.root / "item", Chunk.empty, schema), NodePath.root, optional = false)
        case Schema.Map(ks, vs, _) =>
          Dictionary(
            keys = subtree(NodePath.root / "keys", Chunk.empty, ks),
            values = subtree(NodePath.root / "values", Chunk.empty, vs),
            NodePath.root,
            optional = false
          )
        case Schema.Set(schema, _) =>
          ListNode(item = subtree(NodePath.root / "item", Chunk.empty, schema), NodePath.root, optional = false)
        case Schema.Transform(schema, _, _, _, _) => subtree(NodePath.root, Chunk.empty, schema)
        case lzy @ Schema.Lazy(_)                 => fromSchema(lzy.schema)
        case s: Schema.Record[A] =>
          s.fields
            .foldLeft(NodeBuilder(NodePath.root, Chunk(s.hashCode() -> NodePath.root))) { (node, field) =>
              node.addLabelledSubtree(field.name, field.schema)
            }
            .buildProduct(s.id)
        case s: Schema.Enum[A] =>
          s.cases
            .foldLeft(NodeBuilder(NodePath.root, Chunk(s.hashCode() -> NodePath.root))) { case (node, caseValue) =>
              node.addLabelledSubtree(caseValue.id, caseValue.schema)
            }
            .buildSum(s.id)
        case Schema.Dynamic(_) => Other(Dynamic(withSchema = false, NodePath.root))
      }

  final private case class NodeBuilder(
    path: NodePath,
    lineage: Lineage,
    optional: Boolean = false
  ) { self =>
    private val children: ChunkBuilder[(String, FlowSchemaAst)] = ChunkBuilder.make[(String, FlowSchemaAst)]()

    def addLabelledSubtree(label: String, schema: Schema[_]): NodeBuilder = {
      children += (label -> subtree(path / label, lineage, schema))
      self
    }

    def buildProduct(id: TypeId): Product = Product(id, path, children.result(), optional)

    def buildSum(id: TypeId): Sum = Sum(id, path, children.result(), optional)
  }

  private def subtree(
    path: NodePath,
    lineage: Lineage,
    schema: Schema[_],
    optional: Boolean = false
  ): FlowSchemaAst =
    lineage
      .find(_._1 == schema.hashCode())
      .map { case (_, refPath) =>
        Other(MetaSchema.Ref(refPath, path, optional))
      }
      .getOrElse {
        if (schema eq Remote.schemaAny)
          RemoteAst(path, optional)
        else if (schema eq ZFlow.schemaAny)
          FlowAst(path, optional)
        else
          schema match {
            case Schema.Primitive(typ, _)   => Other(MetaSchema.Value(typ, path, optional))
            case Schema.Optional(schema, _) => subtree(path, lineage, schema, optional = true)
            case Schema.Either(left, right, _) =>
              Either(
                path,
                subtree(path / "left", lineage, left, optional = false),
                subtree(path / "right", lineage, right, optional = false),
                optional
              )
            case Schema.Tuple2(left, right, _) =>
              Tuple(
                path,
                subtree(path / "left", lineage, left, optional = false),
                subtree(path / "right", lineage, right, optional = false),
                optional
              )
            case Schema.Sequence(schema, _, _, _, _) =>
              ListNode(item = subtree(path / "item", lineage, schema, optional = false), path, optional)
            case Schema.Map(ks, vs, _) =>
              Dictionary(
                keys = subtree(path / "keys", Chunk.empty, ks, optional = false),
                values = subtree(path / "values", Chunk.empty, vs, optional = false),
                path,
                optional
              )
            case Schema.Set(schema @ _, _) =>
              ListNode(item = subtree(path / "item", lineage, schema, optional = false), path, optional)
            case Schema.Transform(schema, _, _, _, _) => subtree(path, lineage, schema, optional)
            case lzy @ Schema.Lazy(_)                 => subtree(path, lineage, lzy.schema, optional)
            case s: Schema.Record[_] =>
              s.fields
                .foldLeft(NodeBuilder(path, lineage :+ (s.hashCode() -> path), optional)) { (node, field) =>
                  node.addLabelledSubtree(field.name, field.schema)
                }
                .buildProduct(s.id)
            case s: Schema.Enum[_] =>
              s.cases
                .foldLeft(NodeBuilder(path, lineage :+ (s.hashCode() -> path), optional)) { case (node, caseValue) =>
                  node.addLabelledSubtree(caseValue.id, caseValue.schema)
                }
                .buildSum(s.id)
            case Schema.Fail(message, _) => Other(FailNode(message, path))
            case Schema.Dynamic(_)       => Other(Dynamic(withSchema = false, path, optional))
          }
      }

  def fromAst(ast: MetaSchema): FlowSchemaAst =
    ast match {
      case MetaSchema.Product(id, path, fields, optional) =>
        Product(id, path, fields.map { case (label, fieldAst) => (label, fromAst(fieldAst)) }, optional)
      case MetaSchema.Tuple(path, left, right, optional) =>
        Tuple(path, fromAst(left), fromAst(right), optional)
      case MetaSchema.Sum(id, path, cases, optional) =>
        Sum(id, path, cases.map { case (label, caseAst) => (label, fromAst(caseAst)) }, optional)
      case MetaSchema.Either(path, left, right, optional) =>
        Either(path, fromAst(left), fromAst(right), optional)
      case MetaSchema.FailNode(_, _, _) =>
        Other(ast)
      case MetaSchema.ListNode(item, path, optional) =>
        ListNode(fromAst(item), path, optional)
      case MetaSchema.Dictionary(keys, values, path, optional) =>
        Dictionary(fromAst(keys), fromAst(values), path, optional)
      case MetaSchema.Value(_, _, _) =>
        Other(ast)
      case MetaSchema.Ref(_, _, _) =>
        Other(ast)
      case MetaSchema.Dynamic(_, _, _) =>
        Other(ast)
    }

  private def materialize(ast: FlowSchemaAst, refs: mutable.Map[NodePath, Schema[_]]): Schema[_] = {
    val baseSchema = ast match {
      case FlowSchemaAst.Other(MetaSchema.Ref(refPath, _, _)) =>
        Schema.defer(
          refs.getOrElse(refPath, Schema.Fail(s"invalid ref path $refPath"))
        )
      case FlowSchemaAst.Other(ast) =>
        ast.toSchema
      case FlowSchemaAst.Product(id, _, elems, _) =>
        Schema.record(
          id,
          elems.map { case (label, ast) =>
            Schema.Field(
              label,
              materialize(ast, refs).asInstanceOf[Schema[Any]],
              get0 = (p: ListMap[String, _]) => p(label),
              set0 = (p: ListMap[String, _], v: Any) => p.updated(label, v)
            )
          }: _*
        )
      case FlowSchemaAst.Tuple(_, left, right, _) =>
        Schema.tuple2(
          materialize(left, refs),
          materialize(right, refs)
        )
      case FlowSchemaAst.Sum(id, _, elems, _) =>
        Schema.enumeration[Any, CaseSet.Aux[Any]](
          id,
          elems.foldRight[CaseSet.Aux[Any]](CaseSet.Empty[Any]()) { case ((label, ast), acc) =>
            val _case: Schema.Case[Any, Any] = Schema
              .Case[Any, Any](
                label,
                materialize(ast, refs).asInstanceOf[Schema[Any]],
                identity[Any],
                identity[Any],
                _.isInstanceOf[Any],
                Chunk.empty
              )
            CaseSet.Cons(_case, acc)
          }
        )
      case FlowSchemaAst.Either(_, left, right, _) =>
        Schema.either(
          materialize(left, refs),
          materialize(right, refs)
        )
      case FlowSchemaAst.ListNode(itemAst, _, _) =>
        Schema.chunk(materialize(itemAst, refs))
      case FlowSchemaAst.Dictionary(keyAst, valueAst, _, _) =>
        Schema.Map(materialize(keyAst, refs), materialize(valueAst, refs), Chunk.empty)
      case ast =>
        ast.toSchema
    }

    refs += ast.path -> baseSchema

    ast match {
      case Other(_) =>
        // we are using MetaSchema here, so optional is already handled
        baseSchema
      case _ =>
        if (ast.optional) baseSchema.optional else baseSchema
    }
  }

  @nowarn private implicit val nodePathSchema: Schema[NodePath] =
    Schema[String].repeated.transform(NodePath.apply(_), NodePath.unwrap)
  implicit val schema: Schema[FlowSchemaAst] = DeriveSchema.gen
}
