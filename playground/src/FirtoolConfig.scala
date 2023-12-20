package playground

import logger.LazyLogging

case class FirtoolConfig(
  splitVerilog:               Boolean = true,
  dumpFir:                    Boolean = true,
  outputDir:                  Option[os.FilePath] = None,
  outputAnnotationFile:       Option[os.FilePath] = None,
  disableUnknownAnnotations:  Boolean = true,
  verbose:                    Int = 0,
  debug:                      Boolean = false,
  preserveNames:              Boolean = false,
  noRand:                     Boolean = false,
  sourceLocaters:             Boolean = true,
  disallowPackedArrays:       Boolean = false,
  disallowMuxInlining:        Boolean = false,
  disallowLocalVariables:     Boolean = true,
  omitVersionComment:         Boolean = false,
  warnUnprocessedAnnotations: Boolean = true,
  emittedLineLength:          Int = 160,
  preserveAggregates:         String = "1d-vec", // none, 1d-vec, vec, all,
  extraLoweringOptions:       Seq[String] = Seq.empty,
  additionalChiselOptions:    Seq[String] = Seq.empty,
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
      SeqIf(firtoolBinPath)(_ => "--firtool-binary-path", p => p) ++
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
      ) ++ SeqIf(outputAnnotationFile)( //
        f => s"--output-annotation-file=$f",
      ) ++ SeqIf(warnUnprocessedAnnotations)(
        "--warn-on-unprocessed-annotations"
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
      ) ++ SeqIf(outputDir)(dir => s"-o=$dir") ++ otherOptions
  }
}
