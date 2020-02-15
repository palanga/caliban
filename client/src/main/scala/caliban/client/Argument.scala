package caliban.client

import scala.annotation.tailrec
import caliban.client.Value.NullValue

case class Argument[+A](name: String, value: A)(implicit encoder: ArgEncoder[A]) {
  def toGraphQL(
    useVariables: Boolean,
    variables: Map[String, (Value, String)]
  ): (String, Map[String, (Value, String)]) =
    encoder.encode(value) match {
      case NullValue => ("", variables)
      case v =>
        if (useVariables) {
          val variableName = Argument.generateVariableName(name, v, variables)
          (s"$name:$$$variableName", variables.updated(variableName, (v, encoder.formatTypeName)))
        } else {
          (s"$name:${v.toString}", variables)
        }
    }
}

object Argument {

  @tailrec
  def generateVariableName(
    name: String,
    value: Value,
    variables: Map[String, (Value, String)],
    index: Int = 0
  ): String = {
    val formattedName = if (index > 0) s"$name$index" else name
    variables.get(formattedName) match {
      case None                       => formattedName
      case Some((v, _)) if v == value => formattedName
      case Some(_) =>
        generateVariableName(name, value, variables, index + 1)
    }
  }

}