package scala.cli.javaclassname

import java.nio.file.{Files, Paths}

object JavaClassName {

  private def printHelp(): Unit = {
    System.err.println(
      """Extracts class names out of Java sources
        |
        |Usage: java-class-name java-source-path
        |""".stripMargin
    )
  }

  def main(args: Array[String]): Unit = {
    val p = args match {
      case Array("--help" | "-h" | "-help") =>
        printHelp()
        sys.exit(0)
      case Array(path) =>
        Paths.get(path)
      case _ =>
        printHelp()
        sys.exit(1)
    }
    val content = Files.readAllBytes(p)
    val classNameOpt = JavaParser.parseRootPublicClassName(content)
    for (className <- classNameOpt)
      println(className)
  }
}
