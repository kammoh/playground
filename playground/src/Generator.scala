package playground

import chisel3.RawModule
import chisel3.stage.{
  ChiselCircuitAnnotation,
  ChiselGeneratorAnnotation,
  PrintFullStackTraceAnnotation,
  ThrowOnFirstErrorAnnotation
}
import circt.stage.{ChiselStage, FirtoolOption}
import firrtl.options.TargetDirAnnotation
import firrtl.stage.{AllowUnrecognizedAnnotations, FirrtlCircuitAnnotation}
import logger.LazyLogging
import org.chipsalliance.cde.config.{Config, Parameters}
import os.Path

import scala.annotation.nowarn

object Generator extends LazyLogging {
  def resource(file: String): Path =
    Path(java.nio.file.Paths.get(this.getClass.getClassLoader.getResource(file).toURI))

  def generateSystem[M <: RawModule](
    outputDirectory: Path,
    testHarness:     Class[M],
    configs:         Seq[Class[_ <: Config]]): (String, Seq[Path], Seq[Path]) = {

    val params: Parameters = configs
      .map(InstantiateClass.fromClassAndArgs[Config](_))
      .reduce((c1: Parameters, c2: Parameters) => c1 ++ c2)

    val configName = configs.map(_.getName).mkString("_")

    val outputAnnotationFile = outputDirectory / s"${testHarness.getSimpleName}.anno.json"

    val config = FirtoolConfig(outputDir = Some(outputDirectory), outputAnnotationFile = Some(outputAnnotationFile))

    val (topName, duts) = generateVerilog(
      config,
      testHarness,
      Seq(params),
    )

    logger.warn(s"${testHarness.getSimpleName} with configs: $configName generated.")

    val artifactsBasename = s"${topName}_$configName"

    val artifacts = freechips.rocketchip.util.ElaborationArtefacts.files.zipWithIndex .map { case ((ext, contentGen), i) =>
      val path = outputDirectory / s"$artifactsBasename.$ext"
      logger.warn(s" --- Generating ${path.last}")
      os.write.over(path, contentGen())
      path
    }

    (topName, duts, artifacts)

  }

  def generateVerilog[M <: RawModule](
    config:      FirtoolConfig,
    testHarness: Class[M],
    params:      Seq[Any]): (String, Seq[Path]) = {

    val outputDirectory: Path = config.outputDir.getOrElse(os.FilePath("gen_rtl")).resolveFrom(os.pwd)

    if (!os.exists(outputDirectory)) {
      os.makeDir.all(outputDirectory)
    }

    val filelist = outputDirectory / "filelist.f"

    if (config.splitVerilog && os.exists(filelist))
      os.remove(filelist)

    val annos = Seq(
      ChiselGeneratorAnnotation(() => InstantiateClass.fromClassAndArgs(testHarness, params: _*)),
      TargetDirAnnotation(outputDirectory.toString),
      ThrowOnFirstErrorAnnotation,
      PrintFullStackTraceAnnotation,
      AllowUnrecognizedAnnotations,
//      OutputAnnotationFileAnnotation(outputAnnotationFile.toString)
    ) ++
      config.firtoolOptions.map(FirtoolOption)

    val annotations = (new ChiselStage).execute(config.chiselOptions, annos)

    @nowarn("msg=class Circuit in package firrtl is deprecated.*")
    val circuitNames = annotations.collect {
      case ChiselCircuitAnnotation(circuit) => circuit.name
      case FirrtlCircuitAnnotation(circuit) => circuit.main
    }.distinct

    logger.info(s"circuitNames: $circuitNames")
    assert(circuitNames.nonEmpty)

    val topName = circuitNames.head

    val duts = {
      if (config.splitVerilog)
        os.read.lines(filelist).map(p => outputDirectory / os.RelPath(p))
      else {
        val outputSuffix = ".sv"
        Seq(outputDirectory / s"$topName.$outputSuffix")
      }
    }

    logger.info(s"duts: $duts")
    assert(duts.nonEmpty, "no DUT generated")

    os.write(
      outputDirectory / s"$topName.other.anno.json",
      firrtl.annotations.JsonProtocol.serialize(annotations.filter {
        case _: ChiselCircuitAnnotation => false
        case _: FirrtlCircuitAnnotation => true
        case _ => true
      })
    )

    (topName, duts)
  }

  def genEmulator[M <: RawModule](
    testHarness: Class[M],
    configs:     Seq[Class[_ <: Config]],
    targetDir:   Option[Path] = None): Path = {
    val outputDirectory: Path = targetDir.getOrElse(os.temp.dir(deleteOnExit = false))
    logger.warn(s"start to compile emulator in $outputDirectory")

    //       InferReadWriteAnnotation,
    //       GenVerilogMemBehaviorModelAnno(false),

    val (_, duts, artifacts) = Generator.generateSystem(outputDirectory, testHarness, configs)

    val blackbox =
      os.read
        .lines(outputDirectory / firrtl.transforms.BlackBoxSourceHelper.defaultFileListName)
        .map(p => outputDirectory / p)

    val verilatorBuildDir = outputDirectory / "build"

    val cmakefilelist = outputDirectory / "CMakeLists.txt"

    if (!os.exists(verilatorBuildDir)) {
      os.makeDir.all(verilatorBuildDir)
    }

    val optCxxFlags = Seq(
      "-O3",
      "-march=native",
      "-mtune=native",
      "-g0",
    )

    val cxxFlags = Seq(
      "-std=c++17",
    ) ++ optCxxFlags

    val verilatorCFlags = cxxFlags

    val verilatorArgs = Seq(
        // format: off
        "-Wno-fatal",
        "-Wno-style",
        "-Wno-STMTDLY",
        "-Wno-UNOPTTHREADS",
        "-O3",
        s"-CFLAGS \"${verilatorCFlags.mkString(" ")}\"",
        "--x-assign unique",
        """+define+PRINTF_COND=\$c\(\"verbose\",\"&&\",\"done_reset\"\)""",
        """+define+STOP_COND=\$c\(\"done_reset\"\)""",
        "+define+RANDOMIZE_GARBAGE_ASSIGN",
        "--output-split 200000",
        "--output-split-cfuncs 20000",
        "--max-num-width 1048576"
        // format: on
    )

    val csrcs = Seq("csrc/emulator.cc", "csrc/SimDTM.cc", "csrc/SimJTAG.cc", "csrc/remote_bitbang.cc")
      .map(resource)

    val vsrcs = (duts ++ blackbox).distinct
      .filter(f => f.ext == "v" | f.ext == "sv")

    val plusArg = artifacts.collectFirst { case p if p.ext == "plusArgs" => p }

    val cxxIncludes = Seq(
      resource("csrc/verilator.h"),
      "VTestHarness.h",
    ) ++ plusArg

    val cmakeCxxFlags = cxxFlags ++ Seq(
      "-DVERILATOR",
      "-DTEST_HARNESS=VTestHarness",
    ) ++ cxxIncludes.flatMap(s => Seq("-include", s.toString))

    os.write(
      cmakefilelist,
        // format: off
        s"""cmake_minimum_required(VERSION 3.20)
           |project(emulator)
           |include_directories(${resource("usr/include")})
           |link_directories(${resource("usr/lib")})
           |find_package(verilator)
           |add_executable(emulator ${csrcs.mkString(" ")})
           |set(CMAKE_C_COMPILER clang)
           |set(CMAKE_CXX_COMPILER clang++)
           |set(CMAKE_CXX_STANDARD 17)
           |set(CMAKE_BUILD_TYPE Release)
           |target_link_libraries(emulator PRIVATE $${CMAKE_THREAD_LIBS_INIT})
           |target_link_libraries(emulator PRIVATE fesvr)
           |set(CMAKE_CXX_FLAGS "$${CMAKE_CXX_FLAGS} ${cmakeCxxFlags.mkString(" ")}")
           |verilate(emulator
           |  SOURCES ${vsrcs.mkString(" ")}
           |  TOP_MODULE TestHarness
           |  PREFIX VTestHarness
           |  VERILATOR_ARGS ${verilatorArgs.mkString(" ")}
           |)
           |set(CMAKE_CXX_FLAGS "$${CMAKE_CXX_FLAGS} -std=c++17")
           |""".stripMargin
        // format: on
    )
    logger.warn(s"compiling DUT with CMake:\n")
    logger.warn(s"start to compile Verilog to C++")
    os.proc(
      "cmake",
      "-G",
      "Ninja",
      "-B",
      verilatorBuildDir,
      "-DCMAKE_BUILD_TYPE=Release",
      "-DCMAKE_CXX_STANDARD=17",
    ).call(outputDirectory)
    logger.warn(s"start to compile C++ to emulator")
    os.proc(
      "cmake",
      "--build",
      verilatorBuildDir,
      "-j"
    ).call(outputDirectory)
    val emulatorBinary = verilatorBuildDir / "emulator"
    logger.warn(s"emulator location: $emulatorBinary")
    emulatorBinary
  }
}

//import firrtl.AnnotationSeq
//import firrtl.options.{Dependency, Phase}
//
//class FilterUnhandled extends Phase {
//
//  override def prerequisites = Seq(Dependency[chisel3.stage.phases.Elaborate])
//
//  override def optionalPrerequisites = Seq.empty
//
//  override def optionalPrerequisiteOf = Seq.empty
//
//  override def invalidates(a: Phase) = false
//
//  def transform(annotations: AnnotationSeq): AnnotationSeq = annotations.flatMap {
//    case a: freechips.rocketchip.util.AddressMapAnnotation => None
//    case a: freechips.rocketchip.util.SRAMAnnotation => None
//    case a: freechips.rocketchip.util.ParamsAnnotation => None
//    case a: freechips.rocketchip.util.RegFieldDescMappingAnnotation => None
//    case a => Some(a)
//  }
//}
