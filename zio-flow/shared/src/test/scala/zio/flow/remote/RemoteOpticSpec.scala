package zio.flow.remote

import zio.flow.{LocalContext, Remote, RemoteContext}
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio._
import zio.schema._
import zio.test._

object RemoteOpticSpec extends RemoteSpecBase {

  final case class Person(name: String, age: Int)

  object Person {
    implicit val schema = DeriveSchema.gen[Person]
    val (name, age)     = Remote.makeAccessors[Person]
  }

  val genPerson: Gen[Any, Person] =
    for {
      name <- Gen.string
      age  <- Gen.int
    } yield Person(name, age)

  sealed trait Color { self =>
    def blue: Option[Blue.type] =
      self match {
        case Blue => Some(Blue)
        case _    => None
      }
  }

  case object Blue  extends Color
  case object Green extends Color
  case object Red   extends Color

  object Color {
    implicit val schema     = DeriveSchema.gen[Color]
    implicit val blueSchema = DeriveSchema.gen[Blue.type]

    val (blue, green, red) = Remote.makeAccessors[Color]
  }

  val genColor: Gen[Any, Color] =
    Gen.elements(Red, Green, Blue)

  def spec = suite("RemoteOpticSpec")(
    suite("Lens")(
      test("get") {
        check(genPerson) { person =>
          Person.age.get(Remote(person)) <-> person.age
        }
      },
      test("set") {
        check(genPerson, Gen.int) { (person, age) =>
          Person.age.set(person)(age) <-> person.copy(age = age)
        }
      }
    ),
    suite("Prism")(
      test("get") {
        check(genColor) { color =>
          Color.blue.get(Remote(color)) <-> color.blue
        }
      },
      test("set") {
        Color.blue.set(Remote(Blue)) <-> Blue
      }
    )
    // TODO: fix type error
//    suite("Traversal")(
//      suite("list")(
//        test("get") {
//          check(Gen.listOf(genPerson)) { list =>
//            val traversal = Remote.makeAccessors[List[Person]]
//            traversal.get(Remote(list)) <-> list
//          }
//        },
//        test("set") {
//          check(Gen.listOf(genPerson), Gen.listOf(genPerson)) { case (oldList, newList) =>
//            val traversal = Remote.makeAccessors[List[Person]]
//            traversal.set(Remote(oldList))(Remote(newList)) <-> newList
//          }
//        }
//      ),
//      suite("set")(
//        test("get") {
//          check(Gen.setOf(genPerson)) { set =>
//            val traversal = Remote.makeAccessors[Set[Person]]
//            traversal.get(Remote(set)) <-> set.toList
//          }
//        },
//        test("set") {
//          check(Gen.setOf(genPerson), Gen.listOf(genPerson)) { case (oldSet, newList) =>
//            val traversal = Remote.makeAccessors[Set[Person]]
//            traversal.set(Remote(oldSet))(Remote(newList)) <-> newList.toSet
//          }
//        }
//      ),
//      suite("chunk")(
//        test("get") {
//          check(Gen.chunkOf(genPerson)) { chunk =>
//            val traversal = Remote.makeAccessors[Chunk[Person]]
//            traversal.get(Remote(chunk)) <-> chunk.toList
//          }
//        },
//        test("set") {
//          check(Gen.chunkOf(genPerson), Gen.listOf(genPerson)) { case (oldChunk, newList) =>
//            val traversal = Remote.makeAccessors[Chunk[Person]]
//            traversal.set(Remote(oldChunk))(Remote(newList)) <-> Chunk.fromIterable(newList)
//          }
//        }
//      ),
//      suite("map")(
//        test("get") {
//          check(Gen.mapOf(Gen.string, genPerson)) { map =>
//            val traversal = Remote.makeAccessors[Map[String, Person]]
//            traversal.get(Remote(map)) <-> map.toList
//          }
//        },
//        test("set") {
//          check(Gen.mapOf(Gen.string, genPerson), Gen.listOf(genPerson)) { case (oldMap, newList) =>
//            val traversal = Remote.makeAccessors[Map[String, Person]]
//            val newMap = newList.map(person => person.name -> person)
//            traversal.set(Remote(oldMap))(Remote(newMap)) <-> newMap.toMap
//          }
//        }
//      ),
//    )
  ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

}
