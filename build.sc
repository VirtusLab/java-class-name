import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`
import $ivy.`io.github.alexarchambault.mill::mill-native-image::0.1.26`
import $ivy.`io.github.alexarchambault.mill::mill-native-image-upload:0.1.21`

import de.tobiasroeser.mill.vcs.version._
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload
import mill._
import mill.scalalib._
import coursier.core.Version
import scala.concurrent.duration.DurationInt

import java.io.File

trait JavaMainClassNativeImage extends NativeImage {

  def nativeImageOptions = T{
    super.nativeImageOptions() ++ Seq(
      "--no-fallback"
    )
  }
  def nativeImagePersist = System.getenv("CI") != null
  def graalVmVersion = "22.1.0"
  def nativeImageGraalVmJvmId = s"graalvm-java17:$graalVmVersion"
  def nativeImageName = "java-class-name"
  def nativeImageMainClass = "scala.cli.javaclassname.JavaClassName"

  def nameSuffix = ""
  def copyToArtifacts(directory: String = "artifacts/") = T.command {
    val _ = Upload.copyLauncher(
      nativeImage().path,
      directory,
      "java-class-name",
      compress = true,
      suffix = nameSuffix
    )
  }
}

object `scala3-graal-processor` extends ScalaModule {
  def scalaVersion = "3.3.1"
  def mainClass = Some("scala.cli.graal.CoursierCacheProcessor")
  def ivyDeps = Agg(
    ivy"org.virtuslab.scala-cli::scala3-graal:1.0.5"
  )
}

object `java-class-name` extends ScalaModule with JavaMainClassNativeImage with JavaClassNamePublishModule {
  def scalaVersion = "3.3.1"

  def nativeImageClassPath = T {
    // adapted from https://github.com/VirtusLab/scala-cli/blob/b19086697401827a6f8185040ceb248d8865bf21/build.sc#L732-L744

    val classpath = runClasspath().map(_.path).mkString(File.pathSeparator)
    val cache     = T.dest / "native-cp"
    // `scala3-graal-processor`.run() do not give me output and I cannot pass dynamically computed values like classpath
    System.err.println("Calling scala3 graal processor on")
    for (f <- classpath.split(File.pathSeparator))
      System.err.println(s"  $f")
    val res = mill.modules.Jvm.callSubprocess(
      mainClass = `scala3-graal-processor`.finalMainClass(),
      classPath = `scala3-graal-processor`.runClasspath().map(_.path),
      mainArgs = Seq(cache.toNIO.toString, classpath),
      workingDir = os.pwd
    )
    val cp = res.out.text.trim
    System.err.println("Processed class path:")
    for (f <- cp.split(File.pathSeparator))
      System.err.println(s"  $f")
    cp.split(File.pathSeparator).toSeq.map(p => mill.PathRef(os.Path(p)))
  }
  def ivyDeps = super.ivyDeps() ++ Seq(
    ivy"org.scala-lang::scala3-compiler:${scalaVersion()}"
  )
  def compileIvyDeps = super.compileIvyDeps() ++ Seq(
    ivy"org.graalvm.nativeimage:svm:$graalVmVersion"
  )

  object static extends JavaMainClassNativeImage {
    def nameSuffix = "-static"
    def nativeImageClassPath = T{
      `java-class-name`.nativeImageClassPath()
    }
    def buildHelperImage = T {
      os.proc("docker", "build", "-t", "scala-cli-base-musl:latest", ".")
        .call(cwd = os.pwd / "musl-image", stdout = os.Inherit)
      ()
    }
    def nativeImageDockerParams = T{
      buildHelperImage()
      Some(
        NativeImage.linuxStaticParams(
          "scala-cli-base-musl:latest",
          s"https://github.com/coursier/coursier/releases/download/v$csDockerVersion/cs-x86_64-pc-linux.gz"
        )
      )
    }
    def writeNativeImageScript(scriptDest: String, imageDest: String = "") = T.command {
      buildHelperImage()
      super.writeNativeImageScript(scriptDest, imageDest)()
    }
  }

  object `mostly-static` extends JavaMainClassNativeImage {
    def nameSuffix = "-mostly-static"
    def nativeImageClassPath = T{
      `java-class-name`.nativeImageClassPath()
    }
    def nativeImageDockerParams = Some(
      NativeImage.linuxMostlyStaticParams(
        "ubuntu:18.04", // TODO Pin that?
        s"https://github.com/coursier/coursier/releases/download/v$csDockerVersion/cs-x86_64-pc-linux.gz"
      )
    )
  }
}

object `java-class-name-tests` extends ScalaModule {
  def scalaVersion = "3.3.1"
  trait Tests extends super.Tests {
    def launcher: T[PathRef]
    def ivyDeps = super.ivyDeps() ++ Seq(
      ivy"com.lihaoyi::os-lib:0.9.2",
      ivy"com.lihaoyi::utest:0.8.2"
    )
    def testFramework = "utest.runner.Framework"
    def forkEnv = super.forkEnv() ++ Seq(
      "JAVA_CLASS_NAME_CLI" -> launcher().path.toString
    )
  }
  object test extends Tests {
    def launcher = `java-class-name`.nativeImage()
  }
  object static extends Tests {
    def sources = T.sources(`java-class-name-tests`.test.sources())
    def launcher = `java-class-name`.static.nativeImage()
  }
  object `mostly-static` extends Tests {
    def sources = T.sources(`java-class-name-tests`.test.sources())
    def launcher = `java-class-name`.`mostly-static`.nativeImage()
  }
}

def csDockerVersion = "2.1.0-M5-18-gfebf9838c"

def publishVersion0 = T {
  val state = VcsVersion.vcsState()
  if (state.commitsSinceLastTag > 0) {
    val versionOrEmpty = state.lastTag
      .filter(_ != "latest")
      .map(_.stripPrefix("v"))
      .flatMap { tag =>
        val idx = tag.lastIndexOf(".")
        if (idx >= 0) Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
        else None
      }
      .getOrElse("0.0.1-SNAPSHOT")
    Some(versionOrEmpty)
      .filter(_.nonEmpty)
      .getOrElse(state.format())
  } else
    state
      .lastTag
      .getOrElse(state.format())
      .stripPrefix("v")
}

def ghOrg = "VirtusLab"
def ghName = "java-class-name"
trait JavaClassNamePublishModule extends PublishModule {
  import mill.scalalib.publish._
  def pomSettings = PomSettings(
    description = artifactName(),
    organization = "org.virtuslab.scala-cli.java-class-name",
    url = s"https://github.com/$ghOrg/$ghName",
    licenses = Seq(License.`Apache-2.0`),
    versionControl = VersionControl.github(ghOrg, ghName),
    developers = Seq(
      Developer(
        "Gedochao",
        "Piotr Chabelski",
        "https://github.com/Gedochao",
        None
      ),
      Developer(
        "alexarchambault",
        "Alex Archambault",
        "https://github.com/alexarchambault",
        None
      )
    )
  )
  def publishVersion =
    publishVersion0()
}

object ci extends Module {
  def publishSonatype(tasks: mill.main.Tasks[PublishModule.PublishData]) = T.command {
    publishSonatype0(
      data = define.Target.sequence(tasks.value)(),
      log = T.ctx().log
    )
  }

  private def publishSonatype0(
      data: Seq[PublishModule.PublishData],
      log: mill.api.Logger
  ): Unit = {

    val credentials = sys.env("SONATYPE_USERNAME") + ":" + sys.env("SONATYPE_PASSWORD")
    val pgpPassword = sys.env("PGP_PASSWORD")
    val timeout = 10.minutes

    val artifacts = data.map { case PublishModule.PublishData(a, s) =>
      (s.map { case (p, f) => (p.path, f) }, a)
    }

    val isRelease = {
      val versions = artifacts.map(_._2.version).toSet
      val set = versions.map(!_.endsWith("-SNAPSHOT"))
      assert(
        set.size == 1,
        s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}"
      )
      set.head
    }
    val publisher = new scalalib.publish.SonatypePublisher(
      uri = "https://oss.sonatype.org/service/local",
      snapshotUri = "https://oss.sonatype.org/content/repositories/snapshots",
      credentials = credentials,
      signed = true,
      // format: off
      gpgArgs = Seq(
        "--detach-sign",
        "--batch=true",
        "--yes",
        "--pinentry-mode", "loopback",
        "--passphrase", pgpPassword,
        "--armor",
        "--use-agent"
      ),
      // format: on
      readTimeout = timeout.toMillis.toInt,
      connectTimeout = timeout.toMillis.toInt,
      log = log,
      awaitTimeout = timeout.toMillis.toInt,
      stagingRelease = isRelease
    )

    publisher.publishAll(isRelease, artifacts: _*)
  }

  def upload(directory: String = "artifacts/") = T.command {
    val version = publishVersion0()

    val path = os.Path(directory, os.pwd)
    val launchers = os.list(path).filter(os.isFile(_)).map { path =>
      path -> path.last
    }
    val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val (tag, overwriteAssets) =
      if (version.endsWith("-SNAPSHOT")) ("nightly", true)
      else ("v" + version, false)

    Upload.upload(ghOrg, ghName, ghToken, tag, dryRun = false, overwrite = overwriteAssets)(launchers: _*)
  }
}
