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

  test("record with primitive components") {
    val content =
      """package a;
        |
        |public record PrimRecord(int a, String b) {}
        |""".stripMargin
    assertEquals(extractClassName("PrimRecord.java", content), "PrimRecord")
  }

  test("record with all primitive kinds, arrays and varargs") {
    val content =
      """package a;
        |
        |public record AllPrims(
        |  byte a, short b, char c, int d, long e, float f, double g, boolean h,
        |  int[] i, double[][] j, long... k
        |) {}
        |""".stripMargin
    assertEquals(extractClassName("AllPrims.java", content), "AllPrims")
  }

  // https://github.com/VirtusLab/scala-cli/issues/4514
  test("enum") {
    val content =
      """package a;
        |
        |public enum SimpleEnum { A, B }
        |""".stripMargin
    assertEquals(extractClassName("SimpleEnum.java", content), "SimpleEnum")
  }

  // https://github.com/VirtusLab/scala-cli/issues/4514
  test("enum with methods") {
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

  // https://github.com/VirtusLab/scala-cli/issues/4514
  test("enum with constructor args, constant bodies and interfaces") {
    val content =
      """package a;
        |
        |public enum FancyEnum implements Runnable, java.io.Serializable {
        |  A(1) { public void run() {} },
        |  B(2, "b");
        |  private final int n;
        |  FancyEnum(int n) { this(n, ""); }
        |  FancyEnum(int n, String s) { this.n = n; }
        |  public void run() {}
        |  public static void main(String[] args) { System.out.println(values().length); }
        |}
        |""".stripMargin
    assertEquals(extractClassName("FancyEnum.java", content), "FancyEnum")
  }

  // https://github.com/VirtusLab/scala-cli/issues/4515
  test("package-private class") {
    val content =
      """package a;
        |
        |class PackagePrivate {}
        |""".stripMargin
    assertEquals(extractClassName("PackagePrivate.java", content), "")
  }

  // https://github.com/VirtusLab/scala-cli/issues/4515
  test("public class after a package-private one") {
    val content =
      """package a;
        |
        |class Helper {}
        |
        |public class Second {}
        |""".stripMargin
    assertEquals(extractClassName("Second.java", content), "Second")
  }

  test("package-private interface, enum and record") {
    val content =
      """package a.b.c;
        |
        |interface HiddenIface {}
        |enum HiddenEnum { A }
        |record HiddenRecord(int x) {}
        |""".stripMargin
    assertEquals(extractClassName("HiddenIface.java", content), "")
  }

  test("package-private class in the default package") {
    val content =
      """class DefaultPackagePrivate {}
        |""".stripMargin
    assertEquals(extractClassName("DefaultPackagePrivate.java", content), "")
  }

  test("public class after a package-private one in the default package") {
    val content =
      """class Helper {}
        |
        |public class Main {}
        |""".stripMargin
    assertEquals(extractClassName("Main.java", content), "Main")
  }

  // TODO: Java 21+ unnamed classes (JEP 445/463/512 "compact source files") crash the launcher.
  // Top-level members without an enclosing type are parsed via `termDecl`, which builds the `void`
  // return type from `defn.UnitType` and NPEs without initialized compiler definitions. Even once
  // that is bypassed, the stock parser returns `EmptyTree` for compact compilation units, so no
  // name would be printed. `javac` names the implicit class after the source file, so the launcher
  // should arguably print the file name stem (or Scala CLI should handle this case itself).
  test("unnamed class with top-level main".ignore) {
    val content =
      """String greeting = "hi";
        |
        |void main() {
        |  System.out.println(greeting);
        |}
        |""".stripMargin
    assertEquals(extractClassName("Unnamed.java", content), "Unnamed")
  }

}
