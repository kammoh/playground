// import Mill dependency
import mill._
import mill.scalalib._
import mill.scalalib.publish._
import mill.scalalib.scalafmt._

// support bloop
import $ivy.`com.lihaoyi::mill-contrib-bloop:$MILL_VERSION`

// support BSP
import mill.bsp._

// VCS version
import $ivy.`de.tototec::de.tobiasroeser.mill.vcs.version::0.4.0`

// input build.sc from each repositories.
import $file.dependencies.cde.{build => cdeBuild}
import $file.dependencies.`berkeley-hardfloat`.{common => hardfloatBuild}
import $file.dependencies.`rocket-chip`.{common => rocketchipBuild}

// Global Scala Version
object ivys {
  val sv = "2.13.12"

  val ivyVersions = collection.mutable.HashMap(
    "org.chipsalliance::chisel" -> sys.env.getOrElse("CHISEL_VERSION", "6.0.0-RC1+9-0d91631e-SNAPSHOT"),
    "edu.berkeley.cs::chiseltest" -> sys.env.getOrElse("CHISELTEST_VERSION", "6.0-SNAPSHOT"),
    "org.scalatest::scalatest" -> "3.2.17",
    "org.scalacheck::scalacheck" -> "1.17.0",
    "org.scalatestplus::scalacheck-1-17" -> "3.2.17.0",
    "com.lihaoyi::mainargs" -> "0.5.4",
    "com.outr::scribe" -> "3.13.0",
    "com.github.jnr:jnr-ffi" -> "2.2.15",
    "com.lihaoyi::pprint" -> "0.8.1",
    "com.lihaoyi::os-lib" -> "0.9.2",
    "org.json4s::json4s-jackson" -> "4.0.7",
    "org.scala-lang.modules::scala-parallel-collections" -> "1.0.4",
    "com.lihaoyi::utest" -> "0.8.2",
    "com.chuusai::shapeless" -> "2.4.0-M1",
    // depJava:
    "org.scala-lang:scala-reflect" -> sv,
  )
  // 2-step initialization: dependant artifacts
  ivyVersions ++= Seq(
    "org.chipsalliance::chisel-plugin" -> getIvyVersion("chisel"),
    "edu.berkeley.cs::firrtl2" -> getIvyVersion("chiseltest")
  )

  val breeze = ivy"org.scalanlp::breeze:2.1.0"

  def getIvyVersionEntry(name: String): (String, String) =
    ivyVersions.get(name) match {
      case Some(v) => (name, v)
      case None =>
        val r = ivyVersions.collectFirst { case (k, v) if k.endsWith(":" + name) => (k, v) }
        // println(s"getIvyVersionEntry: $name -> $r")
        r.getOrElse(("", ""))
    }

  def getIvyVersion(name: String): String = getIvyVersionEntry(name)._2

  def dep(name: String): Dep = dep(name, -1)
  def dep(name: String, c: Int): Dep = {
    // println(s">>> dep: ${name} (c=${c})")
    val sp = name.split(":")
    val colons = sp.count(_.nonEmpty)
    if (sp.length == 1) {
      val (key, version) = getIvyVersionEntry(name)
      if (c > 0) {
        val ksp = key.split(":")
        require(ksp.length >= 2)
        dep(ksp.head, ksp.last, c)
      } else
        ivy"$key:${version}"
    } else {
      dep(sp.head, sp.last, colons)
    }
  }
  def dep(org: String, name: String, c: Int = 2): Dep = {
    val version = ivyVersions
      .getOrElse(
        name,
        ivyVersions
          .getOrElse(org + ":" + name, ivyVersions(org + "::" + name))
      )
    ivy"$org${":" * (c - 1)}:${name}:${version}"
  }
  def depJava(org:    String, name: String): Dep = dep(org, name, 1)
  def depJava(name:   String): Dep = dep(name, 1)
  def depPlugin(org:  String, name: String): Dep = dep(org, name, 3)
  def depPlugin(name: String): Dep = dep(name, 3)

}

import ivys.{dep, depJava, depPlugin}

// For modules not support mill yet, need to have a ScalaModule depend on our own repositories.
trait CommonModule extends ScalaModule {

  override def scalaVersion = ivys.sv

  override def ivyDeps = Agg(
    dep("chisel")
  )

  override def scalacPluginIvyDeps = Agg(
    depPlugin("chisel-plugin")
  )

  override def scalacOptions = Seq(
    // checks
    "-deprecation",
    "-feature",
    "-Xcheckinit",
    // warnings
    "-Wunused",
    "-Wconf:cat=unused:info",
    "-Xlint:adapted-args",
    // very noisy:
    // "-Xlint",
    // chisel:
    "-Ymacro-annotations",
    "-language:reflectiveCalls",
    "-Wconf:cat=unused&msg=parameter _.* in method .* is never used:s",
    "-Wconf:cat=deprecation&msg=Importing from firrtl is deprecated:s",
    "-Wconf:cat=deprecation&msg=will not be supported as part of the migration to the MLIR-based FIRRTL Compiler:s"
  )

  override def repositoriesTask = T.task {
    import coursier.maven.MavenRepository

    super.repositoriesTask() ++ Seq( //
      MavenRepository("https://oss.sonatype.org/content/repositories/releases"),
      MavenRepository("https://oss.sonatype.org/content/repositories/snapshots"),
      MavenRepository("https://s01.oss.sonatype.org/content/repositories/releases"),
      MavenRepository("https://s01.oss.sonatype.org/content/repositories/snapshots"),
      MavenRepository("https://jitpack.io"),
      MavenRepository("file:///Users/kamyar/.m2/repository")
    )
  }

}

object mycde extends cdeBuild.CDE with PublishModule {
  override def millSourcePath = os.pwd / "dependencies" / "cde" / "cde"

  override def scalaVersion = ivys.sv
}

object rocketchipMacros extends rocketchipBuild.MacrosModule with CommonModule with SbtModule {
  override def millSourcePath = os.pwd / "dependencies" / "rocket-chip" / "macros"

  override def scalaVersion = ivys.sv

  override def scalaReflectIvy = ivy"org.scala-lang:scala-reflect:${ivys.sv}" // depJava("scala-reflect")
}

object myrocketchip extends rocketchipBuild.RocketChipModule with CommonModule with SbtModule {

  override def scalaVersion = ivys.sv

  override def ivyDeps = Agg(
    dep("chisel"),
    dep("pprint"),
    dep("mainargs"),
    dep("json4s-jackson")
  )

  override def scalacPluginIvyDeps = Agg(
    depPlugin("chisel-plugin")
  )

  override def millSourcePath = os.pwd / "dependencies" / "rocket-chip"

  def chiselIvy = Some(dep("chisel"))

  def chiselPluginIvy = Some(depPlugin("chisel-plugin"))

  def chiselModule = None

  def chiselPluginJar = None

  def json4sJacksonIvy = dep("json4s-jackson")

  def mainargsIvy = dep("mainargs")

  def hardfloatModule = myhardfloat

  def cdeModule = mycde

  def macrosModule = rocketchipMacros

  def moduleDeps = super.moduleDeps ++ Seq(
    mycde,
    rocketchipMacros,
    myhardfloat
  )

}

object inclusivecache extends CommonModule {
  override def millSourcePath =
    os.pwd / "dependencies" / "rocket-chip-inclusive-cache" / 'design / 'craft / "inclusivecache"
  override def moduleDeps = super.moduleDeps ++ Seq(myrocketchip)
}

object blocks extends CommonModule with SbtModule {
  override def millSourcePath = os.pwd / "dependencies" / "rocket-chip-blocks"
  override def moduleDeps = super.moduleDeps ++ Seq(myrocketchip)

  override def ivyDeps = Agg(
    dep("chisel")
  )

  override def scalacPluginIvyDeps = Agg(
    depPlugin("chisel-plugin")
  )
}

object shells extends CommonModule with SbtModule {
  override def millSourcePath = os.pwd / "dependencies" / "rocket-chip-fpga-shells"
  override def moduleDeps = super.moduleDeps ++ Seq(myrocketchip, blocks)

  override def ivyDeps = Agg(
    dep("chisel")
  )

  override def scalacPluginIvyDeps = Agg(
    depPlugin("chisel-plugin")
  )
}

// UCB
object myhardfloat extends ScalaModule with CommonModule with SbtModule with hardfloatBuild.HardfloatModule {
  override def millSourcePath = os.pwd / "dependencies" / "berkeley-hardfloat" / "hardfloat"

  def crossValue: String = ivys.sv

  // remove test dep
  override def allSourceFiles = T(
    super.allSourceFiles().filterNot(_.path.last.contains("Tester")).filterNot(_.path.segments.contains("test"))
  )

  def chiselIvy = Some(dep("chisel"))

  def chiselPluginIvy = Some(depPlugin("chisel-plugin"))

  def chiselModule = None

  def chiselPluginJar = None

  override def ivyDeps = Agg(
    dep("chisel")
  )

  override def scalacPluginIvyDeps = Agg(
    depPlugin("chisel-plugin")
  )
}

object playground extends CommonModule {
  override def moduleDeps = super.moduleDeps ++ Seq(myrocketchip, inclusivecache, blocks, shells)

  // add some scala ivy module you like here.
  override def ivyDeps = super.ivyDeps() ++ Agg(
    dep("os-lib"),
    dep("pprint"),
    dep("mainargs")
  )

  def lazymodule: String = "freechips.rocketchip.system.ExampleRocketSystem"

  def configs: String = "playground.PlaygroundConfig"

  def elaborate = T {
    mill.util.Jvm.runSubprocess(
      finalMainClass(),
      runClasspath().map(_.path),
      forkArgs(),
      forkEnv(),
      Seq(
        "--dir",
        T.dest.toString,
        "--lm",
        lazymodule,
        "--configs",
        configs
      ),
      workingDir = os.pwd
    )
    PathRef(T.dest)
  }

  def verilog = T {
    os.proc(
      "firtool",
      elaborate().path / s"${lazymodule.split('.').last}.fir",
      "--disable-annotation-unknown",
      "-O=debug",
      "--split-verilog",
      "--preserve-values=named",
      "--output-annotation-file=mfc.anno.json",
      s"-o=${T.dest}"
    ).call(T.dest)
    PathRef(T.dest)
  }

}

object sanitytests extends CommonModule {
  override def scalaVersion = ivys.sv

  override def ivyDeps = super.ivyDeps() ++ Agg(
    dep("utest")
    // dep("firrtl2")
  )

  object rocketchip extends ScalaTests with CommonModule with TestModule.Utest {
    override def ivyDeps = Agg(
      dep("utest")
      // dep("firrtl2")
    )
    override def moduleDeps = super.moduleDeps ++ Seq(myrocketchip)

    def libraryResources = T {
      val x86Dir = T.ctx().dest
      os.proc("make", s"DESTDIR=${x86Dir}", "install").call(spike.compile())
      PathRef(T.ctx().dest)
    }
    override def resources = T.sources {
      super.resources() :+ libraryResources()
    }
  }

  object vcu118 extends ScalaTests with CommonModule with TestModule.Utest {
    override def ivyDeps = Agg(
      dep("utest"),
      dep("firrtl2")
    )
    override def moduleDeps = super.moduleDeps ++ Seq(myrocketchip, shells)
  }

  def moduleDeps = Seq(myrocketchip, shells)
}

object spike extends Module {
  override def millSourcePath = os.pwd / "dependencies" / "riscv-isa-sim"
  // ask make to cache file.
  def compile = T.persistent {
    os.proc(
      millSourcePath / "configure",
      "--prefix",
      "/usr",
      "--without-boost",
      "--without-boost-asio",
      "--without-boost-regex"
    ).call(
      T.ctx().dest,
      Map(
        "CC" -> "clang",
        "CXX" -> "clang++",
        "AR" -> "llvm-ar",
        "RANLIB" -> "llvm-ranlib",
        "LD" -> "lld"
      )
    )
    os.proc("make", "-j", Runtime.getRuntime().availableProcessors()).call(T.ctx().dest)
    T.ctx().dest
  }
}

object dromajo extends Module {
  override def millSourcePath = os.pwd / "dependencies" / "dromajo"

  // ask make to cache file.
  def compile = T.persistent {
    os.proc(
      "cmake",
      "-DCMAKE_BUILD_TYPE=Release",
      "-B",
      T.ctx().dest
    ).call(
      cwd = millSourcePath
    )
    os.proc("cmake", "--build", T.ctx().dest, "-j").call(cwd = millSourcePath)
    T.ctx().dest
  }
}
