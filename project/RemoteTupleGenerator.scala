import sbt._
import sbt.Keys._

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.meta._

object RemoteTupleGenerator extends AutoPlugin {

  case class TupleModel(size: Int) {
    val typeParamTypes: List[Type.Name] = (1 to size).map(i => Type.Name(s"T$i")).toList
    val typeParams: List[Type.Param] =
      typeParamTypes.map(name => Type.Param(Nil, name, Nil, Type.Bounds(None, None), Nil, Nil))
    val typeParamPlaceholders: List[Type.Param] =
      typeParamTypes.map(_ => Type.Param(Nil, Name.Anonymous(), Nil, Type.Bounds(None, None), Nil, Nil))
    val typeParamPlaceholderTypes: List[Type.Placeholder] =
      typeParamTypes.map(_ => Type.Placeholder(Type.Bounds(None, None)))

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
      val schemaFromDyns =
        dynNames.map { dyn =>
          q"$dyn.schema"
        }

      val evalDynamic =
        (dynNames zip model.paramRefs).map { case (dynName, paramName) =>
          Enumerator.Generator(Pat.Var(dynName), q"$paramName.evalDynamic")
        }
      val toTyped =
        (typNames zip dynNames).map { case (typName, dynName) =>
          Enumerator.Generator(Pat.Var(typName), q"ZIO.fromEither($dynName.toTyped)")
        }

      q"""trait $name[..${model.typeParams}] {
          lazy val schema: Schema[_ <: ${model.appliedTupleType}] =
            Schema.${model.schemaTupleMethod}(..$schemaFromParamVals)

          def evalDynamic: ZIO[LocalContext with RemoteContext, String, SchemaAndValue[${model.appliedTupleType}]] =
            for {
              ..$evalDynamic
              ..$toTyped

              result = SchemaAndValue.fromSchemaAndValue(
                         Schema.${model.schemaTupleMethod}(..$schemaFromDyns),
                         ${fromTyps}
                       )
            } yield result

          ..${model.paramVals}
          }
       """
    }

    def generateTupleConstructorStatic(model: TupleModel): Defn.Trait = {
      val name = Type.Name(s"ConstructStatic")

      val selfType      = t"Self[..${model.typeParamTypes}]"
      val constructType = t"Construct[..${model.typeParamTypes}]"
      val fromAccessors =
        Term.Tuple((1 to model.size).map(i => q"t.asInstanceOf[$constructType].${Term.Name(s"t$i")}").toList)
      val anyTypeParams = model.typeParamTypes.map(t => t"Any")
      val anySelfType   = t"Self[..$anyTypeParams]"

      val pattern = Pat.Tuple(model.paramRefs.map(Pat.Var(_)))

      q"""trait $name[Self[..${model.typeParamPlaceholders}] <: Construct[..${model.typeParamPlaceholderTypes}] with Remote[_]] {
          def construct[..${model.typeParams}](..${model.paramDefs}): Self[..${model.typeParamTypes}]
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

            def schemaCase[A]: Schema.Case[$anySelfType, Remote[A]] =
              Schema.Case(${model.tupleLit}, schema[..$anyTypeParams], _.asInstanceOf[$anySelfType])
       }
       """
    }

    def generateTupleSyntax(model: TupleModel) = {
      val accessors =
        model.typeParamTypes.zipWithIndex.map { case (t, i) =>
          q"""def ${Term.Name(s"_${i + 1}")}: Remote[$t] = Remote.TupleAccess(self, ${Lit.Int(i)})"""
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
      val appliedConstructType       = Type.Apply(constructType, model.typeParamTypes)
      val appliedConstructStaticType = Type.Apply(constructStaticType, List(typ))
      val substitutions = model.typeParamTypes.zipWithIndex.map { case (t, i) =>
        q"""${Term.Name(s"t${i + 1}")}.substitute(f)"""
      }
      val variableUsageUnion = model.typeParamTypes.zipWithIndex.foldLeft[Term](q"""VariableUsage.none""") { case (expr, (t, i)) =>
        q"""$expr.union(${Term.Name(s"t${i + 1}")}.variableUsage)"""
      }

      List(
        q"""
      final case class $typ[..${model.typeParams}](..${model.paramDefs})
        extends Remote[${model.appliedTupleType}]
        with ${Init(appliedConstructType, Name.Anonymous(), Nil)} {
        
          override protected def substituteRec[B](f: Remote.Substitutions): Remote[${model.appliedTupleType}] =
            $name(..$substitutions)

          override private[flow] val variableUsage = $variableUsageUnion
        }
      """,
        q"""
      object $name extends ${Init(appliedConstructStaticType, Name.Anonymous(), Nil)} {
        def construct[..${model.typeParams}](..${model.paramDefs}): $typ[..${model.typeParamTypes}] =
          $name(..${model.paramRefs})
      }
      """
      )
    }

    def generateSyntaxImplicits(model: TupleModel) = {
      val name              = Term.Name(s"remoteTuple${model.size}Syntax")
      val implName          = Term.Name(s"RemoteTuple${model.size}")
      val syntaxType        = Type.Select(implName, Type.Name("Syntax"))
      val appliedSyntaxType = Type.Apply(syntaxType, model.typeParamTypes)

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
