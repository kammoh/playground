package sanitytests
package rocketchip

import os.Path
import org.chipsalliance.cde.config.Config

import chisel3.RawModule
import logger.LazyLogging

import playground.Generator


case class TestHarness[M <: RawModule](
  testHarness: Class[M],
  configs:     Seq[Class[_ <: Config]],
  targetDir:   Option[Path] = None)
    extends LazyLogging {

  /** compile [[testHarness]] with correspond [[configs]] to emulator. return emulator [[Path]].
    */
  lazy val emulator: Path = Generator.genEmulator(testHarness, configs, targetDir)
}
