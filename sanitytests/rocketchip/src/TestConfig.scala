package sanitytests.rocketchip

import org.chipsalliance.cde.config.Config
import freechips.rocketchip.devices.tilelink.BootROMLocated
import freechips.rocketchip.util.ClockGateModelFile
import os._

class TestConfig
    extends Config((_, _, up) => {
      case ClockGateModelFile => Some(sanitytests.utils.resource("vsrc/EICG_wrapper.v").toString)
      case BootROMLocated(x) =>
        up(BootROMLocated(x)).map(_.copy(contentFileName = {
          val tmp = os.temp.dir(deleteOnExit = false)
          val elf = tmp / "bootrom.elf"
          val bin = tmp / "bootrom.bin"
          val img = tmp / "bootrom.img"
          // format: off
          proc(
            "clang",
            "--target=riscv64", "-march=rv64gc",
            "-mno-relax",
            "-static",
            "-nostdlib",
            "-Wl,--no-gc-sections",
            "-fuse-ld=lld", s"-T${sanitytests.utils.resource("linker.ld")}",
            s"${sanitytests.utils.resource("bootrom.S")}",
            "-o", elf
          ).call()
          proc(
            "llvm-objcopy",
            "-O", "binary",
            elf,
            bin
          ).call()
          proc(
            "dd",
            s"if=$bin",
            s"of=$img",
            "bs=128",
            "count=1"
          ).call()
          // format: on
          img.toString()
        }))
    })
