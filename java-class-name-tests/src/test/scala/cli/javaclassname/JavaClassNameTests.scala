package cli.javaclassname

import com.eed3si9n.expecty.Expecty.expect

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
    expect(extractClassName("Foo.java", content) == expectedClassName)
  }

  test("no package") {
    val content =
      """public class NoPackage {}
        |""".stripMargin
    expect(extractClassName("NoPackage.java", content) == "NoPackage")
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
    expect(extractClassName("Generic.java", content) == "Generic")
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
    expect(extractClassName("VoidMethods.java", content) == "VoidMethods")
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
    expect(extractClassName("Iface.java", content) == "Iface")
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
    expect(extractClassName("Annot.java", content) == "Annot")
  }

  test("record with reference components") {
    val content =
      """package a;
        |
        |public record RefRecord(String a, Object b) {}
        |""".stripMargin
    expect(extractClassName("RefRecord.java", content) == "RefRecord")
  }

  test("record with primitive components") {
    val content =
      """package a;
        |
        |public record PrimRecord(int a, String b) {}
        |""".stripMargin
    expect(extractClassName("PrimRecord.java", content) == "PrimRecord")
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
    expect(extractClassName("AllPrims.java", content) == "AllPrims")
  }

  // https://github.com/VirtusLab/scala-cli/issues/4514
  test("enum") {
    val content =
      """package a;
        |
        |public enum SimpleEnum { A, B }
        |""".stripMargin
    expect(extractClassName("SimpleEnum.java", content) == "SimpleEnum")
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
    expect(extractClassName("MethodEnum.java", content) == "MethodEnum")
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
    expect(extractClassName("FancyEnum.java", content) == "FancyEnum")
  }

  // https://github.com/VirtusLab/scala-cli/issues/4515
  test("package-private class") {
    val content =
      """package a;
        |
        |class PackagePrivate {}
        |""".stripMargin
    expect(extractClassName("PackagePrivate.java", content) == "")
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
    expect(extractClassName("Second.java", content) == "Second")
  }

  test("package-private interface, enum and record") {
    val content =
      """package a.b.c;
        |
        |interface HiddenIface {}
        |enum HiddenEnum { A }
        |record HiddenRecord(int x) {}
        |""".stripMargin
    expect(extractClassName("HiddenIface.java", content) == "")
  }

  test("package-private class in the default package") {
    val content =
      """class DefaultPackagePrivate {}
        |""".stripMargin
    expect(extractClassName("DefaultPackagePrivate.java", content) == "")
  }

  test("public class after a package-private one in the default package") {
    val content =
      """class Helper {}
        |
        |public class Main {}
        |""".stripMargin
    expect(extractClassName("Main.java", content) == "Main")
  }

  // Java 21+ compact source files (JEP 445/463/512) have no
  // top-level type, so `javac` names the implicit class after the source file.
  test("unnamed class with top-level main") {
    val content =
      """String greeting = "hi";
        |
        |void main() {
        |  System.out.println(greeting);
        |}
        |""".stripMargin
    expect(extractClassName("Unnamed.java", content) == "Unnamed")
  }

  test("unnamed class with a helper class and a record after main") {
    val content =
      """import java.util.List;
        |
        |void main() {
        |  System.out.println(new Helper().greet(List.of(new Pair(1, "a"))));
        |}
        |
        |class Helper {
        |  String greet(List<Pair> ps) { return "hi " + ps; }
        |}
        |
        |record Pair(int n, String s) {}
        |""".stripMargin
    expect(extractClassName("WithHelpers.java", content) == "WithHelpers")
  }

  test("unnamed class with a field before main") {
    val content =
      """static final int N = 1;
        |String greeting = "hi";
        |void main() { System.out.println(greeting + N); }
        |""".stripMargin
    expect(extractClassName("FieldFirst.java", content) == "FieldFirst")
  }

}
