package codegen

object Types {
    const val STRING = "String"
    const val INTEGER = "Integer"
    const val BOOLEAN = "Boolean"
    const val DOUBLE = "Double"
    const val LONG = "Long"
    const val BYTE_ARRAY = "byte[]"
    const val URI = "URI"
    const val UUID = "UUID"
    const val LOCAL_DATE = "@JsonFormat(pattern = \"yyyy-MM-dd\", shape = JsonFormat.Shape.STRING) LocalDate"
    const val OFFSET_DATE_TIME = "@JsonFormat(pattern = \"yyyy-MM-dd'T'HH:mm:ssX\", shape = JsonFormat.Shape.STRING) OffsetDateTime"
    const val MAP_STRING_OBJECT = "Map<String, Object>"
    const val EPOCH_TIME = "@JsonFormat(shape = JsonFormat.Shape.NUMBER_INT) OffsetDateTime"
}
