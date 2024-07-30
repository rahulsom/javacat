package codegen

import codegen.CodegenHelper.codeRef
import codegen.CodegenHelper.generated
import codegen.ext.camelCase
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem.HttpMethod
import codegen.ext.pascalCase
import codegen.ext.referenceAndDefinition
import codegen.ext.unkeywordize
import io.swagger.v3.oas.models.parameters.Parameter
import java.io.File

object PathsBuilder {
    class AtomicMethod(val path: String, val method: HttpMethod, val operationId: String, val operation: Operation)

    fun buildApis(openAPI: OpenAPI, outputDir: File, packageName: String) {
        val apiDir = File(outputDir, packageName.replace(".", "/"))
        apiDir.mkdirs()
        openAPI.paths
            .flatMap { (path, pathItem) ->
                pathItem.readOperationsMap().map { (method, operation) ->
                    AtomicMethod(path, method, operation.operationId, operation)
                }
            }
            .groupBy { it.operationId.split('/')[0] }
            .forEach { (groupName, atomicMethods) ->
                val docTag = atomicMethods[0].operation.tags[0]
                val apiDescription = openAPI.tags.find { it.name == docTag }?.description
                val interfaceName = groupName.pascalCase() + "Api"
                val typeDef = Type(Type.TypeEnum.INTERFACE, interfaceName, apiDescription ?: "")
                    .import("retrofit2.Call")
                    .import("retrofit2.http.DELETE")
                    .import("retrofit2.http.GET")
                    .import("retrofit2.http.PATCH")
                    .import("retrofit2.http.POST")
                    .import("retrofit2.http.PUT")
                    .import("retrofit2.http.Path")
                    .import("retrofit2.http.Query")
                    .import("retrofit2.http.Body")
                    .import("retrofit2.http.Headers")
                    .import("com.fasterxml.jackson.annotation.JsonFormat")
                    .import("java.util.List")
                    .import("java.util.Map")
                    .import("java.time.OffsetDateTime")
                    .import("com.github.rahulsom.javacat.rest.schemas.*")
                    .import("com.github.rahulsom.javacat.rest.schemas.Thread")
                    .import("com.github.rahulsom.javacat.rest.schemas.Package")
                    .annotation(generated("#/tags/$docTag", codeRef()))

                atomicMethods.forEach { atomicMethod ->
                    Holder.instance.get().withSchemaStack("#", "paths", atomicMethod.path, atomicMethod.method.name.lowercase()) {
                        buildMethod(atomicMethod, openAPI, typeDef)
                    }
                }

                val code = typeDef.toCode()
                val importString = typeDef.importString()
                CodegenHelper.createFile(packageName, interfaceName, outputDir, importString + "\n\n" + code)
            }

    }

    private fun buildMethod(atomicMethod: AtomicMethod, openAPI: OpenAPI, typeDef: Type) {
        val parameters = getParameters(atomicMethod, openAPI)

        val successResponses = atomicMethod.operation.responses
            .filter { (responseCode, apiResponse) ->
                val content = apiResponse.content
                content != null && content.isNotEmpty() && responseCode.startsWith("2")
            }
            .toMutableMap()

        if (successResponses.size > 1 && successResponses.containsKey("204")) {
            successResponses.remove("204")
        }

        val javadoc = buildMethodJavadoc(atomicMethod, parameters).toString()
            .split("\n")
            .dropLastWhile {it.isEmpty()}
            .joinToString("\n")
            .replace("\n", "\n * ")

        val params = parameters.joinToString(",\n") { buildParameter(it, atomicMethod, typeDef) }
        val successResponse = successResponses.entries.sortedBy {it.key}.firstOrNull()

        if (successResponse == null || successResponse.value.content == null) {
            typeDef.rawBody(
                listOf(
                    "/**\n * ${javadoc}\n */",
                    """@${atomicMethod.method}("${atomicMethod.path}")""",
                    generated("#/paths/${atomicMethod.path.replace("/", "~1")}/${atomicMethod.method.name.lowercase()}", codeRef()),
                    """Call<Void> ${atomicMethod.operationId.split('/')[1].camelCase()}(""",
                    params,
                    ");"
                ).joinToString("\n").replace(Regex("\n+"), "\n") + "\n"
            )
        } else {
            successResponse.value.content.forEach { (contentType, details) ->
                val rad = Holder.instance.get().withSchemaStack("responses", successResponse.key, "content", contentType) {
                    mapOf("${atomicMethod.operationId.split('/')[1].pascalCase()}${successResponse.key}" to details.schema).entries.first()
                        .referenceAndDefinition()
                }
                rad?.let { r ->
                    r.second?.also { typeDef.subType(it) }
                    val respRef = r.first
                    val acceptHeader = """@Headers({"Accept: $contentType"})"""
                    val methodName = when (successResponse.value.content.size) {
                        1 -> atomicMethod.operationId.split('/')[1].camelCase()
                        else -> atomicMethod.operationId.split('/')[1].camelCase() + suffixContentType(contentType)
                    }
                    typeDef.rawBody(
                        listOf(
                            "/**\n * ${javadoc}\n */",
                            acceptHeader,
                            """@${atomicMethod.method}("${atomicMethod.path}")""",
                            generated("#/paths/${atomicMethod.path.replace("/", "~1")}/${atomicMethod.method.name.lowercase()}", codeRef()),
                            """Call<${respRef}> $methodName(""",
                            params,
                            ");"
                        ).joinToString("\n").replace(Regex("\n+"), "\n") + "\n"
                    )
                }
            }
        }

    }

    private fun suffixContentType(key: String) = when (key) {
        "application/json" -> ""
        "application/vnd.github.v3.star+json" -> "Star"
        "application/vnd.github.object" -> "Object"
        "application/json+sarif" -> "Sarif"
        else -> throw IllegalArgumentException("Unknown content type: $key")
    }

    private fun buildMethodJavadoc(
        atomicMethod: AtomicMethod,
        parameters: List<Parameter>
    ): StringBuilder {
        val javadoc = StringBuilder()
        val summary = atomicMethod.operation.summary ?: ""
        val description = atomicMethod.operation.description ?: ""
        if (description.contains(summary)) {
            javadoc.append(CodegenHelper.mdToHtml(description))
        } else {
            javadoc.append(CodegenHelper.mdToHtml("**${summary}**\n\n${description}"))
        }
        if (parameters.isNotEmpty()) {
            javadoc.append("\n\n")
        }
        parameters.forEach { theParameter ->
            val paramName = theParameter.name.unkeywordize().camelCase()
            val mdToHtml = CodegenHelper.mdToHtml(theParameter.description)
            javadoc.append("@param $paramName $mdToHtml")
            if (!mdToHtml.endsWith("\n")) {
                javadoc.append("\n")
            }
        }
        if (atomicMethod.operation.externalDocs != null) {
            javadoc.append("@see <a href=\"${atomicMethod.operation.externalDocs.url}\">${atomicMethod.operation.externalDocs.description ?: "External Docs"}</a>\n")
        }
        return javadoc
    }

    private fun buildParameter(theParameter: Parameter, atomicMethod: AtomicMethod, typeDef: Type): String {
        val (ref, def) = mapOf(theParameter.name to theParameter.schema).entries.first().referenceAndDefinition()!!
        val newDef = def?.copy(name = atomicMethod.operationId.split('/')[1].pascalCase() + def.name)
        newDef?.let { typeDef.subType(it) }

        val paramName = theParameter.name.unkeywordize().camelCase()
        return "    " + when (theParameter.`in`) {
            "query" -> "@Query(\"${theParameter.name}\")"
            "body" -> "@Body"
            "path" -> "@Path(\"${theParameter.name}\")"
            "header" -> "@Header(\"${theParameter.name}\")"
            else -> throw IllegalArgumentException("Unknown parameter type: ${theParameter.`in`}")
        } + " ${newDef?.name ?: ref} $paramName"
    }

    private fun getParameters(atomicMethod: AtomicMethod, openAPI: OpenAPI): List<Parameter> {
        val parameters = atomicMethod.operation.parameters
            ?.mapNotNull { parameter ->
                if (parameter.`$ref` == null) {
                    parameter
                } else {
                    val refName = parameter.`$ref`.replace("#/components/parameters/", "")
                    openAPI.components.parameters[refName]
                }
            }?.toMutableList() ?: mutableListOf()

        if (atomicMethod.operation.requestBody != null) {
            val requestBody = atomicMethod.operation.requestBody
            parameters.add(
                Parameter()//.`$ref`(ref)
                    .`in`("body")
                    .name("body")
                    .description(requestBody.description ?: "The request body")
                    .schema(requestBody.content.firstEntry().value.schema)
                    .required(true)
            )
        }
        return parameters.toList()
    }

}
