package midas
package widgets

trait CPPLiteral {
  def typeString: String
  def toC: String
}

trait IntLikeLiteral extends CPPLiteral {
  def bitWidth: Int
  def literalSuffix: String
  def value: BigInt
  def toC = value.toString + literalSuffix
}

case class UInt32(value: BigInt) extends IntLikeLiteral {
  def typeString = "unsigned int"
  def bitWidth = 32
  def literalSuffix = ""

  require(bitWidth >= value.bitLength)
}

case class UInt64(value: BigInt) extends IntLikeLiteral {
  def typeString = "uint64_t"
  def bitWidth = 64
  def literalSuffix = "L"

  require(bitWidth >= value.bitLength)
}

case class CStrLit(val value: String) extends CPPLiteral {
  def typeString = "const char* const"
  def toC = "\"%s\"".format(value)
}

object CppGenerationUtils {
  val indent = "  "

  def genEnum(name: String, values: Seq[String]): String =
    if (values.isEmpty) "" else s"enum $name {%s};\n".format(values mkString ",")

  def genArray[T <: CPPLiteral](name: String, values: Seq[T]): String =
    if (values.isEmpty) "" else {
      val prefix = s"static ${values.head.typeString} $name [${values.size}] = {\n"
      val body = values map (indent + _.toC) mkString ",\n"
      val suffix = "\n};\n"
      prefix + body + suffix
    }

  def genStatic[T <: CPPLiteral](name: String, value: T): String =
    "static %s %s = %s;\n".format(value.typeString, name, value.toC)

  def genConstStatic[T <: CPPLiteral](name: String, value: T): String =
    "const static %s %s = %s;\n".format(value.typeString, name, value.toC)

  def genMacro(name: String, value: String = ""): String = s"#define $name $value\n"

  def genMacro[T <: CPPLiteral](name: String, value: T): String =
    "#define %s %s\n".format(name, value.toC)

  def genComment(str: String): String = "// %s\n".format(str)

}


