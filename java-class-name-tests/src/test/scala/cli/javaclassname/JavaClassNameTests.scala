package cli.javaclassname

class JavaClassNameTests extends munit.FunSuite {

  val launcher = Option(System.getenv("JAVA_CLASS_NAME_CLI"))
    .map(os.Path(_, os.pwd))
    .getOrElse {
      sys.error("JAVA_CLASS_NAME_CLI not set")
    }

  private def extractClassName(fileName: String, content: String): String = {
    val tmpDir = os.temp.dir()
    try {
      os.write(tmpDir / fileName, content)
      val res = os.proc(launcher, fileName)
        .call(cwd = tmpDir)
      res.out.text().trim
    }
    finally
      os.remove.all(tmpDir)
  }

  test("simple") {
    val expectedClassName = "Foo"
    val content           =
      s"""package a.b.c;
         |
         |public class $expectedClassName {
         |  private int n = 2;
         |  public String getThing() {
         |    return "a";
         |  }
         |}
         |""".stripMargin
    assertEquals(extractClassName("Foo.java", content), expectedClassName)
  }

  test("no package") {
    val content =
      """public class NoPackage {}
        |""".stripMargin
    assertEquals(extractClassName("NoPackage.java", content), "NoPackage")
  }

  test("generic class") {
    val content =
      """package a;
        |
        |import java.util.*;
        |
        |public class Generic<T extends Comparable<T>, U> extends ArrayList<T> implements Map.Entry<T, U> {
        |  public T getKey() { return null; }
        |  public U getValue() { return null; }
        |  public U setValue(U u) { return u; }
        |}
        |""".stripMargin
    assertEquals(extractClassName("Generic.java", content), "Generic")
  }

  test("void methods") {
    val content =
      """package a;
        |
        |public class VoidMethods {
        |  private int n = 2;
        |  public void run() {}
        |  public static void main(String[] args) {}
        |  void g(int x, long y) {}
        |}
        |""".stripMargin
    assertEquals(extractClassName("VoidMethods.java", content), "VoidMethods")
  }

  test("interface") {
    val content =
      """package a;
        |
        |public interface Iface<T> {
        |  void f();
        |  default int g() { return 1; }
        |}
        |""".stripMargin
    assertEquals(extractClassName("Iface.java", content), "Iface")
  }

  test("annotation") {
    val content =
      """package a;
        |
        |public @interface Annot {
        |  String value() default "";
        |  int n() default 0;
        |}
        |""".stripMargin
    assertEquals(extractClassName("Annot.java", content), "Annot")
  }

  test("record with reference components") {
    val content =
      """package a;
        |
        |public record RefRecord(String a, Object b) {}
        |""".stripMargin
    assertEquals(extractClassName("RefRecord.java", content), "RefRecord")
  }

  // TODO: primitive types need initialized compiler definitions, the launcher crashes with an NPE
  // https://github.com/VirtusLab/scala-cli/issues/4516
  test("record with primitive components".ignore) {
    val content =
      """package a;
        |
        |public record PrimRecord(int a, String b) {}
        |""".stripMargin
    assertEquals(extractClassName("PrimRecord.java", content), "PrimRecord")
  }

  // TODO: enums need initialized compiler definitions, the launcher crashes with an NPE
  // https://github.com/VirtusLab/scala-cli/issues/4514
  test("enum".ignore) {
    val content =
      """package a;
        |
        |public enum SimpleEnum { A, B }
        |""".stripMargin
    assertEquals(extractClassName("SimpleEnum.java", content), "SimpleEnum")
  }

  // TODO: enums need initialized compiler definitions, the launcher crashes with an NPE
  // https://github.com/VirtusLab/scala-cli/issues/4514
  test("enum with methods".ignore) {
    val content =
      """package a;
        |
        |public enum MethodEnum {
        |  A, B;
        |  void f() {}
        |  int g() { return 1; }
        |}
        |""".stripMargin
    assertEquals(extractClassName("MethodEnum.java", content), "MethodEnum")
  }

  // TODO: package-private classes aren't filtered out, "PackagePrivate" is printed
  // https://github.com/VirtusLab/scala-cli/issues/4515
  test("package-private class".ignore) {
    val content =
      """package a;
        |
        |class PackagePrivate {}
        |""".stripMargin
    assertEquals(extractClassName("PackagePrivate.java", content), "")
  }

  // TODO: package-private classes aren't filtered out, "Helper" is printed
  // https://github.com/VirtusLab/scala-cli/issues/4515
  test("public class after a package-private one".ignore) {
    val content =
      """package a;
        |
        |class Helper {}
        |
        |public class Second {}
        |""".stripMargin
    assertEquals(extractClassName("Second.java", content), "Second")
  }

}
