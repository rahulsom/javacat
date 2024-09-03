package codegen.ext

import codegen.CodegenHelper
import codegen.CodegenHelper.codeRef
import codegen.CodegenHelper.generated
import codegen.CodegenHelper.printObject
import codegen.CodegenHelper.todo
import codegen.Holder
import codegen.Type
import codegen.Types
import io.swagger.v3.oas.models.media.Schema

fun Map.Entry<String, Schema<*>>.className() = when {
    Holder.instance.get().isNestedWithSameName(key) -> key.pascalCase() + "Inner"
    else -> key.pascalCase()
}

data class Field(val property: String, val type: String, val name: String, val javadoc: String) {
    private fun annotation() = "@JsonProperty(\"$property\")"

    fun toFieldDefinition() =
        Holder.instance.get().withSchemaStack("properties", property) {
            val wrappedJavadoc = """
                    |/**
                    | * ${javadoc.split("\n").dropLastWhile { it.isEmpty() }.joinToString("\n").replace("\n", "\n * ")}
                    | */
                    """.trimMargin()

            val things = listOf(
                "",
                if (javadoc.isEmpty()) null else wrappedJavadoc,
                generated(Holder.instance.get().getSchemaStackRef(), codeRef()),
                annotation(),
                """private $type $name;"""
            )

            things.filterNotNull().joinToString("\n")
        }
}

fun Map.Entry<String, Schema<*>>.fieldDefinition(): Field? {
    return referenceAndDefinition()?.let {rad ->
        Field(
            key,
            rad.first,
            key.unkeywordize().camelCase(),
            schemaJavadoc()
        )
    }
}

fun isSingleOrArray(oneOf: List<Schema<Any>>, type: String) = oneOf.size == 2
        && oneOf.first().types == setOf(type)
        && oneOf.last().types == setOf("array")
        && oneOf.last().items.types == setOf(type)

fun typesAre(oneOf: List<Schema<Any>>, vararg types: String) = types.toSet() == oneOf.flatMap { it.types ?: listOf() }.toSet()

fun Map.Entry<String, Schema<*>>.referenceAndDefinition(isArray: Boolean = false): Pair<String, Type?>? {
    val types = value.types?.filterNotNull()?.filter { it != "null" }
    val anyOf = value.anyOf?.filterNotNull()?.filter { it.types != setOf("null") }
    val oneOf = value.oneOf?.filterNotNull()
    val allOf = value.allOf?.filterNotNull()
    return when {
        value.`$ref` != null -> {
            val schemaName = value.`$ref`.replace("#/components/schemas/", "")
            val entries = Holder.instance.get().openAPI!!.components.schemas.filter { (k, _) -> k == schemaName }.entries
            val schema = entries.first()
            schema.referenceAndDefinition()?.copy(second = null)
        }

        anyOf != null && anyOf.size == 1 -> {
            val anyOfValue = anyOf.first()
            mapOf(key to anyOfValue).entries.first().referenceAndDefinition()
        }
        oneOf != null && isSingleOrArray(oneOf, "string") -> Pair("List<String>", null)
        oneOf != null && typesAre(oneOf, "string", "integer") -> Pair("StringOrInteger", null)
        anyOf != null -> buildType { buildFancyObject(anyOf, "anyOf") }
        oneOf != null -> buildType { buildFancyObject(oneOf, "oneOf") }
        allOf != null -> buildType { buildFancyObject(allOf, "allOf") }

        types == null && value.properties != null -> mapOf(key to value.also { it.types = mutableSetOf("object") }).entries.first()
            .referenceAndDefinition()
        types == null && value.properties != null && value.properties.isEmpty() && value.additionalProperties == false -> Pair("Void", null)

        types == null && value.properties != null && value.properties.isNotEmpty() -> buildType { buildSimpleObject(isArray) }
        types == null -> when {
            else -> Pair("/*${todo()} Unhandled ${printObject(this)}*/ Map<String, Object>", null)
        }

        types.isEmpty() -> null

        types.size == 1 -> when (types.first()) {
            "string" -> when {
                value.enum != null -> buildType { buildEnum() }
                value.format == null -> Pair("String", null)
                else -> when (value.format) {
                    "uri" -> Pair(Types.URI, null)
                    "uuid" -> Pair(Types.UUID, null)
                    "date" -> Pair(Types.LOCAL_DATE, null)
                    "date-time" -> Pair(Types.OFFSET_DATE_TIME, null)
                    "binary" -> Pair(Types.BYTE_ARRAY, null)
                    "email", "hostname", "ip/cidr", "uri-template", "repo.nwo", "ssh-key", "ssh-key fingerprint" -> Pair(Types.STRING, null)
                    else -> throw RuntimeException("Unknown string type for ${key}, stack: ${Holder.instance.get().getSchemaStackRef()}")
                }
            }

            "integer" -> when (value.format) {
                "int32" -> Pair(Types.INTEGER, null)
                "timestamp" -> Pair(Types.EPOCH_TIME, null)
                else -> Pair(Types.LONG, null)
            }

            "boolean" -> Pair(Types.BOOLEAN, null)
            "number" -> Pair(Types.DOUBLE, null)
            "array" -> mapOf(key to value.items).entries.first().referenceAndDefinition(true)?.let {
                Pair("List<${it.first}>", it.second)
            }

            "object" -> when {
                value.additionalProperties != null && (value.properties == null || value.properties.isEmpty()) -> {
                    val additionalProperties = value.additionalProperties
                    if (additionalProperties is Schema<*>) {
                        mapOf(key to additionalProperties).entries.first().referenceAndDefinition()
                    } else {
                        val message = additionalProperties.javaClass
                        println(message)
                        Pair("Map<String, /*${todo()} ${printObject(value)}*/ Object>", null)
                    }
                }

                value.properties != null && value.properties.isNotEmpty() -> buildType { buildSimpleObject(isArray) }
                else -> Pair("Map<String, Object>", null)
            }

            else -> throw RuntimeException("Unknown type for ${key}, stack: ${Holder.instance.get().getSchemaStackRef()}")
        }

        types.toSet() == setOf("string", "integer") -> Pair("StringOrInteger", null)
        types.toSet() == setOf("string", "object") -> Pair("StringOrObject", null)
        types.toSet() == setOf("string", "object", "integer") -> Pair("StringObjectOrInteger", null)
        else -> Pair("/*${todo()} ${printObject(value)}*/Object\n", null)
    }
}

private fun Map.Entry<String, Schema<*>>.buildType(f: () -> Type) =
    when {
        Holder.instance.get().schemaStack.size > 3 -> Holder.instance.get().withSchemaStack("properties") { Pair(className(), f()) }
        else -> Pair(className(), f())
    }


private fun Map.Entry<String, Schema<*>>.buildFancyObject(subSchemas: List<Schema<Any>>?, type: String): Type {
    return Holder.instance.get().withSchemaStack(key) {
        val theType = Type(Type.TypeEnum.CLASS, className(), """
            |
            |$type
            |""".trimMargin())
            .import("java.util.List")
            .import("java.util.Map")
            .import("java.util.UUID")
            .import("java.net.URI")
            .import("com.fasterxml.jackson.annotation.JsonFormat")
            .import("com.fasterxml.jackson.annotation.JsonProperty")
            .import("com.fasterxml.jackson.databind.annotation.JsonDeserialize")
            .import("com.fasterxml.jackson.databind.annotation.JsonSerialize")
            .import("java.time.OffsetDateTime")
            .import("java.time.LocalDate")
            .import("lombok.experimental.Delegate")
            .import("lombok.experimental.Tolerate")
            .annotation(generated(Holder.instance.get().getSchemaStackRef(), codeRef()))
            .annotation("@Getter")
            .annotation("@Setter")
            .annotation("""@JsonDeserialize(using = %TYPE%.%TYPE%Deserializer.class)""")
            .annotation("""@JsonSerialize(using = %TYPE%.%TYPE%Serializer.class)""")

        val deserializer = StringBuilder()
        deserializer.append("""
            |public static class %TYPE%Deserializer extends FancyDeserializer<%TYPE%> {
            |    public %TYPE%Deserializer() {
            |        super(%TYPE%.class, %TYPE%::new, Mode.$type, List.of(
            |
        """.trimMargin())

        val serializer = StringBuilder()
        serializer.append("""
            |public static class %TYPE%Serializer extends FancySerializer<%TYPE%> {
            |    public %TYPE%Serializer() {
            |        super(%TYPE%.class, Mode.$type, List.of(
            |
        """.trimMargin())

        (subSchemas?:listOf())
            .mapIndexed { index, it ->
                var newKey = key + index
                if (it.`$ref` != null) {
                    newKey = it.`$ref`.replace("#/components/schemas/", "")
                }
                newKey to it
            }
            .forEachIndexed { index, (newKey, it) ->
                val keyValuePair = mapOf(newKey to it).entries.first()
                val rad = keyValuePair.referenceAndDefinition()
                rad?.let {
                    rad.second?.let { x -> theType.subType(x) }
                    keyValuePair.fieldDefinition()?.let { theType.rawBody(it.toFieldDefinition()) }
                    if (index > 0) {
                        deserializer.append(",\n")
                        serializer.append(",\n")
                    }
                    val fType = rad.first.replace(Regex("<.*>"), "")
                    val fieldName = rad.second?.name ?: keyValuePair.key
                    deserializer.append("            new FancyDeserializer.SettableField<>(${fType}.class, %TYPE%::set${fieldName.pascalCase()})")
                    serializer.append("            new FancySerializer.GettableField<>(${fType}.class, %TYPE%::get${fieldName.pascalCase()})")
                }
            }
        deserializer.append("""
            |
            |        ));
            |    }
            |}""".trimMargin())
        serializer.append("""
            |
            |        ));
            |    }
            |}""".trimMargin())

        theType.rawBody(deserializer.toString())
        theType.rawBody(serializer.toString())

        theType
    }
}

private fun Map.Entry<String, Schema<*>>.buildSimpleObject(isArray: Boolean): Type {
    return Holder.instance.get().withSchemaStack(key) {

        val theType = Type(Type.TypeEnum.CLASS, className(), schemaJavadoc())
            .import("java.util.List")
            .import("java.util.Map")
            .import("java.util.UUID")
            .import("java.net.URI")
            .import("com.fasterxml.jackson.annotation.JsonFormat")
            .import("com.fasterxml.jackson.annotation.JsonProperty")
            .import("java.time.OffsetDateTime")
            .import("java.time.LocalDate")
            .annotation(generated(Holder.instance.get().getSchemaStackRef(), codeRef()))

        value.properties?.forEach { p ->
            if (isArray) {
                Holder.instance.get().withSchemaStack("items") {
                    p.referenceAndDefinition()?.let { (_, s) ->
                        produceField(s, theType, p)
                    }
                }
            } else {
                p.referenceAndDefinition()?.let { (_, s) ->
                    produceField(s, theType, p)
                }
            }
        }

        theType
    }
}

private fun produceField(s: Type?, definition: Type, p: Map.Entry<String, Schema<Any>>) {
    s?.let { definition.subType(it) }
    p.fieldDefinition()?.let { definition.rawBody(it.toFieldDefinition()) }
}

private fun Map.Entry<String, Schema<*>>.buildEnum(): Type {
    return Holder.instance.get().withSchemaStack(key) {
        val javadoc = schemaJavadoc()
        Type(Type.TypeEnum.ENUM, className(), javadoc)
            .import("com.fasterxml.jackson.annotation.JsonCreator")
            .import("com.fasterxml.jackson.annotation.JsonValue")
            .import("lombok.RequiredArgsConstructor")
            .import("lombok.Getter")
            .annotation(generated(Holder.instance.get().getSchemaStackRef(), codeRef()))
            .annotation("@RequiredArgsConstructor")
            .annotation("@Getter")
            .rawBody(value.enum.joinToString(", ", "", ";") { v ->
                if (v == null) "NULL(null)" else "${v.toString().unkeywordize().trainCase()}(\"${v}\")"
            })
            .rawBody("@JsonValue private final String value;")
//            .rawBody(
//                """
//            |// %TYPE%(String value) { this.value = value; }
//            |
//            |// @JsonValue
//            |// public String getValue() { return value; }
//            |
//            |@JsonCreator
//            |public static %TYPE% fromValue(String value) {
//            |    for (%TYPE% e : %TYPE%.values()) {
//            |        if (e.value.equals(value)) {
//            |            return e;
//            |        }
//            |    }
//            |    throw new IllegalArgumentException("Unexpected value '" + value + "'");
//            |}
//        """.trimMargin()/*.prependIndent("    ")*/
//            )
    }
}

private fun Map.Entry<String, Schema<*>>.schemaJavadoc(): String {
    val title = value.title ?: ""
    val description = value.description ?: ""
    val javadoc = if (description.contains(title)) {
        CodegenHelper.mdToHtml(description)
    } else {
        CodegenHelper.mdToHtml("**${title}**\n\n$description")
    }
    return javadoc
}
