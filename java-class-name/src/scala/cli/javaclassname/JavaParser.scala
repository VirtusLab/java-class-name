package scala.cli.javaclassname

import dotty.tools.dotc.ast.untpd.ModuleDef
import dotty.tools.dotc.ast.{Trees, untpd}
import dotty.tools.dotc.core.Contexts.{Context, ContextBase}
import dotty.tools.dotc.core.Flags
import dotty.tools.dotc.core.StdNames.tpnme
import dotty.tools.dotc.parsing.JavaParsers.OutlineJavaParser
import dotty.tools.dotc.parsing.JavaTokens
import dotty.tools.dotc.util.SourceFile
import dotty.tools.io.VirtualFile

import scala.io.Codec

object JavaParser {

  /** The stock parser emits typed trees for `java.lang.Object`, `Unit` and the Java primitive
    * types, which require the compiler definitions to be initialized (and thus the Scala library on
    * the classpath). We only need names and modifiers, so we fall back to untyped trees and skip
    * the dummy constructors.
    */
  private class UntypedOutlineJavaParser(source: SourceFile)(using Context)
      extends OutlineJavaParser(source) {
    override def ObjectTpt(): untpd.Tree = javaLangDot(tpnme.Object)

    /** Set when the source is a Java 21+ compact source file (JEP 512, formerly "unnamed classes"):
      * top-level fields / methods with no enclosing type, which `javac` wraps in an implicit class
      * named after the source file.
      */
    var isCompactUnit: Boolean = false

    /** Type bodies are skipped by `typeBody` (and our `enumDecl`), so the stock parser only calls
      * `termDecl` for top-level members of compact source files. The stock implementation types
      * `void` via `defn.UnitType` and crashes with an NPE, and `compilationUnit` returns
      * `EmptyTree` for compact units anyway. We don't need the members, so we flag the unit as
      * compact and skip to the end of the source.
      */
    override def termDecl(start: Int, mods: untpd.Modifiers, parentToken: Int): List[untpd.Tree] = {
      isCompactUnit = true
      while in.token != JavaTokens.EOF do in.nextToken()
      List(untpd.EmptyTree) // non-empty, so `compilationUnit` treats the unit as compact
    }

    /** Primitive types show up in record headers (and method signatures), e.g. `record R(int a)`.
      * The stock implementation resolves them via `defn.IntType` & co, which crashes with an NPE
      * without initialized definitions. We only need class names, so any untyped placeholder tree
      * will do. Non-primitive tokens must still go through the stock syntax error reporting and
      * recovery (`skip()`), otherwise malformed input could throw off brace balancing.
      */
    override def basicType(): untpd.Tree =
      if JavaTokens.primTypes.contains(in.token) then
        atSpan(in.offset) {
          in.nextToken()
          ObjectTpt()
        }
      else super.basicType()

    /** The stock `enumDecl` synthesizes `values()` / `valueOf(String)` via `defn.StringType` and a
      * `java.lang.Enum[E]` parent, both of which crash with an NPE without initialized definitions.
      * We only need the enum's name and modifiers, so we parse the header, let `typeBody` skip the
      * body (constants included) and emit the plain class + companion pair like other declarations.
      */
    override def enumDecl(start: Int, mods: untpd.Modifiers): List[untpd.Tree] = {
      accept(JavaTokens.ENUM)
      val nameOffset      = in.offset
      val name            = identForType()
      val interfaces      = interfacesOpt()
      val (statics, body) = typeBody(JavaTokens.ENUM, name)
      val enumClass       = atSpan(start, nameOffset) {
        untpd.TypeDef(name, makeTemplate(interfaces, body, Nil, needsDummyConstr = false))
          .withMods(mods | Flags.JavaEnum)
      }
      addCompanionObject(statics, enumClass)
    }

    override def makeTemplate(
      parents: List[untpd.Tree],
      stats: List[untpd.Tree],
      tparams: List[untpd.TypeDef],
      needsDummyConstr: Boolean
    ): untpd.Template = super.makeTemplate(parents, stats, tparams, needsDummyConstr = false)
  }

  private enum Outline {
    case Types(stats: List[untpd.Tree])
    case Compact
  }

  private def parseOutline(byteContent: Array[Byte]): Outline = {
    given Context     = ContextBase().initialCtx.fresh
    val virtualFile   = VirtualFile("placeholder.java", byteContent)
    val sourceFile    = SourceFile(virtualFile, Codec.UTF8)
    val outlineParser = UntypedOutlineJavaParser(sourceFile)
    val tree          = outlineParser.parse()
    if outlineParser.isCompactUnit then Outline.Compact
    else
      Outline.Types(tree match {
        case pd: Trees.PackageDef[_] => pd.stats
        case _                       => Nil
      })
  }

  extension (mdef: untpd.DefTree) {

    /** The Java parser has no `Public` flag. Instead, package-private (and `protected`) members get
      * the enclosing package recorded as `privateWithin` (`<empty>` for the default package), while
      * `public` and `private` leave it empty. So `public` is: nothing in `privateWithin` and
      * neither `private` nor `protected` set.
      */
    def isPublic: Boolean =
      mdef.mods.privateWithin.isEmpty && !mdef.mods.isOneOf(Flags.Private | Flags.Protected)
  }

  private def publicRootTypeName(stats: List[untpd.Tree]): Option[String] =
    stats.collectFirst {
      case mdef: ModuleDef if mdef.isPublic => mdef.name.toString
    }

  /** The name of the first public top-level type declared in the source, if any. */
  def parseRootPublicClassName(byteContent: Array[Byte]): Option[String] =
    parseOutline(byteContent) match {
      case Outline.Types(stats) => publicRootTypeName(stats)
      case Outline.Compact      => None
    }

  /** The class name `javac` would produce a class file for: the first public top-level type, or for
    * a Java 21+ compact source file (top-level `main` & co, JEP 512) the source file name stem.
    */
  def rootClassName(byteContent: Array[Byte], sourceFileName: String): Option[String] =
    parseOutline(byteContent) match {
      case Outline.Types(stats) => publicRootTypeName(stats)
      case Outline.Compact      => Some(sourceFileName.stripSuffix(".java"))
    }
}
