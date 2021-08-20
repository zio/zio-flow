package zio.flow

import java.util.Locale

import zio.flow.utils.RemoteAssertionSyntax.RemoteAssertionOps
import zio.test.TestAspect.ignore
import zio.test._

object RemoteStringSpec extends DefaultRunnableSpec {
  override def spec: ZSpec[Environment, Failure] = suite("RemoteStringSpec")(
    test("CharAt") {
      BoolAlgebra.all(
        Remote("abc").charAtOption(0) <-> Some('a'),
        Remote("abc").charAtOption(1) <-> Some('b'),
        Remote("abc").charAtOption(2) <-> Some('c'),
        Remote("abc").charAtOption(-1) <-> None,
        Remote("abc").charAtOption(3) <-> None
      )
    },
    test("CodepointAt") {
      BoolAlgebra.all(
        Remote("abc").codepointAtOption(0) <-> Some(97),
        Remote("abc").codepointAtOption(1) <-> Some(98),
        Remote("abc").codepointAtOption(2) <-> Some(99),
        Remote("abc").codepointAtOption(-1) <-> None,
        Remote("abc").codepointAtOption(3) <-> None
      )
    },
    test("CodepointBefore") {
      BoolAlgebra.all(
        Remote("abc").codepointBeforeOption(0) <-> None,
        Remote("abc").codepointBeforeOption(1) <-> Some(97),
        Remote("abc").codepointBeforeOption(2) <-> Some(98),
        Remote("abc").codepointBeforeOption(3) <-> Some(99),
        Remote("abc").codepointAtOption(-1) <-> None
      )
    },
    test("CompareIgnoreCase") {
      BoolAlgebra.all(
        Remote("a").compareToIgnoreCase("B") <-> -1,
        Remote("A").compareToIgnoreCase("b") <-> -1,
        Remote("B").compareToIgnoreCase("a") <-> 1,
        Remote("b").compareToIgnoreCase("A") <-> 1,
        Remote("a").compareToIgnoreCase("A") <-> 0,
        Remote("A").compareToIgnoreCase("a") <-> 0,
        Remote("a").compareToIgnoreCase("a") <-> 0
      )
    },
    test("Concat") {
      BoolAlgebra.all(
        Remote("abc") ++ Remote("123") <-> "abc123",
        Remote("123") ++ Remote("abc") <-> "123abc",
        Remote("abc") ++ Remote("") <-> "abc",
        Remote("") ++ Remote("abc") <-> "abc"
      )
    },
    test("Contains char") {
      BoolAlgebra.all(
        Remote("abc").contains('a') <-> true,
        Remote("abc").contains('b') <-> true,
        Remote("abc").contains('c') <-> true,
        Remote("abc").contains('d') <-> false
      )
    } @@ ignore, // TODO: remove ignore when Remote.Equal is implemented
    test("Contains slice") {
      BoolAlgebra.all(
        Remote("abc").contains("ab") <-> true,
        Remote("abc").contains("bc") <-> true,
        Remote("abc").contains("ac") <-> false,
        Remote("abc").contains("abc") <-> true,
        Remote("").contains("ab") <-> false,
        Remote("abc").contains("") <-> true
      )
    } @@ ignore,
    test("Index of char") {
      BoolAlgebra.all(
        Remote("abc").indexOf('a', 0) <-> 0,
        Remote("abc").indexOf('b', 1) <-> 1,
        Remote("abc").indexOf('b', 2) <-> -1,
        Remote("abc").indexOf('d') <-> -1,
        Remote("永").indexOf('永') <-> 0,
        Remote("a永").indexOf('永') <-> 1
      )
    },
    test("Index of substring") {
      BoolAlgebra.all(
        Remote("abc").indexOf("abc") <-> 0,
        Remote("abc").indexOf("") <-> 0,
        Remote("abc").indexOf("d") <-> -1,
        Remote("abc").indexOf("bcd") <-> -1,
        Remote("abc").indexOf("永") <-> -1,
        Remote("永遠").indexOf("永遠", 0) <-> 0,
        Remote("永遠に").indexOf("遠に") <-> 1,
        Remote("永遠に").indexOf("遠に", 2) <-> -1
      )
    },
    test("Index of slice") {
      BoolAlgebra.all(
        Remote("abc").indexOfSlice(List('a', 'b')) <-> 0
      )
    } @@ ignore, // TODO: complete/remove ignore when Remote.LessThanEqual gets fixed
    test("Is empty") {
      BoolAlgebra.all(
        Remote("").isEmpty <-> true,
        Remote(" ").isEmpty <-> false,
        Remote("a").isEmpty <-> false
      )
    },
    test("Last index of char") {
      BoolAlgebra.all(
        Remote("abc").lastIndexOf('a', 0) <-> 0,
        Remote("abc").lastIndexOf('b', 1) <-> 1,
        Remote("abc").lastIndexOf('b', 2) <-> 1,
        Remote("abca").lastIndexOf('a') <-> 3,
        Remote("abc").lastIndexOf('d') <-> -1,
        Remote("永").lastIndexOf('永') <-> 0,
        Remote("a永").lastIndexOf('永') <-> 1
      )
    },
    test("Last index of string") {
      BoolAlgebra.all(
        Remote("abcabc").lastIndexOf("abc") <-> 3,
        Remote("abcabc").lastIndexOf("abcd") <-> -1,
        Remote("bbbaaa").lastIndexOf("a", 4) <-> 4,
        Remote("永遠").lastIndexOf("永遠", 0) <-> 0,
        Remote("永遠永遠永遠").lastIndexOf("永遠") <-> 4
      )
    },
    test("Last option") {
      BoolAlgebra.all(
        Remote("abc").lastOption <-> Some('c'),
        Remote("永遠").lastOption <-> Some('遠'),
        Remote(" ").lastOption <-> Some(' '),
        Remote("").lastOption <-> None
      )
    },
    test("Length") {
      BoolAlgebra.all(
        Remote("").length <-> 0,
        Remote("a").length <-> 1,
        Remote("ab").length <-> 2,
        Remote("\t\n").length <-> 2
      )
    },
    test("Matches regex") {
      BoolAlgebra.all(
        Remote("foo").matches("foo") <-> true,
        Remote("foo").matches("fo+") <-> true,
        Remote("oof").matches("fo+") <-> false
      )
    },
    test("Relational") {
      BoolAlgebra.all(
        (Remote("a") === "a") <-> true,
        (Remote("a") === "b") <-> false,
        (Remote("a") > "b") <-> false,
        (Remote("a") < "b") <-> true
      )
    } @@ ignore, // TODO: remove ignore when Remote.LessThanEqual gets fixed
    test("Reverse") {
      BoolAlgebra.all(
        Remote("foo").reverse <-> "oof",
        Remote("oof").reverse <-> "foo",
        Remote("").reverse <-> ""
      )
    },
    test("Substring option") {
      BoolAlgebra.all(
        Remote("abc").substringOption(-1) <-> None,
        Remote("abc").substringOption(0) <-> Some("abc")
      )
    } @@ ignore, // TODO: remove ignore when Remote.LessThanEqual gets fixed
    test("To lowercase") {
      BoolAlgebra.all(
        Remote("abc").toLowerCase <-> "abc",
        Remote("ABC").toLowerCase <-> "abc",
        Remote("bugün nasılsın").toLowerCase(Locale.forLanguageTag("tr")) <-> "bugün nasılsın",
        Remote("BUGÜN NASILSIN").toLowerCase(Locale.forLanguageTag("tr")) <-> "bugün nasılsın"
      )
    },
    test("To uppercase") {
      BoolAlgebra.all(
        Remote("abc").toUpperCase <-> "ABC",
        Remote("ABC").toUpperCase <-> "ABC",
        Remote("bugün nasılsın").toUpperCase(Locale.forLanguageTag("tr")) <-> "BUGÜN NASILSIN",
        Remote("BUGÜN NASILSIN").toUpperCase(Locale.forLanguageTag("tr")) <-> "BUGÜN NASILSIN"
      )
    }
  )
}
