package zio.flow.remote

import zio.ZLayer
import zio.flow.debug.TrackRemotes._
import zio.flow.runtime.internal.InMemoryRemoteContext
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow.{LocalContext, Remote}
import zio.test.{Spec, TestAspect, TestEnvironment}

object RemoteStringSyntaxSpec extends RemoteSpecBase {
  val suite: Spec[TestEnvironment, Nothing] =
    suite("RemoteStringSyntax")(
      remoteTest("*")(
        Remote("xy") * 3 <-> "xyxyxy",
        Remote("x") * 2 <-> "xx",
        Remote('x').toString * 2 <-> "xx"
      ),
      remoteTest("+")(
        Remote("") + "hello" <-> "hello",
        Remote("hello") + "" <-> "hello",
        Remote("hello") + "world" <-> "helloworld"
      ),
      remoteTest("++")(
        Remote("") ++ "hello" <-> "hello",
        Remote("hello") ++ "" <-> "hello",
        Remote("hello") ++ "world" <-> "helloworld"
      ),
      remoteTest("++:")(
        Remote("") ++: Remote("hello") <-> "hello",
        Remote("hello") ++: Remote("") <-> "hello",
        Remote("hello") ++: Remote("world") <-> "helloworld"
      ),
      remoteTest("+:")(
        Remote('x') +: Remote("yz") <-> "xyz"
      ),
      remoteTest(":++")(
        Remote("") :++ "hello" <-> "hello",
        Remote("hello") :++ "" <-> "hello",
        Remote("hello") :++ "world" <-> "helloworld"
      ),
      remoteTest("appended")(
        Remote("hello").appended('!') <-> "hello!"
      ),
      remoteTest("appendedAll")(
        Remote("").appendedAll("hello") <-> "hello",
        Remote("hello").appendedAll("") <-> "hello",
        Remote("hello").appendedAll("world") <-> "helloworld"
      ),
      remoteTest("apply")(
        Remote("").apply(1) failsWithRemoteFailure "get called on empty Option",
        Remote("hello").apply(1) <-> 'e'
      ),
      remoteTest("capitalize")(
        Remote("").capitalize <-> "",
        Remote("hello world").capitalize <-> "Hello world"
      ),
      remoteTest("charAt")(
        Remote("").charAt(1) failsWithRemoteFailure "get called on empty Option",
        Remote("hello").charAt(1) <-> 'e'
      ),
      remoteTest("combinations")(
        Remote("").combinations(2) <-> List.empty[String],
        Remote("hello").combinations(1) <-> List("h", "e", "l", "o"),
        Remote("hello").combinations(2) <-> List("he", "hl", "ho", "el", "eo", "ll", "lo"),
        Remote("hello").combinations(3) <-> List("hel", "heo", "hll", "hlo", "ell", "elo", "llo")
      ) @@ TestAspect.ignore, // TODO
      remoteTest("concat")(
        Remote("").concat("hello") <-> "hello",
        Remote("hello").concat("") <-> "hello",
        Remote("hello").concat("world") <-> "helloworld"
      ),
      remoteTest("contains")(
        Remote("").contains('x') <-> false,
        Remote("xyz").contains('y') <-> true
      ),
      remoteTest("count")(
        Remote("").count(_ === 'a') <-> 0,
        Remote("baaba").count(_ === 'a') <-> 3
      ),
      remoteTest("diff")(
        Remote("").diff(List('b', 'c')) <-> "",
        Remote("abcd").diff(List('b', 'c')) <-> "ad"
      ),
      remoteTest("distinct")(
        Remote("").distinct <-> "",
        Remote("abbacca").distinct <-> "abc",
        Remote("abBacCa").distinct <-> "abBcC"
      ),
      remoteTest("distinctBy")(
        Remote("").distinctBy(_.toUpper) <-> "",
        Remote("abBacCa").distinctBy(_.toUpper) <-> "abc"
      ),
      remoteTest("drop")(
        Remote("").drop(10) <-> "",
        Remote("hello").drop(2) <-> "llo"
      ),
      remoteTest("dropRight")(
        Remote("").dropRight(10) <-> "",
        Remote("hello").dropRight(2) <-> "hel"
      ),
      remoteTest("dropWhile")(
        Remote("").dropWhile(_.isWhitespace) <-> "",
        Remote("    hello").dropWhile(_.isWhitespace) <-> "hello"
      ),
      remoteTest("endsWith")(
        Remote("").endsWith("!!") <-> false,
        Remote("hello").endsWith("!!") <-> false,
        Remote("hello!!").endsWith("!!") <-> true
      ),
      remoteTest("exists")(
        Remote("").exists(_.isDigit) <-> false,
        Remote("hello").exists(_.isDigit) <-> false,
        Remote("hell0").exists(_.isDigit) <-> true
      ),
      remoteTest("filter")(
        Remote("").filter(_.isLetter) <-> "",
        Remote("h1ell0w1rld").filter(_.isLetter) <-> "hellwrld"
      ),
      remoteTest("filterNot")(
        Remote("").filterNot(_.isLetter) <-> "",
        Remote("h1ell0w1rld").filterNot(_.isLetter) <-> "101"
      ),
      remoteTest("find")(
        Remote("").find(_.isDigit) <-> None,
        Remote("hello w1rld").find(_.isDigit) <-> Some('1')
      ),
      remoteTest("flatMap")(
        Remote("").flatMap((ch: Remote[Char]) => ch.toString * 2) <-> "",
        Remote("hello").flatMap((ch: Remote[Char]) => ch.toString * 2) <-> "hheelllloo"
      ),
      remoteTest("fold")(
        Remote("").fold('5')((a: Remote[Char], b: Remote[Char]) => (b.isDigit.ifThenElse(b, a))) <-> '5',
        Remote("hell0 world").fold('5')((a: Remote[Char], b: Remote[Char]) => (b.isDigit.ifThenElse(b, a))) <-> '0'
      ),
      remoteTest("foldLeft")(
        Remote("").foldLeft('5')((a: Remote[Char], b: Remote[Char]) => (b.isDigit.ifThenElse(b, a))) <-> '5',
        Remote("hell0 world").foldLeft('5')((a: Remote[Char], b: Remote[Char]) => (b.isDigit.ifThenElse(b, a))) <-> '0'
      ),
      remoteTest("foldRight")(
        Remote("").foldRight('5')((a: Remote[Char], b: Remote[Char]) => (a.isDigit.ifThenElse(a, b))) <-> '5',
        Remote("hell0 world").foldRight('5')((a: Remote[Char], b: Remote[Char]) => (a.isDigit.ifThenElse(a, b))) <-> '0'
      ),
      remoteTest("forall")(
        Remote("").forall(_.isDigit) <-> true,
        Remote("hell0").forall(_.isDigit) <-> false,
        Remote("1234").forall(_.isDigit) <-> true
      ),
      remoteTest("grouped")(
        Remote("").grouped(2) <-> List.empty[String],
        Remote("hello").grouped(3) <-> List("hel", "lo")
      ) @@ TestAspect.ignore, // TODO
      remoteTest("head")(
        Remote("").head failsWithRemoteFailure "List is empty",
        Remote("hello").head <-> 'h'
      ),
      remoteTest("headOption")(
        Remote("").headOption <-> None,
        Remote("hello").headOption <-> Some('h')
      ),
      remoteTest("indexOf(char)")(
        Remote("").indexOf('w') <-> -1,
        Remote("hello").indexOf('w') <-> -1,
        Remote("hello world").indexOf('w') <-> 6,
        Remote("").indexOf('w', 2) <-> -1,
        Remote("hello, 2").indexOf('w') <-> -1,
        Remote("hello world").indexOf('w', 2) <-> 6,
        Remote("hello world").indexOf('w', 8) <-> -1
      ),
      remoteTest("indexOf(string)")(
        Remote("").indexOf("wo") <-> -1,
        Remote("hello").indexOf("wo") <-> -1,
        Remote("hello world").indexOf("wo") <-> 6,
        Remote("").indexOf("wo", 2) <-> -1,
        Remote("hello, 2").indexOf("wo") <-> -1,
        Remote("hello world").indexOf("wo", 2) <-> 6,
        Remote("hello world").indexOf("wo", 8) <-> -1
      ),
      remoteTest("indexWhere")(
        Remote("").indexWhere(_ === 'w') <-> -1,
        Remote("hello").indexWhere(_ === 'w') <-> -1,
        Remote("hello world").indexWhere(_ === 'w') <-> 6,
        Remote("").indexWhere(_ === 'w', 2) <-> -1,
        Remote("hello, 2").indexWhere(_ === 'w') <-> -1,
        Remote("hello world").indexWhere(_ === 'w', 2) <-> 6,
        Remote("hello world").indexWhere(_ === 'w', 8) <-> -1
      ),
      remoteTest("init")(
        Remote("").init failsWithRemoteFailure "List is empty",
        Remote("hello").init <-> "hell"
      ),
      remoteTest("inits")(
        Remote("").inits <-> List(""),
        Remote("hello").inits <-> List("hello", "hell", "hel", "he", "h", "")
      ),
      remoteTest("intersect")(
        Remote("hello").intersect("lol") <-> "llo"
      ),
      remoteTest("isEmpty")(
        Remote("").isEmpty <-> true,
        Remote("x").isEmpty <-> false
      ),
      remoteTest("knownSize")(
        Remote("").knownSize <-> 0,
        Remote("abc").knownSize <-> 3
      ),
      remoteTest("last")(
        Remote("").last failsWithRemoteFailure "List is empty",
        Remote("hello").last <-> 'o'
      ),
      remoteTest("lastIndexOf(char)")(
        Remote("").lastIndexOf('w') <-> -1,
        Remote("hello").lastIndexOf('w') <-> -1,
        Remote("hello world").lastIndexOf('w') <-> 6,
        Remote("").lastIndexOf('w', 2) <-> -1,
        Remote("hello").lastIndexOf('w', 2) <-> -1,
        Remote("hello world").lastIndexOf('w', 2) <-> -1,
        Remote("hello world").lastIndexOf('w', 8) <-> 6
      ),
      remoteTest("lastIndexOf(string)")(
        Remote("").lastIndexOf("wo") <-> -1,
        Remote("hello").lastIndexOf("wo") <-> -1,
        Remote("hello world").lastIndexOf("wo") <-> 6,
        Remote("").lastIndexOf("wo", 2) <-> -1,
        Remote("hello").lastIndexOf("wo", 2) <-> -1,
        Remote("hello world").lastIndexOf("wo", 2) <-> -1,
        Remote("hello world").lastIndexOf("wo", 8) <-> 6
      ),
      remoteTest("lastIndexWhere")(
        Remote("").lastIndexWhere(_ === 'w') <-> -1,
        Remote("hello").lastIndexWhere(_ === 'w') <-> -1,
        Remote("hello world").lastIndexWhere(_ === 'w') <-> 6,
        Remote("").lastIndexWhere(_ === 'w', 2) <-> -1,
        Remote("hello").lastIndexWhere(_ === 'w', 2) <-> -1,
        Remote("hello world").lastIndexWhere(_ === 'w', 2) <-> -1,
        Remote("hello world").lastIndexWhere(_ === 'w', 8) <-> 6
      ),
      remoteTest("lastOption")(
        Remote("").lastOption <-> None,
        Remote("hello").lastOption <-> Some('o')
      ),
      remoteTest("length")(
        Remote("").length <-> 0,
        Remote("abc").length <-> 3
      ),
      remoteTest("map")(
        Remote("").map(_.toUpper) <-> "",
        Remote("hElLo").map(_.toUpper) <-> "HELLO"
      ),
      remoteTest("mkString")(
        Remote("").mkString <-> "",
        Remote("hello").mkString <-> "hello",
        Remote("hello").mkString("//") <-> "h//e//l//l//o",
        Remote("hello").mkString("[", "//", "]") <-> "[h//e//l//l//o]"
      ),
      remoteTest("nonEmpty")(
        Remote("").nonEmpty <-> false,
        Remote("x").nonEmpty <-> true
      ),
      remoteTest("padTo")(
        Remote("").padTo(3, '_') <-> "___",
        Remote("ab").padTo(3, '_') <-> "ab_",
        Remote("abcd").padTo(3, '_') <-> "abcd"
      ),
      remoteTest("partition")(
        Remote("").partition(_.isUpper) <-> (("", "")),
        Remote("hello").partition(_.isUpper) <-> (("", "hello")),
        Remote("hElLo").partition(_.isUpper) <-> (("EL", "hlo"))
      ),
      remoteTest("partitionMap")(
        Remote("").partitionMap(
          _.isUpper.ifThenElse(Remote.left[Char, Char]('^'), Remote.right[Char, Char]('v'))
        ) <-> (("", "")),
        Remote("hElLo").partitionMap(
          _.isUpper.ifThenElse(Remote.left[Char, Char]('^'), Remote.right[Char, Char]('v'))
        ) <-> (("^^", "vvv"))
      ),
      remoteTest("patch")(
        Remote("").patch(0, "hello", 3) <-> "hello",
        Remote("012345").patch(0, "hello", 3) <-> "hello345",
        Remote("012345").patch(1, "hello", 2) <-> "0hello345"
      ),
      remoteTest("permutations")(
        Remote("").permutations <-> List(""),
        Remote("abc").permutations <-> List("abc", "acb", "bac", "bca", "cab", "cba")
      ),
      remoteTest("prepended")(
        Remote("yz").prepended('x') <-> "xyz"
      ),
      remoteTest("prependedAll")(
        Remote("").prependedAll("xyz") <-> "xyz",
        Remote("z").prependedAll("xy") <-> "xyz"
      ),
      remoteTest("replace")(
        Remote("").replace(' ', '_') <-> "",
        Remote("hello world !!!").replace(' ', '_') <-> "hello_world_!!!"
      ),
      remoteTest("replaceAll")(
        Remote("").replaceAll("world", "WORLD") <-> "",
        Remote("hello world, hello world!").replaceAll("world", "WORLD") <-> "hello WORLD, hello WORLD!",
        Remote("hello world").replaceAll("[hel]", ".") <-> "....o wor.d"
      ) @@ TestAspect.ignore, // TODO
      remoteTest("replaceFirst")(
        Remote("").replaceFirst(" ", "__") <-> "",
        Remote("hello world !!!").replaceFirst(" ", "__") <-> "hello__world !!!"
      ) @@ TestAspect.ignore, // TODO
      remoteTest("reverse")(
        Remote("").reverse <-> "",
        Remote("hello").reverse <-> "olleh"
      ),
      remoteTest("size")(
        Remote("").size <-> 0,
        Remote("hello").size <-> 5
      ),
      remoteTest("slice")(
        Remote("").slice(2, 4) <-> "",
        Remote("hello").slice(2, 4) <-> "ll"
      ),
      remoteTest("sliding")(
        Remote("").sliding(1, 1) <-> List.empty[String],
        Remote("01233233345").sliding(1, 2) <-> List(
          "0",
          "2",
          "3",
          "3",
          "3",
          "5"
        ),
        Remote("01233233345").sliding(3, 1) <-> List(
          "012",
          "123",
          "233",
          "332",
          "323",
          "233",
          "333",
          "334",
          "345"
        )
      ),
      remoteTest("span")(
        Remote("").span(_.isLetter) <-> (("", "")),
        Remote("hello world").span(_.isLetter) <-> (("hello", " world"))
      ),
      remoteTest("split")(
        Remote("").split(',') <-> List(""),
        Remote("1,2,3").split(',') <-> List("1", "2", "3"),
        Remote("1,2;3,4").split(List(',', ';')) <-> List("1", "2", "3", "4")
      ),
      remoteTest("splitAt")(
        Remote("").splitAt(1) <-> (("", "")),
        Remote("hello").splitAt(2) <-> (("he", "llo"))
      ),
      remoteTest("startsWith")(
        Remote("").startsWith("he") <-> false,
        Remote("hello").startsWith("lo") <-> false,
        Remote("hello").startsWith("he") <-> true
      ),
      remoteTest("strip")(
        Remote("").strip() <-> "",
        Remote(" xy ").strip() <-> "xy",
        Remote("\nxy  \t ").strip() <-> "xy"
      ),
      remoteTest("stripLeading")(
        Remote("").stripLeading() <-> "",
        Remote(" xy ").stripLeading() <-> "xy ",
        Remote("\nxy  \t ").stripLeading() <-> "xy  \t "
      ),
      remoteTest("stripLineEnd")(
        Remote("hello").stripLineEnd <-> "hello",
        Remote("hello\n").stripLineEnd <-> "hello",
        Remote("hello\r").stripLineEnd <-> "hello",
        Remote("hello\r\n").stripLineEnd <-> "hello"
      ),
      remoteTest("stripMargin")(
        Remote("  |x\n |y\n     |z").stripMargin('|') <-> "x\ny\nz"
      ),
      remoteTest("stripPrefix")(
        Remote("hello").stripPrefix("wo") <-> "hello",
        Remote("world").stripPrefix("wo") <-> "rld"
      ),
      remoteTest("stripTrailing")(
        Remote("").stripTrailing() <-> "",
        Remote(" xy ").stripTrailing() <-> " xy",
        Remote("\nxy  \t ").stripTrailing() <-> "\nxy"
      ),
      remoteTest("stripSuffix")(
        Remote("hello").stripSuffix("lo") <-> "hel",
        Remote("world").stripSuffix("lo") <-> "world"
      ),
      remoteTest("substring")(
        Remote("").substring(2) <-> "",
        Remote("hello").substring(2) <-> "llo",
        Remote("hello").substring(1, 3) <-> "el"
      ),
      remoteTest("tail")(
        Remote("").tail failsWithRemoteFailure "List is empty",
        Remote("hello").tail <-> "ello"
      ),
      remoteTest("tails")(
        Remote("").tails <-> List(""),
        Remote("hello").tails <-> List("hello", "ello", "llo", "lo", "o", "")
      ),
      remoteTest("take")(
        Remote("").take(2) <-> "",
        Remote("hello").take(2) <-> "he"
      ),
      remoteTest("takeWhile")(
        Remote("").takeWhile(_.isLetter) <-> "",
        Remote("hello123").takeWhile(_.isLetter) <-> "hello"
      ),
      remoteTest("trim")(
        Remote("").trim() <-> "",
        Remote(" xy ").trim() <-> "xy",
        Remote("\nxy  \t ").trim() <-> "xy"
      ),
      remoteTest("toBoolean")(
        Remote("false").toBoolean <-> false,
        Remote("true").toBoolean <-> true,
        Remote("x").toBoolean failsWithRemoteFailure "Invalid boolean"
      ),
      remoteTest("toBooleanOption")(
        Remote("false").toBooleanOption <-> Some(false),
        Remote("true").toBooleanOption <-> Some(true),
        Remote("x").toBooleanOption <-> None
      ),
      // TODO: enable once we have byte schema
//      remoteTest("toByte")(
//        Remote("0").toByte <-> 0,
//        Remote("-100").toByte <-> -100,
//        Remote("x").toByte failsWithRemoteError "Invalid byte"
//      ),
//      remoteTest("toByteOption")(
//        Remote("0").toByteOption <-> Some(0),
//        Remote("-100").toByteOption <-> Some(-100),
//        Remote("x").toByteOption <-> None
//      )
      remoteTest("toDouble")(
        Remote("0.0").toDouble <-> 0.0,
        Remote("-12.34").toDouble <-> -12.34,
        Remote("x").toDouble failsWithRemoteFailure "Invalid double"
      ),
      remoteTest("toDoubleOption")(
        Remote("0.0").toDoubleOption <-> Some(0.0),
        Remote("-12.34").toDoubleOption <-> Some(-12.34),
        Remote("x").toDoubleOption <-> None
      ),
      remoteTest("toFloat")(
        Remote("0.0").toFloat <-> 0.0f,
        Remote("-12.34").toFloat <-> -12.34f,
        Remote("x").toFloat failsWithRemoteFailure "Invalid float"
      ),
      remoteTest("toFloatOption")(
        Remote("0.0").toFloatOption <-> Some(0.0f),
        Remote("-12.34").toFloatOption <-> Some(-12.34f),
        Remote("x").toFloatOption <-> None
      ),
      remoteTest("toInt")(
        Remote("0").toInt <-> 0,
        Remote("-123456").toInt <-> -123456,
        Remote("x").toInt failsWithRemoteFailure "Invalid int"
      ),
      remoteTest("toIntOption")(
        Remote("0").toIntOption <-> Some(0),
        Remote("-123456").toIntOption <-> Some(-123456),
        Remote("x").toIntOption <-> None
      ),
      remoteTest("toList")(
        Remote("").toList <-> List.empty[Char],
        Remote("hello").toList <-> "hello".toList
      ),
      remoteTest("toLong")(
        Remote("0").toLong <-> 0L,
        Remote("-1234567891000").toLong <-> -1234567891000L,
        Remote("x").toLong failsWithRemoteFailure "Invalid long"
      ),
      remoteTest("toLongOption")(
        Remote("0").toLongOption <-> Some(0L),
        Remote("-1234567891000").toLongOption <-> Some(-1234567891000L),
        Remote("x").toLongOption <-> None
      ),
      remoteTest("toLowerCase")(
        Remote("").toLowerCase <-> "",
        Remote("hello").toLowerCase <-> "hello",
        Remote("HEllO").toLowerCase <-> "hello"
      ),
      remoteTest("toShort")(
        Remote("0").toShort <-> (0: Short),
        Remote("-1234").toShort <-> (-1234: Short),
        Remote("x").toShort failsWithRemoteFailure "Invalid short"
      ),
      remoteTest("toShortOption")(
        Remote("0").toShortOption <-> Some(0: Short),
        Remote("-1234").toShortOption <-> Some(-1234: Short),
        Remote("x").toShortOption <-> None
      ),
      remoteTest("toUpperCase")(
        Remote("").toUpperCase <-> "",
        Remote("hello").toUpperCase <-> "HELLO",
        Remote("HELLO").toUpperCase <-> "HELLO"
      ),
      remoteTest("remote string interpolator")(
        rs"hello world" <-> "hello world",
        rs"hello ${Remote("world")}!" <-> "hello world!",
        rs"hello ${Remote(1).toString}${Remote("!") * 3}" <-> "hello 1!!!"
      )
    ).provide(ZLayer(InMemoryRemoteContext.make), LocalContext.inMemory)

  override def spec = suite("RemoteStringSpec")(suite)
}
