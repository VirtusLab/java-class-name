//| mvnDeps:
//| - io.github.alexarchambault.mill::mill-native-image::0.2.0
//| - io.github.alexarchambault.mill::mill-native-image-upload:0.2.0
//| - com.goyeau::mill-scalafix::0.6.0
//| - com.lumidion::sonatype-central-client-requests:0.6.0
package build
import io.github.alexarchambault.millnativeimage.NativeImage
import io.github.alexarchambault.millnativeimage.upload.Upload

import coursier.core.{Dependency, DependencyManagement}
import coursier.version.VersionConstraint

import java.io.File

import scala.annotation.unused
import scala.concurrent.duration.DurationInt
import com.goyeau.mill.scalafix.ScalafixModule
import com.lumidion.sonatype.central.client.core.{PublishingType, SonatypeCredentials}

import mill.*
import mill.scalalib.*
import mill.api.{BuildCtx, Task}
import mill.scalalib.publish.{Developer, License, PomSettings, VersionControl}
import mill.util.{Tasks, VcsVersion}

object Versions {
  def scala          = "3.3.6"
  def scalaCli       = "1.8.4"
  def graalVmVersion = "22.3.1"
  def coursier       = "2.1.24"
  def osLib          = "0.11.4"
  def uTest          = "0.8.9"
  def jline          = "3.25.0"
  def ubuntu         = "24.04"
}

trait JavaMainClassNativeImage extends NativeImage {
  def nativeImageOptions: T[Seq[String]] = Task {
    super.nativeImageOptions() ++ Seq(
      "--no-fallback"
    )
  }

  def nativeImagePersist: Boolean = System.getenv("CI") != null
  def nativeImageGraalVmJvmId     = s"graalvm-java17:${Versions.graalVmVersion}"
  def nativeImageName             = "java-class-name"
  def nativeImageMainClass        = "scala.cli.javaclassname.JavaClassName"
  def nameSuffix                  = ""

  @unused
  def copyToArtifacts(directory: String = "artifacts/"): Command[Unit] = Task.Command {
    val _ = Upload.copyLauncher0(
      nativeLauncher = nativeImage().path,
      directory = directory,
      name = "java-class-name",
      compress = true,
      workspace = BuildCtx.workspaceRoot,
      suffix = nameSuffix
    )
  }
}

trait JavaClassNameModule extends ScalaModule with ScalafixModule {
  override def scalacOptions: T[Seq[String]] =
    super.scalacOptions() ++ Seq("-Wunused:all")

  override def scalaVersion: T[String] = Versions.scala

  private def jlineOrg = "org.jline"

  def jlineDeps: Seq[Dep] = Seq(
    mvn"$jlineOrg:jline-reader:${Versions.jline}",
    mvn"$jlineOrg:jline-terminal:${Versions.jline}",
    mvn"$jlineOrg:jline-terminal-jna:${Versions.jline}",
    mvn"$jlineOrg:jline-terminal-jni:${Versions.jline}",
    mvn"$jlineOrg:jline-native:${Versions.jline}"
  )

  override def coursierDependency: Dependency =
    super.coursierDependency
      .addOverrides(
        jlineDeps.map(jd =>
          DependencyManagement.Key.from(jd.toDependency(jd.version, jd.version, "")) ->
            DependencyManagement.Values.empty.withVersionConstraint(
              VersionConstraint.Lazy(Versions.jline)
            )
        )
      )

  override def allMvnDeps: T[Seq[Dep]] = Task {
    super.allMvnDeps()
      .map(_.exclude(jlineDeps.map(d => d.organization -> d.name)*)) ++ jlineDeps
  }

  override def mvnDeps: T[Seq[Dep]] =
    super.mvnDeps().map(_.exclude(jlineDeps
      .map(d => d.organization -> d.name)*)) ++ jlineDeps
}

object `scala3-graal-processor` extends JavaClassNameModule {
  override def mainClass: T[Option[String]] = Some("scala.cli.graal.CoursierCacheProcessor")

  override def mvnDeps: T[Seq[Dep]] = jlineDeps ++ Seq(
    mvn"org.virtuslab.scala-cli::scala3-graal:${Versions.scalaCli}"
  )
}

object `java-class-name` extends JavaClassNameModule with JavaMainClassNativeImage
    with JavaClassNamePublishModule {
  def nativeImageClassPath: T[Seq[PathRef]] = Task {
    // adapted from https://github.com/VirtusLab/scala-cli/blob/b19086697401827a6f8185040ceb248d8865bf21/build.sc#L732-L744

    val classpath = runClasspath().map(_.path).mkString(File.pathSeparator)
    val cache     = Task.dest / "native-cp"
    // `scala3-graal-processor`.run() do not give me output and I cannot pass dynamically computed values like classpath
    System.err.println("Calling scala3 graal processor on")
    for (f <- classpath.split(File.pathSeparator))
      System.err.println(s"  $f")
    val res = mill.util.Jvm.callProcess(
      mainClass = `scala3-graal-processor`.finalMainClass(),
      classPath = `scala3-graal-processor`.runClasspath().map(_.path),
      mainArgs = Seq(cache.toNIO.toString, classpath)
    )
    val cp = res.out.trim()
    if cp.isBlank then System.err.println("class path can't be empty!")
    assert(cp.nonEmpty)
    System.err.println("Processed class path:")
    for (f <- cp.split(File.pathSeparator))
      System.err.println(s"  $f")
    cp.split(File.pathSeparator).toSeq.map(p => mill.PathRef(os.Path(p)))
  }

  override def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ jlineDeps ++ Seq(
    mvn"org.scala-lang::scala3-compiler:${Versions.scala}"
  )

  override def compileMvnDeps: T[Seq[Dep]] = super.compileMvnDeps() ++ Seq(
    mvn"org.graalvm.nativeimage:svm:${Versions.graalVmVersion}"
  )

  object static extends JavaMainClassNativeImage {
    def nameSuffix = "-static"

    def nativeImageClassPath: T[Seq[PathRef]] = Task {
      `java-class-name`.nativeImageClassPath()
    }

    def buildHelperImage: T[Unit] = Task {
      os.proc("docker", "build", "-t", "scala-cli-base-musl:latest", ".")
        .call(cwd = BuildCtx.workspaceRoot / "musl-image", stdout = os.Inherit)
      ()
    }

    def nativeImageDockerParams: T[Option[NativeImage.DockerParams]] = Task {
      buildHelperImage()
      Some(
        NativeImage.linuxStaticParams(
          "scala-cli-base-musl:latest",
          s"https://github.com/coursier/coursier/releases/download/v${Versions.coursier}/cs-x86_64-pc-linux.gz"
        )
      )
    }

    def writeNativeImageScript(scriptDest: String, imageDest: String = ""): Command[Unit] =
      Task.Command {
        buildHelperImage()
        super.writeNativeImageScript(scriptDest, imageDest)()
      }
  }

  object `mostly-static` extends JavaMainClassNativeImage {
    def nameSuffix = "-mostly-static"

    def nativeImageClassPath: T[Seq[PathRef]] = Task {
      `java-class-name`.nativeImageClassPath()
    }

    def nativeImageDockerParams: T[Option[NativeImage.DockerParams]] = Some(
      NativeImage.linuxMostlyStaticParams(
        s"ubuntu:${Versions.ubuntu}",
        s"https://github.com/coursier/coursier/releases/download/v${Versions.coursier}/cs-x86_64-pc-linux.gz"
      )
    )
  }
}

trait Tests(jlineDeps: Seq[Dep]) extends ScalaModule with TestModule.Utest {
  def launcher: T[PathRef]

  def mvnDeps: T[Seq[Dep]] = super.mvnDeps() ++ jlineDeps ++ Seq(
    mvn"com.lihaoyi::os-lib:${Versions.osLib}",
    mvn"com.lihaoyi::utest:${Versions.uTest}"
  )

  def testFramework = "utest.runner.Framework"

  def forkEnv: T[Map[String, String]] = super.forkEnv() ++ Seq(
    "JAVA_CLASS_NAME_CLI" -> launcher().path.toString
  )
}

object `java-class-name-tests` extends JavaClassNameModule with SbtModule {
  object test extends Tests(jlineDeps = jlineDeps) with super.SbtTests {
    def launcher: T[PathRef] = `java-class-name`.nativeImage()
  }
}

object `java-class-name-static-tests` extends JavaClassNameModule with SbtModule {
  object test extends Tests(jlineDeps = jlineDeps) with super.SbtTests {
    def sources: T[Seq[PathRef]] = `java-class-name-tests`.test.sources()
    def launcher: T[PathRef]     = `java-class-name`.static.nativeImage()
  }
}

object `java-class-name-mostly-static-tests` extends JavaClassNameModule with SbtModule {
  object test extends Tests(jlineDeps = jlineDeps) with super.SbtTests {
    def sources: T[Seq[PathRef]] = `java-class-name-tests`.test.sources()
    def launcher: T[PathRef]     = `java-class-name`.`mostly-static`.nativeImage()
  }
}

def publishVersion0: T[String] = Task {
  val state = VcsVersion.vcsState()
  if state.commitsSinceLastTag > 0 then {
    val versionOrEmpty = state.lastTag
      .filter(_ != "latest")
      .map(_.stripPrefix("v"))
      .flatMap { tag =>
        val idx = tag.lastIndexOf(".")
        if idx >= 0 then
          Some(tag.take(idx + 1) + (tag.drop(idx + 1).toInt + 1).toString + "-SNAPSHOT")
        else None
      }
      .getOrElse("0.0.1-SNAPSHOT")
    Some(versionOrEmpty)
      .filter(_.nonEmpty)
      .getOrElse(state.format())
  }
  else
    state
      .lastTag
      .getOrElse(state.format())
      .stripPrefix("v")
}

def ghOrg      = "VirtusLab"
def ghName     = "java-class-name"
def publishOrg = "org.virtuslab.scala-cli.java-class-name"

trait JavaClassNamePublishModule extends SonatypeCentralPublishModule {
  def pomSettings: T[PomSettings] = PomSettings(
    description = artifactName(),
    organization = publishOrg,
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

  def publishVersion: T[String] = publishVersion0()
}

@unused
object ci extends Module {
  @unused
  def publishSonatype(tasks: Tasks[PublishModule.PublishData]): Command[Unit] =
    Task.Command {
      val publishVersion = publishVersion0()
      System.err.println(s"Publish version: $publishVersion")
      val bundleName = s"$publishOrg-$ghName-$publishVersion"
      System.err.println(s"Publishing bundle: $bundleName")
      publishSonatype0(
        data = Task.sequence(tasks.value)(),
        log = Task.ctx().log,
        workspace = BuildCtx.workspaceRoot,
        env = Task.env,
        bundleName = bundleName
      )
    }

  private def publishSonatype0(
    data: Seq[PublishModule.PublishData],
    log: mill.api.Logger,
    workspace: os.Path,
    env: Map[String, String],
    bundleName: String
  ): Unit = {

    val credentials = SonatypeCredentials(
      username = sys.env("SONATYPE_USERNAME"),
      password = sys.env("SONATYPE_PASSWORD")
    )
    val pgpPassword = sys.env("PGP_PASSWORD")
    val timeout     = 10.minutes

    val artifacts = data.map { case PublishModule.PublishData(a, s) =>
      (s.map { case (p, f) => (p.path, f) }, a)
    }

    val isRelease = {
      val versions = artifacts.map(_._2.version).toSet
      val set      = versions.map(!_.endsWith("-SNAPSHOT"))
      assert(
        set.size == 1,
        s"Found both snapshot and non-snapshot versions: ${versions.toVector.sorted.mkString(", ")}"
      )
      set.head
    }
    val publisher = new SonatypeCentralPublisher(
      credentials = credentials,
      gpgArgs = Seq(
        "--detach-sign",
        "--batch=true",
        "--yes",
        "--pinentry-mode",
        "loopback",
        "--passphrase",
        pgpPassword,
        "--armor",
        "--use-agent"
      ),
      readTimeout = timeout.toMillis.toInt,
      connectTimeout = timeout.toMillis.toInt,
      log = log,
      workspace = workspace,
      env = env,
      awaitTimeout = timeout.toMillis.toInt
    )

    val publishingType = if isRelease then PublishingType.AUTOMATIC else PublishingType.USER_MANAGED
    val finalBundleName = if bundleName.nonEmpty then Some(bundleName) else None
    publisher.publishAll(
      publishingType = publishingType,
      singleBundleName = finalBundleName,
      artifacts = artifacts*
    )
  }

  @unused
  def upload(directory: String = "artifacts/"): Command[Unit] = Task.Command {
    val version: String = publishVersion0()

    val path      = os.Path(directory, BuildCtx.workspaceRoot)
    val launchers = os.list(path).filter(os.isFile(_)).map { path =>
      path -> path.last
    }
    val ghToken = Option(System.getenv("UPLOAD_GH_TOKEN")).getOrElse {
      sys.error("UPLOAD_GH_TOKEN not set")
    }
    val (tag, overwriteAssets) =
      if version.endsWith("-SNAPSHOT") then ("nightly", true) else ("v" + version, false)

    Upload.upload(
      ghOrg,
      ghName,
      ghToken,
      tag,
      dryRun = false,
      overwrite = overwriteAssets
    )(launchers*)
  }
}
