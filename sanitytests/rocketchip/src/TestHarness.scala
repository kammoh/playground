package sanitytests
package rocketchip

import firrtl.options.OutputAnnotationFileAnnotation
import firrtl.stage.FirrtlCircuitAnnotation

import os.Path

import org.chipsalliance.cde.config.{Config, Parameters}
import chisel3.RawModule
import chisel3.stage.{
  ChiselCircuitAnnotation,
  ChiselGeneratorAnnotation,
  ThrowOnFirstErrorAnnotation,
  PrintFullStackTraceAnnotation
}
import circt.stage.{ChiselStage, FirtoolOption}
import firrtl.stage.AllowUnrecognizedAnnotations
import firrtl.options.TargetDirAnnotation
import logger.LazyLogging

import scala.annotation.nowarn

case class TestHarness[M <: RawModule](
  testHarness: Class[M],
  configs:     Seq[Class[_ <: Config]],
  targetDir:   Option[Path] = None)
    extends LazyLogging {

  /** compile [[testHarness]] with correspond [[configs]] to emulator. return emulator [[Path]].
    */
  lazy val emulator: Path = {
    val outputDirectory: Path = targetDir.getOrElse(os.temp.dir(deleteOnExit = false))
    logger.warn(s"start to compile emulator in $outputDirectory")

    //       InferReadWriteAnnotation,
    //       GenVerilogMemBehaviorModelAnno(false),

    if (!os.exists(outputDirectory)) {
      os.makeDir.all(outputDirectory)
    }

    val outputAnnotationFile = outputDirectory / s"${testHarness.getSimpleName}.anno.json"

    val config = FirtoolConfig(outputDir = Some(outputDirectory), outputAnnotationFile = Some(outputAnnotationFile))

    val filelist = outputDirectory / "filelist.f"

    if (config.splitVerilog && os.exists(filelist))
      os.remove(filelist)

    val params: Parameters = configs
      .map(InstantiateClass.fromClassAndArgs[Config](_))
      .reduce((c1: Parameters, c2: Parameters) => c1 ++ c2)

    val annos = Seq(
      ChiselGeneratorAnnotation(() => InstantiateClass.fromClassAndArgs(testHarness, params)),
      TargetDirAnnotation(outputDirectory.toString),
      ThrowOnFirstErrorAnnotation,
      PrintFullStackTraceAnnotation,
      AllowUnrecognizedAnnotations,
      OutputAnnotationFileAnnotation(outputAnnotationFile.toString)
    ) ++
      config.firtoolOptions.map(FirtoolOption)

    val annotations = (new ChiselStage).execute(config.chiselOptions, annos)

    val configName = configs.map(_.getName).mkString("_")

    logger.warn(s"${testHarness.getSimpleName} with configs: $configName generated.")

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

    val artifactsBasename = s"${topName}_$configName"
    freechips.rocketchip.util.ElaborationArtefacts.files.foreach { case (ext, contentGen) =>
      logger.warn(s" --- Generating $artifactsBasename.$ext")
      os.write.over(outputDirectory / s"$artifactsBasename.$ext", contentGen())
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

    val blackbox =
      os.read
        .lines(outputDirectory / firrtl.transforms.BlackBoxSourceHelper.defaultFileListName)
        .map(p => outputDirectory / p)
    val verilatorBuildDir = outputDirectory / "build"
    val cmakefilelist = verilatorBuildDir / "CMakeLists.txt"
    os.makeDir(verilatorBuildDir)
    val verilatorArgs = Seq(
      // format: off
      "-Wno-fatal",
      "-Wno-style",
      "-Wno-STMTDLY",
      // "-Wno-LATCH",
      "-Wno-UNOPTTHREADS",
      "-O3",
      "-CFLAGS \"-std=c++17 -O3 -march=native -mtune=native\"",
      "--x-assign unique",
      """+define+PRINTF_COND=\$c\(\"verbose\",\"&&\",\"done_reset\"\)""",
      """+define+STOP_COND=\$c\(\"done_reset\"\)""",
      "+define+RANDOMIZE_GARBAGE_ASSIGN",
      "--output-split 20000",
      "--output-split-cfuncs 20000",
      "--max-num-width 1048576"
      // format: on
    ).mkString(" ")
    val csrcs = Seq("csrc/emulator.cc", "csrc/SimDTM.cc", "csrc/SimJTAG.cc", "csrc/remote_bitbang.cc")
      .map(sanitytests.utils.resource(_).toString)
      .mkString(" ")
    val vsrcs = (duts ++ blackbox ++ Seq(
      // os.pwd / os.RelPath("dependencies/rocket-chip/src/main/resources/vsrc/TestDriver.v")
    )).distinct
      .filter(f => f.ext == "v" | f.ext == "sv")
      .map(_.toString)
      .mkString(" ")

    os.write(
      cmakefilelist,
      // format: off
      s"""cmake_minimum_required(VERSION 3.20)
         |project(emulator)
         |include_directories(${sanitytests.utils.resource("usr/include")})
         |link_directories(${sanitytests.utils.resource("usr/lib")})
         |find_package(verilator)
         |add_executable(emulator $csrcs)
         |set(CMAKE_C_COMPILER clang)
         |set(CMAKE_CXX_COMPILER clang++)
         |set(CMAKE_CXX_STANDARD 17)
         |set(CMAKE_BUILD_TYPE Release)
         |target_link_libraries(emulator PRIVATE $${CMAKE_THREAD_LIBS_INIT})
         |target_link_libraries(emulator PRIVATE fesvr)
         |set(CMAKE_CXX_FLAGS "$${CMAKE_CXX_FLAGS} -O3 -march=native -mtune=native -DVERILATOR -DTEST_HARNESS=VTestHarness -std=c++17 -include ${sanitytests.utils.resource("csrc/verilator.h")} -include ${outputDirectory / s"$artifactsBasename.plusArgs"} -include VTestHarness.h")
         |verilate(emulator
         |  SOURCES $vsrcs
         |  TOP_MODULE TestHarness
         |  PREFIX VTestHarness
         |  VERILATOR_ARGS $verilatorArgs
         |)
         |set(CMAKE_CXX_FLAGS "$${CMAKE_CXX_FLAGS} -std=c++17")
         |""".stripMargin
      // format: on
    )
    logger.warn(s"compiling DUT with CMake:\n" + os.read(cmakefilelist))
    logger.warn(s"start to compile Verilog to C++")
    os.proc(
      // format: off
      "cmake",
      "-G", "Ninja",
      "-DCMAKE_CXX_STANDARD=17",
      verilatorBuildDir.toString
      // format: on
    ).call(verilatorBuildDir)
    logger.warn(s"start to compile C++ to emulator")
    os.proc(
      // format: off
      "ninja"
      // format: on
    ).call(verilatorBuildDir)
    val emulatorBinary = verilatorBuildDir / "emulator"
    logger.warn(s"emulator location: $emulatorBinary")
    emulatorBinary
  }
}
