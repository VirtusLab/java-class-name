// copied from https://github.com/VirtusLab/scala-cli/blob/b19086697401827a6f8185040ceb248d8865bf21/modules/scala3-graal-processor/src/scala/cli/graal/CoursierCacheProcessor.scala
// remove once the scala3-graal-processor module of Scala CLI is published, and can be used from here

package scala.cli.graal

import java.io.File

object CoursierCacheProcessor {
  def main(args: Array[String]) = {
    val List(cacheDir, classpath) = args.toList
    val cache                     = DirCache(os.Path(cacheDir, os.pwd))

    val newCp = BytecodeProcessor.processClassPath(classpath, cache).map(_.nioPath)

    println(newCp.mkString(File.pathSeparator))
  }
}
