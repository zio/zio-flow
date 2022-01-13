package zio.flow.examples

import zio._

object HelloWorld extends ZIOAppDefault {

  case class Person(name: String, age: Int)

  val person: Person = Person("Ash", 12)
  person.copy(age = 32)

  val run =
    for {
      _    <- Console.printLine("Hello! What is your name?")
      name <- Console.readLine
      _    <- Console.printLine(s"Hello, $name, welcome to ZIO!")
    } yield ()
}
