package zio.flow.remote

import zio.ZLayer
import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.flow._
import zio.test.{Spec, TestEnvironment}

object RemoteStringSyntaxSpec extends RemoteSpecBase {
  val suite: Spec[TestEnvironment, Nothing] =
    suite("RemoteStringSyntax")(
      remoteTest("*")(
        Remote("xy" * 3) <-> "xyxyxy"
      ),
      remoteTest("+")(
        Remote("" + "hello") <-> "hello",
        Remote("hello" + "") <-> "hello",
        Remote("hello" + "world") <-> "helloworld"
      ),
      remoteTest("++")(
        Remote("" ++ "hello") <-> "hello",
        Remote("hello" ++ "") <-> "hello",
        Remote("hello" ++ "world") <-> "helloworld"
      ),
      remoteTest("++:")(
        Remote("" ++: "hello") <-> "hello",
        Remote("hello" ++: "") <-> "hello",
        Remote("hello" ++: "world") <-> "helloworld"
      ),
      remoteTest("+:")(
        Remote('x') +: Remote("yz") <-> "xyz"
      ),
      remoteTest(":++")(
        Remote("" :++ "hello") <-> "hello",
        Remote("hello" :++ "") <-> "hello",
        Remote("hello" :++ "world") <-> "helloworld"
      ),
      remoteTest("appended")(
        Remote("hello").appended('!') <-> "hello!"
      ),
      remoteTest("appendedAll")(
        Remote("" appendedAll "hello") <-> "hello",
        Remote("hello" appendedAll "") <-> "hello",
        Remote("hello" appendedAll "world") <-> "helloworld"
      ),
      remoteTest("apply")(
        Remote("").apply(1) failsWithRemoteError "get called on empty Option",
        Remote("hello").apply(1) <-> 'e'
      ),
      remoteTest("capitalize")(
        Remote("").capitalize <-> "",
        Remote("hello world").capitalize <-> "Hello world"
      ),
      remoteTest("charAt")(
        Remote("").charAt(1) failsWithRemoteError "get called on empty Option",
        Remote("hello").charAt(1) <-> 'e'
      ),
      remoteTest("combinations")(
        Remote("").combinations(2) <-> List.empty[String],
        Remote("hello").combinations(1) <-> List("h", "e", "l", "o"),
        Remote("hello").combinations(2) <-> List("he", "hl", "ho", "el", "eo", "ll", "lo"),
        Remote("hello").combinations(3) <-> List("hel", "heo", "hll", "hlo", "ell", "elo", "llo")
      ),
      remoteTest("concat")(
        Remote("" concat "hello") <-> "hello",
        Remote("hello" concat "") <-> "hello",
        Remote("hello" concat "world") <-> "helloworld"
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
      ),
      remoteTest("head")(
        Remote("").head failsWithRemoteError "List is empty",
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
        Remote("").init failsWithRemoteError "List is empty",
        Remote("hello").init <-> "hell"
      ),
      remoteTest("inits")(
        Remote("").inits <-> List[String],
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
        Remote("").last failsWithRemoteError "List is empty",
        Remote("hello").last <-> 'o'
      )
    ).provide(ZLayer(RemoteContext.inMemory), LocalContext.inMemory)

  override def spec = suite("RemoteStringSpec")(suite)
}
