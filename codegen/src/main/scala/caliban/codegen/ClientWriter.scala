package caliban.codegen

import scala.annotation.tailrec
import caliban.parsing.adt.Definition.TypeSystemDefinition.TypeDefinition
import caliban.parsing.adt.Definition.TypeSystemDefinition.TypeDefinition._
import caliban.parsing.adt.Type.{ ListType, NamedType }
import caliban.parsing.adt.{ Document, Type }

object ClientWriter {

  def write(schema: Document, objectName: String = "Client", packageName: Option[String] = None): String = {
    val schemaDef = Document.schemaDefinitions(schema).headOption

    val typesMap: Map[String, TypeDefinition] = schema.definitions.collect {
      case op @ ObjectTypeDefinition(_, name, _, _, _)   => name -> op
      case op @ InputObjectTypeDefinition(_, name, _, _) => name -> op
      case op @ EnumTypeDefinition(_, name, _, _)        => name -> op
      case op @ UnionTypeDefinition(_, name, _, _)       => name -> op
      case op @ ScalarTypeDefinition(_, name, _)         => name -> op
      case op @ InterfaceTypeDefinition(_, name, _, _)   => name -> op
    }.toMap

    val unionTypes = Document
      .unionTypeDefinitions(schema)
      .map(union => (union, union.memberTypes.flatMap(Document.objectTypeDefinition(schema, _))))
      .toMap

    val unions = unionTypes.map { case (union, objects) => writeUnion(union, objects, typesMap) }.mkString("\n")

    val objects = Document
      .objectTypeDefinitions(schema)
      .filterNot(
        obj =>
          reservedType(obj) ||
            unionTypes.values.flatten.exists(_.name == obj.name) ||
            schemaDef.exists(_.query.getOrElse("Query") == obj.name) ||
            schemaDef.exists(_.mutation.getOrElse("Mutation") == obj.name) ||
            schemaDef.exists(_.subscription.getOrElse("Subscription") == obj.name)
      )
      .map(writeObject(_, typesMap))
      .mkString("\n")

    val inputs = Document.inputObjectTypeDefinitions(schema).map(writeInputObject).mkString("\n")

    val interfaces = Document.interfaceTypeDefinitions(schema).map(writeInterface(_, typesMap)).mkString("\n")

    val enums = Document.enumTypeDefinitions(schema).map(writeEnum).mkString("\n")

    val queries = Document
      .objectTypeDefinition(schema, schemaDef.flatMap(_.query).getOrElse("Query"))
      .map(t => writeRootQuery(t, typesMap))
      .getOrElse("")

    val mutations = Document
      .objectTypeDefinition(schema, schemaDef.flatMap(_.mutation).getOrElse("Mutation"))
      .map(t => writeRootMutation(t, typesMap))
      .getOrElse("")

    val imports = s"""${if (enums.nonEmpty)
      """import caliban.client.CalibanClientError.DecodingError
        |""".stripMargin
    else ""}${if (objects.nonEmpty || interfaces.nonEmpty || unions.nonEmpty || queries.nonEmpty || mutations.nonEmpty)
      """import caliban.client.FieldBuilder._
        |import caliban.client.SelectionBuilder._
        |""".stripMargin
    else
      ""}${if (enums.nonEmpty || objects.nonEmpty || interfaces.nonEmpty || unions.nonEmpty || queries.nonEmpty || mutations.nonEmpty || inputs.nonEmpty)
      """import caliban.client._
        |""".stripMargin
    else ""}${if (queries.nonEmpty || mutations.nonEmpty)
      """import caliban.client.Operations._
        |""".stripMargin
    else ""}${if (enums.nonEmpty || inputs.nonEmpty)
      """import caliban.client.Value._
        |""".stripMargin
    else ""}${unionTypes.keys.map(t => s"import $objectName.${t.name}._").mkString("\n")}"""

    s"""${packageName.fold("")(p => s"package $p\n\n")}$imports
       |
       |object $objectName {
       |
       |  $enums
       |  $unions
       |  $objects
       |  $inputs
       |  $interfaces
       |  $queries
       |  $mutations
       |  
       |}""".stripMargin
  }

  def reservedType(typeDefinition: ObjectTypeDefinition): Boolean =
    typeDefinition.name == "Query" || typeDefinition.name == "Mutation" || typeDefinition.name == "Subscription"

  def writeRootQuery(typedef: ObjectTypeDefinition, typesMap: Map[String, TypeDefinition]): String =
    s"""object ${typedef.name} {
       |  ${typedef.fields.map(writeField(_, "RootQuery", typesMap)).mkString("\n  ")}
       |}
       |""".stripMargin

  def writeRootMutation(typedef: ObjectTypeDefinition, typesMap: Map[String, TypeDefinition]): String =
    s"""object ${typedef.name} {
       |  ${typedef.fields.map(writeField(_, "RootMutation", typesMap)).mkString("\n  ")}
       |}
       |""".stripMargin

  def writeObject(typedef: ObjectTypeDefinition, typesMap: Map[String, TypeDefinition]): String =
    s"""type ${typedef.name}
       |object ${typedef.name} {
       |  ${typedef.fields.map(writeField(_, typedef.name, typesMap)).mkString("\n  ")}
       |}
       |""".stripMargin

  def writeInterface(typedef: InterfaceTypeDefinition, typesMap: Map[String, TypeDefinition]): String =
    s"""type ${typedef.name}
       |object ${typedef.name} {
       |  ${typedef.fields.map(writeField(_, typedef.name, typesMap)).mkString("\n  ")}
       |}
       |""".stripMargin

  def writeInputObject(typedef: InputObjectTypeDefinition): String =
    s"""case class ${typedef.name}(${writeArgumentFields(typedef.fields)})
       |object ${typedef.name} {
       |  implicit val encoder: ArgEncoder[${typedef.name}] = new ArgEncoder[${typedef.name}] {
       |    override def encode(value: ${typedef.name}): Value =
       |      ObjectValue(List(${typedef.fields
         .map(f => s""""${f.name}" -> ${writeInputValue(f.ofType, s"value.${f.name}")}""")
         .mkString(", ")}))
       |    override def typeName: String = "${typedef.name}"
       |  }
       |}""".stripMargin

  def writeInputValue(t: Type, fieldName: String): String =
    t match {
      case NamedType(name, true) => s"implicitly[ArgEncoder[$name]].encode($fieldName)"
      case NamedType(name, false) =>
        s"$fieldName.fold(NullValue)(value => ${writeInputValue(NamedType(name, nonNull = true), "value")})"
      case ListType(ofType, true) => s"ListValue($fieldName.map(value => ${writeInputValue(ofType, "value")}))"
      case ListType(ofType, false) =>
        s"$fieldName.fold(NullValue)(value => ${writeInputValue(ListType(ofType, nonNull = true), "value")})"
    }

  def writeEnum(typedef: EnumTypeDefinition): String =
    s"""sealed trait ${typedef.name} extends Product with Serializable
        object ${typedef.name} {
          ${typedef.enumValuesDefinition
      .map(v => s"case object ${v.enumValue} extends ${typedef.name}")
      .mkString("\n")}
      
          implicit val decoder: ScalarDecoder[${typedef.name}] = {
            ${typedef.enumValuesDefinition
      .map(v => s"""case StringValue ("${v.enumValue}") => Right(${typedef.name}.${v.enumValue})""")
      .mkString("\n")}
            case other => Left(DecodingError(s"Can't build ${typedef.name} from input $$other"))
          }
          implicit val encoder: ArgEncoder[${typedef.name}] = new ArgEncoder[${typedef.name}] {
            override def encode(value: ${typedef.name}): Value = value match {
              ${typedef.enumValuesDefinition
      .map(v => s"""case ${typedef.name}.${v.enumValue} => StringValue("${v.enumValue}")""")
      .mkString("\n")}
            }
            override def typeName: String = "${typedef.name}"
          }
        }
       """

  def writeUnion(
    typedef: UnionTypeDefinition,
    objects: List[ObjectTypeDefinition],
    typesMap: Map[String, TypeDefinition]
  ): String =
    s"""object ${typedef.name} {
       |  ${objects.map(writeObject(_, typesMap)).mkString("")}}
       |""".stripMargin

  def writeField(field: FieldDefinition, typeName: String, typesMap: Map[String, TypeDefinition]): String = {
    val name      = if (reservedKeywords.contains(field.name)) s"`${field.name}`" else field.name
    val fieldType = getTypeName(field.ofType)
    val isScalar = typesMap
      .get(fieldType)
      .collect {
        case _: ScalarTypeDefinition => true
        case _: EnumTypeDefinition   => true
        case _                       => false
      }
      .getOrElse(true)
    val unionTypes = typesMap
      .get(fieldType)
      .collect {
        case UnionTypeDefinition(_, _, _, memberTypes) => memberTypes.flatMap(typesMap.get)
      }
      .getOrElse(Nil)
      .collect {
        case o: ObjectTypeDefinition => o
      }
    val interfaceTypes = typesMap
      .get(fieldType)
      .collect {
        case InterfaceTypeDefinition(_, name, _, _) => name
      }
      .map(
        interface =>
          typesMap.values.collect {
            case o @ ObjectTypeDefinition(_, _, implements, _, _) if implements.exists(_.name == interface) => o
          }
      )
      .getOrElse(Nil)
    val (typeParam, innerSelection, outputType, builder) =
      if (isScalar) {
        (
          "",
          "",
          writeType(field.ofType),
          writeTypeBuilder(field.ofType, "Scalar()")
        )
      } else if (unionTypes.nonEmpty) {
        (
          "[A]",
          s"(${unionTypes.map(t => s"""on${t.name}: SelectionBuilder[${t.name}, A]""").mkString(", ")})",
          writeType(field.ofType).replace(fieldType, "A"),
          writeTypeBuilder(
            field.ofType,
            s"ChoiceOf(Map(${unionTypes.map(t => s""""${t.name}" -> Obj(on${t.name})""").mkString(", ")}))"
          )
        )
      } else if (interfaceTypes.nonEmpty) {
        (
          "[A]",
          s"(${interfaceTypes.map(t => s"""on${t.name}: SelectionBuilder[${t.name}, A]""").mkString(", ")})",
          writeType(field.ofType).replace(fieldType, "A"),
          writeTypeBuilder(
            field.ofType,
            s"ChoiceOf(Map(${interfaceTypes.map(t => s""""${t.name}" -> Obj(on${t.name})""").mkString(", ")}))"
          )
        )
      } else {
        (
          "[A]",
          s"(innerSelection: SelectionBuilder[$fieldType, A])",
          writeType(field.ofType).replace(fieldType, "A"),
          writeTypeBuilder(field.ofType, "Obj(innerSelection)")
        )
      }
    val args = field.args match {
      case Nil => ""
      case list =>
        s"(${list.map { arg =>
          s"${arg.name}: ${writeType(arg.ofType)}"
        }.mkString(", ")})"
    }
    val argBuilder = field.args match {
      case Nil => ""
      case list =>
        s", arguments = List(${list.map { arg =>
          s"""Argument("${arg.name}", ${arg.name})"""
        }.mkString(", ")})"
    }

    s"""def $name$typeParam$args$innerSelection: SelectionBuilder[$typeName, $outputType] = Field("${field.name}", $builder$argBuilder)"""
  }

  def writeArgumentFields(args: List[InputValueDefinition]): String =
    s"${args.map(arg => s"${arg.name}: ${writeType(arg.ofType)}").mkString(", ")}"

  def writeArguments(field: FieldDefinition): String =
    if (field.args.nonEmpty) {
      s"case class ${field.name.capitalize}Args(${writeArgumentFields(field.args)})"
    } else ""

  def writeDescription(description: Option[String]): String =
    description.fold("")(d => s"""@GQLDescription("$d")
                                 |""".stripMargin)

  def mapTypeName(s: String): String = s match {
    case "Float" => "Double"
    case "ID"    => "String"
    case other   => other
  }

  def writeType(t: Type): String =
    t match {
      case NamedType(name, true)   => mapTypeName(name)
      case NamedType(name, false)  => s"Option[${mapTypeName(name)}]"
      case ListType(ofType, true)  => s"List[${writeType(ofType)}]"
      case ListType(ofType, false) => s"Option[List[${writeType(ofType)}]]"
    }

  def writeTypeBuilder(t: Type, inner: String): String =
    t match {
      case NamedType(_, true)  => inner
      case NamedType(_, false) => s"OptionOf($inner)"
      case ListType(of, true)  => s"ListOf(${writeTypeBuilder(of, inner)})"
      case ListType(of, false) => s"OptionOf(ListOf(${writeTypeBuilder(of, inner)}))"
    }

  @tailrec
  def getTypeName(t: Type): String = t match {
    case NamedType(name, _)  => name
    case ListType(ofType, _) => getTypeName(ofType)
  }

  val reservedKeywords = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "forSome",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "this",
    "throw",
    "trait",
    "try",
    "true",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield"
  )
}
