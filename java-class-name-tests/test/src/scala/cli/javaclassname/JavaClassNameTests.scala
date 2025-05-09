package scala.cli.javaclassname

import utest._

object JavaClassNameTests extends TestSuite {

  val launcher = Option(System.getenv("JAVA_CLASS_NAME_CLI"))
    .map(os.Path(_, os.pwd))
    .getOrElse {
      sys.error("JAVA_CLASS_NAME_CLI not set")
    }

  val tests = Tests {
    test("simple") {
      val expectedClassName = "Foo"
      val content =
        s"""package a.b.c;
           |
           |public class $expectedClassName {
           |  private int n = 2;
           |  public String getThing() {
           |    return "a";
           |  }
           |}
           |""".stripMargin
      val tmpDir = os.temp.dir()
      try {
        os.write(tmpDir / "Foo.java", content)
        val res = os.proc(launcher, "Foo.java")
          .call(cwd = tmpDir)
        val className = res.out.text().trim
        assert(className == expectedClassName)
      }
      finally
        os.remove.all(tmpDir)
    }
  }

}
