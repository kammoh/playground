package sanitytests

import scala.language.implicitConversions

object utils {
  def resource(file: String): os.Path =
    os.Path(java.nio.file.Paths.get(getClass.getClassLoader.getResource(file).toURI))
}
