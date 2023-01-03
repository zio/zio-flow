import sbt._
import sbt.Keys._

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.meta._

object RemoteTupleGenerator extends AutoPlugin {

  case class TupleModel(size: Int) {
    val typeParamTypes: List[Type.Name] = (1 to size).map(i => Type.Name(s"T$i")).toList
    val typeParams: List[Type.Param] =
      typeParamTypes.map(name => Type.Param(Nil, name, Type.ParamClause(Nil), Type.Bounds(None, None), Nil, Nil))
    val typeParamPlaceholders: List[Type.Param] =
      typeParamTypes.map(_ =>
        Type.Param(Nil, Name.Placeholder(), Type.ParamClause(Nil), Type.Bounds(None, None), Nil, Nil)
      )
    val typeParamPlaceholderTypes: List[Type.AnonymousParam] =
      typeParamTypes.map(_ => Type.AnonymousParam(None))

    val tupleName: String                  = s"Tuple${size}"
    val tupleLit: Lit.String               = Lit.String(tupleName)
    val appliedTupleType: Type.Tuple       = Type.Tuple(typeParamTypes)
    val appliedRemoteTupleType: Type.Tuple = Type.Tuple(typeParamTypes.map(t => t"Remote[$t]"))
    val anyTupleType: Type.Tuple           = Type.Tuple((1 to size).map(_ => t"Any").toList)
    val schemaTupleMethod: Term.Name       = Term.Name("tuple" + size)
    val remoteParams: List[Term.ApplyType] = typeParamTypes.map { t =>
      q"Remote[$t]"
    }
    val remoteSchemas: List[Term.ApplyType] = typeParamTypes.map { t =>
      q"Remote.schema[$t]"
    }
    val paramDefs: List[Term.Param] = typeParamTypes.zipWithIndex.map { case (t, i) =>
      Term.Param(Nil, Term.Name(s"t${i + 1}"), Some(t"Remote[$t]"), None)
    }
    val paramVals: List[Decl.Val] = paramDefs.map { param =>
      q"val ${Pat.Var(Term.Name(param.name.value))}: ${param.decltpe.get}"
    }
    val paramRefs: List[Term.Name] = (1 to size).map(i => Term.Name(s"t$i")).toList
  }

  private def generateCode(log: Logger, targetFile: File): Unit = {
    log.info(s"Generating remote tuple code to $targetFile")
    Files.createDirectories(targetFile.getParentFile.toPath)

    def generateTupleConstructor(model: TupleModel) = {
      val name = Type.Name(s"Construct")

      val schemaFromParamVals =
        model.paramRefs.map { param =>
          q"${param}.schema"
        }

      val dynNames = (1 to model.size).map { i =>
        Term.Name(s"dyn$i")
      }.toList
      val typNames = (1 to model.size).map { i =>
        Term.Name(s"typ$i")
      }.toList
      val fromTyps = Term.Tuple(typNames)

      val evalDynamic =
        (dynNames zip model.paramRefs).map { case (dynName, paramName) =>
          Enumerator.Generator(Pat.Var(dynName), q"$paramName.evalDynamic")
        }

      q"""trait $name[..${model.typeParams}] {

          def evalDynamic: ZIO[LocalContext with RemoteContext, RemoteEvaluationError, DynamicValue] =
            for {
              ..$evalDynamic

              result = DynamicValueHelpers.tuple(..$dynNames)
            } yield result

          ..${model.paramVals}
          }
       """
    }

    def generateTupleConstructorStatic(model: TupleModel): Defn.Trait = {
      val name = Type.Name(s"ConstructStatic")

      val selfType = t"Self[..${model.typeParamTypes}]"
      val selfTypeParam = Type.Param(
        Nil,
        Type.Name("Self"),
        Type.ParamClause(model.typeParamPlaceholders),
        Type.Bounds(None, Some(t"Construct[..${model.typeParamPlaceholderTypes}] with Remote[_]")),
        Nil,
        Nil
      )
      val constructType = t"Construct[..${model.typeParamTypes}]"
      val fromAccessors =
        Term.Tuple((1 to model.size).map(i => q"t.asInstanceOf[$constructType].${Term.Name(s"t$i")}").toList)
      val anyTypeParams = model.typeParamTypes.map(t => t"Any")
      val anySelfType   = t"Self[..$anyTypeParams]"

      val pattern = Pat.Tuple(model.paramRefs.map(Pat.Var(_)))

      q"""trait $name[$selfTypeParam] {
          def construct[..${model.typeParams}](..${model.paramDefs}): Self[..${model.typeParamTypes}]
          def checkInstance[A](remote: Remote[A]): Boolean
          def schema[..${model.typeParams}]: Schema[Self[..${model.typeParamTypes}]] =
            Schema.defer(
              Schema
                .${model.schemaTupleMethod}(..${model.remoteSchemas})
                .transform(
                  { (v: ${model.appliedRemoteTupleType}) =>
                    val $pattern = v
                    construct(..${model.paramRefs})
                  },
                  (t: $selfType) => $fromAccessors
                )
            )

            def schemaCase[A]: Schema.Case[Remote[A], $anySelfType] =
              Schema.Case(${model.tupleLit}, schema[..$anyTypeParams], _.asInstanceOf[$anySelfType], _.asInstanceOf[Remote[A]], checkInstance)
       }
       """
    }

    def generateTupleSyntax(model: TupleModel) = {
      val accessors =
        model.typeParamTypes.zipWithIndex.map { case (t, i) =>
          q"""def ${Term.Name(s"_${i + 1}")}: Remote[$t] = Remote.TupleAccess(self, ${Lit.Int(i)}, ${Lit.Int(
              model.size
            )})"""
        }.toList

      q"""
       final class Syntax[..${model.typeParams}](val self: Remote[${model.appliedTupleType}]) {
         ..$accessors
       }
       """
    }

    def generateTupleObject(model: TupleModel) = {
      val name = Term.Name(s"RemoteTuple${model.size}")

      q"""
       object $name {
         ${generateTupleConstructor(model)}
         ${generateTupleConstructorStatic(model)}
         ${generateTupleSyntax(model)}
       }
      """
    }

    def generateRemoteConstructor(model: TupleModel) = {
      val name                       = Term.Name(s"Tuple${model.size}")
      val typ                        = Type.Name(s"Tuple${model.size}")
      val implName                   = Term.Name(s"RemoteTuple${model.size}")
      val constructType              = Type.Select(implName, Type.Name("Construct"))
      val constructStaticType        = Type.Select(implName, Type.Name("ConstructStatic"))
      val appliedConstructType       = Type.Apply(constructType, Type.ArgClause(model.typeParamTypes))
      val appliedConstructStaticType = Type.Apply(constructStaticType, Type.ArgClause(List(typ)))
      val substitutions = model.typeParamTypes.zipWithIndex.map { case (t, i) =>
        q"""${Term.Name(s"t${i + 1}")}.substitute(f)"""
      }
      val variableUsageUnion = model.typeParamTypes.zipWithIndex.foldLeft[Term](q"""VariableUsage.none""") {
        case (expr, (t, i)) =>
          q"""$expr.union(${Term.Name(s"t${i + 1}")}.variableUsage)"""
      }
      val holeParams: List[Type] = model.typeParamTypes.map(t => t"_")

      List(
        q"""
      final case class $typ[..${model.typeParams}](..${model.paramDefs})
        extends Remote[${model.appliedTupleType}]
        with ${Init(appliedConstructType, Name.Anonymous(), Seq.empty)} {
        
          override protected def substituteRec(f: Remote.Substitutions): Remote[${model.appliedTupleType}] =
            $name(..$substitutions)

          override private[flow] val variableUsage = $variableUsageUnion
        }
      """,
        q"""
      object $name extends ${Init(appliedConstructStaticType, Name.Anonymous(), Seq.empty)} {
        def construct[..${model.typeParams}](..${model.paramDefs}): $typ[..${model.typeParamTypes}] =
          $name(..${model.paramRefs})

        def checkInstance[A](remote: Remote[A]): Boolean =
          remote.isInstanceOf[$typ[..$holeParams]]
      }
      """
      )
    }

    def generateSyntaxImplicits(model: TupleModel) = {
      val name              = Term.Name(s"remoteTuple${model.size}Syntax")
      val implName          = Term.Name(s"RemoteTuple${model.size}")
      val syntaxType        = Type.Select(implName, Type.Name("Syntax"))
      val appliedSyntaxType = Type.Apply(syntaxType, Type.ArgClause(model.typeParamTypes))

      q"""
       implicit def $name[..${model.typeParams}](remote: Remote[${model.appliedTupleType}]): $appliedSyntaxType =
         new $syntaxType(remote)
       """
    }

    val maxSize = 22
    val tuples  = (2 to maxSize).map(TupleModel)

    val remoteTuples       = tuples.map(generateTupleObject).toList
    val remoteConstructors = tuples.flatMap(generateRemoteConstructor)
    val syntaxImplicits    = tuples.map(generateSyntaxImplicits)

    println(s"Remote constructors to be added to Remote.scala manually:")
    println(remoteConstructors.mkString("\n\n"))
    println()
    println(s"Implicit remote syntax to be added to Syntax.scala manually:")
    println(syntaxImplicits.mkString("\n"))

    val code =
      source"""
       package zio.flow.remote

       import zio._
       import zio.flow._
       import zio.schema._

       object RemoteTuples {
         ..$remoteTuples
       }
       """

    Files.write(targetFile.toPath, code.toString.getBytes(StandardCharsets.UTF_8))
  }

  lazy val generateSource =
    Def.task {
      val log = streams.value.log

      val sourcesDir = (Compile / sourceManaged).value
      val targetFile = sourcesDir / "zio" / "flow" / "remote" / "RemoteTuples.scala"

      val cachedFun = FileFunction.cached(
        streams.value.cacheDirectory / "zio-flow-remote-tuple",
        FileInfo.hash
      ) { input: Set[File] =>
        generateCode(log, targetFile)
        Set(targetFile)
      }

      cachedFun(Set(file("project/RemoteTupleGenerator.scala"))).toSeq
    }

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      Compile / sourceGenerators += generateSource.taskValue
    )
}
