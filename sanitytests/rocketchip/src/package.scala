package sanitytests

import chisel3.stage.phases.Elaborate
import firrtl.AnnotationSeq
import firrtl.options.{Dependency, Phase}
import logger.LazyLogging
import os._
import sanitytests.utils.SeqIf

import scala.language.implicitConversions
import scala.reflect.ClassTag
import java.lang.reflect.Constructor

object utils {
  def resource(file: String): Path = Path(java.nio.file.Paths.get(getClass().getClassLoader().getResource(file).toURI))

  class SeqIf[A](ifSeq: => Seq[A], cond: => Boolean) {
    def ++(that: Seq[A]): Seq[A] = this.toSeq ++ that

    def Else(elseItems: A*): Seq[A] = {
      if (cond) ifSeq else elseItems
    }

    def toSeq = if (cond) ifSeq else Seq()
  }

  object SeqIf {
    implicit def ifSeqToSeq[A](ifSeq: SeqIf[A]): Seq[A] = ifSeq.toSeq

    def apply[A](cond: Boolean)(ifItems: A*): SeqIf[A] = {
      new SeqIf(ifItems, cond)
    }

//    def apply[A, B](opt: => Option[A])(item0: => B, itemMaps: PartialFunction[A, B]*): SeqIf[B] =
//      new SeqIf(item0 +: itemMaps.collect(f => f(opt.get)), opt.nonEmpty)

    def apply[A, B](opt: => Option[A])(itemMap0: PartialFunction[A, B], itemMaps: PartialFunction[A, B]*): SeqIf[B] =
      new SeqIf((itemMap0 +: itemMaps).collect(f => f(opt.get)), opt.nonEmpty)
  }
}

object InstantiateClass extends LazyLogging {
  /// adapted from https://github.com/seldridge/reflective-builder
  def getTypes(params: Seq[?]) = params.map {
    /// Integer needs to stay as an Int and not become a java.lang.Integer
    case _: Int => classOf[Int]
    case _: Long => classOf[Long]
    case _: Boolean => classOf[Boolean]
    case a => a.getClass()
  }

  def caseObjFromMap[T: ClassTag](m: Map[String, ?]): T = {
    val classTag = implicitly[ClassTag[T]]
    val constructor = classTag.runtimeClass.getDeclaredConstructors.head
    val classParams = constructor.getParameters()
    val classArgNames = classParams.map(_.getName)
    val keysDiff = m.keySet.diff(classArgNames.toSet)
    if (keysDiff.nonEmpty) {
      throw new IllegalArgumentException(s"Excess arguments provided: ${keysDiff
          .mkString(", ")}\n Class ${classTag.getClass().getSimpleName()} constructor args (${classParams.length}) are: ${classArgNames
          .mkString(", ")}")
    }
    val constructorArgs = classParams.map { param =>
      val paramName = param.getName
      if (param.getType == classOf[Option[_]])
        m.get(paramName)
      else
        m.getOrElse(paramName, throw new IllegalArgumentException(s"Missing required parameter: $paramName"))
    }
    constructor.newInstance(constructorArgs: _*).asInstanceOf[T]
  }

  def fromNameAndArgs[M](className: String, params: Any*): M = {
    val clazz = Class.forName(className).asInstanceOf[Class[M]]
    fromClassAndArgs[M](clazz, params: _*)
  }

  def fromClassAndArgs[M](clazz: Class[_ <: M], params: Any*): M = {

    val constructors = clazz.getConstructors()

    if (true) {
      logger.warn(s"${clazz.getSimpleName()} has ${constructors.size} constructor(s):")
      for (constr <- constructors) {
        println(s"    ${constr}   (with ${constr.getParameters().length} params)")
      }
    }

    val constr: Constructor[_] =
      if (constructors.length == 1)
        constructors.head
      else
        clazz.getConstructor(getTypes(params): _*)

    constr.newInstance(params: _*).asInstanceOf[M]
  }

  private val classPattern = "([a-zA-Z0-9_$.]+)\\((.*)\\)".r

  /// adopted from https://github.com/seldridge/reflective-builder
  def stringToAny(str: String): Any = {
    if (str.startsWith("\"") && str.endsWith("\"")) return str
    /* Something that looks like object creation, e.g., "Foo(42)" */

    str match {
      case boolean if boolean.toBooleanOption.isDefined => boolean.toBoolean
      case integer if integer.toIntOption.isDefined => integer.toInt
      case integer if integer.toLongOption.isDefined => integer.toLong
      case double if double.toDoubleOption.isDefined => double.toDouble
      case classPattern(a, b) =>
        val arguments = b.split(',')
        (a, arguments.length) match {
          case ("BigInt", 1) => BigInt(arguments.head)
          case _ =>
            println(s"classPattern match")
            Class.forName(a).getConstructors()(0).newInstance(arguments.map(stringToAny).toSeq: _*)
        }
      case string @ _ => str
    }
  }

  def fromNameAndStringArgs[M](className: String, params: Seq[String]): M = {
    fromNameAndArgs[M](className, params.map(stringToAny): _*)
  }
}

case class FirtoolConfig(
  splitVerilog:              Boolean = true,
  dumpFir:                   Boolean = true,
  outputDir:                 Option[FilePath],
  disableUnknownAnnotations: Boolean = true,
  verbose:                   Int = 0,
  debug:                     Boolean = false,
  preserveNames:             Boolean = false,
  noRand:                    Boolean = true,
  sourceLocaters:            Boolean = true,
  disallowPackedArrays:      Boolean = false,
  disallowMuxInlining:       Boolean = false,
  disallowLocalVariables:    Boolean = true,
  omitVersionComment:        Boolean = true,
  emittedLineLength:         Int = 120,
  preserveAggregates:        String = "none", // none, 1d-vec, vec, all,
  extraLoweringOptions:      Seq[String] = Seq.empty,
  additionalChiselOptions:   Seq[String] = Seq.empty,
  // (doc = "path to firtool binary")
  firtoolBinPath: Option[String] = None,
  // (doc = "replace firtool options with firtoolOptions instead of appending them to the default options")
  overrideFirtoolOptions: Boolean = false,
  otherOptions:           Seq[String] = Seq.empty)
    extends LazyLogging {
  val loweringOptions: Seq[String] = Seq(
    // "verifLabels",
    // "wireSpillingHeuristic=spillLargeTermsWithNamehints",
    // "wireSpillingNamehintTermLimit=4"
    // "emitReplicatedOpsToHeader",
    // "locationInfoStyle=plain", //
    // "disallowExpressionInliningInPorts", //
    // "explicitBitcast"
  ) ++ extraLoweringOptions ++
    Option.when(disallowLocalVariables)("disallowLocalVariables") ++
    Option.when(disallowPackedArrays)("disallowPackedArrays") ++ // for yosys
    Option.when(disallowMuxInlining)("disallowMuxInlining") ++
    Option.when(emittedLineLength > 0)(s"emittedLineLength=$emittedLineLength") ++
    Option.when(omitVersionComment)("omitVersionComment")

  def chiselOptions: Array[String] = {
    Array("--target", "systemverilog") ++
      SeqIf(splitVerilog)("--split-verilog") ++
      SeqIf(firtoolBinPath)((_) => "--firtool-binary-path", p => p) ++
      SeqIf(dumpFir)("--dump-fir") ++
      additionalChiselOptions
  }

  def firtoolOptions: Array[String] = {
    if (overrideFirtoolOptions)
      otherOptions.toArray
    else
      Array(
        s"""-O=${if (debug) "debug" else "release"}""",
        s"""--lowering-options=${loweringOptions.mkString(",")}""",
        "--add-vivado-ram-address-conflict-synthesis-bug-workaround",
        // s"--preserve-aggregate=${preserveAggregates}",
        s"""--preserve-values=${if (preserveNames) "named" else "none"}""",
        "--warn-on-unprocessed-annotations",
        // "--scalarize-top-module=false",
        // "--scalarize-ext-modules=false",
        "--export-module-hierarchy",
        // "--extract-test-code",
      ) ++ SeqIf(debug)(
        "--disable-opt",
        "--mlir-pretty-debuginfo",
      ) ++ SeqIf(splitVerilog)(
        "--split-verilog",
      ) ++ SeqIf(!sourceLocaters)(
        "--strip-fir-debug-info", // Disable source fir locator information in output Verilog
        "--strip-debug-info", // Disable source locator information in output Verilog
      ) ++ SeqIf(noRand)(
        "--disable-mem-randomization", // Disable emission of memory randomization code
        "--disable-reg-randomization", // Disable emission of register randomization code
        "--disable-all-randomization",
      ) ++ SeqIf(disableUnknownAnnotations)(
        "--disable-annotation-unknown",
      ) ++ SeqIf(outputDir)(dir => s"-o=${dir}") ++ otherOptions
  }
}

class FilterUnhandled extends Phase {

  override def prerequisites = Seq(Dependency[Elaborate])

  override def optionalPrerequisites = Seq.empty

  override def optionalPrerequisiteOf = Seq.empty

  override def invalidates(a: Phase) = false

  def transform(annotations: AnnotationSeq): AnnotationSeq = annotations.flatMap {
    case a: freechips.rocketchip.util.AddressMapAnnotation => None
    case a: freechips.rocketchip.util.SRAMAnnotation => None
    case a: freechips.rocketchip.util.ParamsAnnotation => None
    case a: freechips.rocketchip.util.RegFieldDescMappingAnnotation => None
    case a => Some(a)
  }
}
