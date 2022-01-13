package zio.flow

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio._
import zio.schema._
import zio.test._

object RemoteOpticsSpec extends ZIOSpecDefault {

  final case class Person(name: String, age: Int)

  object Person {
    implicit val schema = DeriveSchema.gen[Person]
    val (name, age)     = Remote.makeAccessors[Person]
  }

  val genPerson: Gen[Random with Sized, Person] =
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
    val (blue, green, red)  = Remote.makeAccessors[Color]
  }

  val genColor: Gen[Random with Sized, Color] =
    Gen.elements(Red, Green, Blue)

  def spec = suite("RemoteOpticsSpec")(
    suite("RemoteLens")(
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
    suite("RemotePrism")(
      test("get") {
        check(genColor) { color =>
          Color.blue.get(Remote(color)) <-> color.blue
        }
      },
      test("set") {
        Color.blue.set(Remote(Blue)) <-> Blue
      }
    )
  )
}
