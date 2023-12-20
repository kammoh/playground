package playground

import logger.LazyLogging

import java.lang.reflect.Constructor
import scala.reflect.ClassTag

object InstantiateClass extends LazyLogging {
  /// adapted from https://github.com/seldridge/reflective-builder
  def getTypes(params: Seq[?]): Seq[Class[_]] = params.map {
    /// Integer needs to stay as an Int and not become a java.lang.Integer
    case _: Int => classOf[Int]
    case _: Long => classOf[Long]
    case _: Boolean => classOf[Boolean]
    case a => a.getClass
  }

  def caseObjFromMap[T: ClassTag](m: Map[String, ?]): T = {
    val classTag = implicitly[ClassTag[T]]
    val constructor = classTag.runtimeClass.getDeclaredConstructors.head
    val classParams = constructor.getParameters
    val classArgNames = classParams.map(_.getName)
    val keysDiff = m.keySet.diff(classArgNames.toSet)
    if (keysDiff.nonEmpty) {
      throw new IllegalArgumentException(s"Excess arguments provided: ${keysDiff
          .mkString(", ")}\n Class ${classTag.getClass.getSimpleName} constructor args (${classParams.length}) are: ${classArgNames
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

    val constructors = clazz.getConstructors

    if (true) {
      logger.warn(s"${clazz.getSimpleName} has ${constructors.size} constructor(s):")
      for (constr <- constructors) {
        println(s"    $constr   (with ${constr.getParameters.length} params)")
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
      case _ => str
    }
  }

  def fromNameAndStringArgs[M](className: String, params: Seq[String]): M = {
    fromNameAndArgs[M](className, params.map(stringToAny): _*)
  }
}
