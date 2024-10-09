package codegen

import codegen.CodegenHelper.generated
import codegen.ext.camelCase
import codegen.ext.className
import codegen.ext.pascalCase
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.PathItem
import java.io.File
import java.io.StringWriter

object WebhooksBuilder {
    fun buildWebhooks(openAPI: OpenAPI, mainDir: File, rootPackage: String, restPackage: String, packageName: String, testDir: File) {
        val webhooksDir = File(mainDir, packageName.replace(".", "/"))
        webhooksDir.mkdirs()
        openAPI.webhooks.forEach { (name, webhook) ->
            val interfaceName = name.pascalCase() + "Webhook"
            val content = createWebhookInterface(name, interfaceName, webhook, openAPI, restPackage, packageName, testDir)
            CodegenHelper.createFile(packageName, interfaceName, mainDir, content, rootPackage)
        }
    }

    private fun createWebhookInterface(
        name: String,
        interfaceName: String,
        webhook: PathItem,
        openAPI: OpenAPI,
        restPackage: String,
        packageName: String,
        testDir: File
    ): String {
        return StringWriter().let { writer ->
            val testBuilder = TestBuilder()
            writer.write(
                """
                import ${restPackage}.schemas.*;
                import org.springframework.http.ResponseEntity;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestBody;
                import org.springframework.web.bind.annotation.RequestHeader;

                ${generated("#/webhooks/$name", CodegenHelper.codeRef())}
                public interface $interfaceName<T> {

                """.trimIndent()
            )
            if (webhook.readOperationsMap().size != 1) {
                throw RuntimeException("Webhook $name has more than one operation")
            }
            webhook.readOperationsMap().forEach { (method, operation) ->
                writer.write("\n")
                writer.write("    /**\n")
                writer.write("     * ${CodegenHelper.mdToHtml(operation.summary)}\n")
                if (operation.description != null) {
                    val htmlDescription = CodegenHelper.mdToHtml(operation.description)
                    writer.write("     * $htmlDescription")
                    if (!htmlDescription.endsWith("\n")) {
                        writer.write("\n")
                    }
                }
                writer.write("     *\n")
                operation.parameters.filter { it.`in` == "header" }.forEach {
                    writer.write("     * @param ${it.name.camelCase()} '${it.name}' header. Example: <code>${it.example}</code>\n")
                }
                writer.write("     * @param requestBody The request body\n")
                writer.write("     *\n")
                writer.write("     * @return It doesn't really matter. A 200 means success. Anything else means failure.\n")
                writer.write("     *\n")
                if (operation.externalDocs != null) {
                    writer.write("     * @see <a href=\"${operation.externalDocs.url}\">${operation.externalDocs.description ?: "GitHub Docs"}</a>\n")
                }
                writer.write("     */\n")
                writer.write("    @PostMapping(headers = \"X-Github-Event=${name}\")\n")
                val methodName = "process" + operation.operationId.split('/').last().pascalCase()

                val requestBody = operation.requestBody

                if (requestBody.content.firstEntry().value.schema.`$ref` != null) {
                    val ref = requestBody.content.firstEntry().value.schema.`$ref`.replace("#/components/schemas/", "")
                    val examples = requestBody.content.firstEntry().value.examples
                    val schema = openAPI.components.schemas.entries.first { it.key == ref }
                    val type = schema.className()
                    writer.write("    ResponseEntity<T> $methodName(\n")
                    operation.parameters.filter { it.`in` == "header" }.forEach {
                        val required = when {
                            it.name.startsWith("X-Hub-Signature") -> false
                            it.name.startsWith("X-GitHub-Enterprise") -> false
                            else -> true
                        }
                        writer.write("""        @RequestHeader(required = ${required},name = "${it.name}") String ${it.name.camelCase()},""")
                        writer.write("\n")
                    }
                    writer.write("        @RequestBody ${type} requestBody\n")
                    writer.write("    );\n")

                    examples?.forEach { (key, value) ->
                        val ref = value.`$ref`
                        val example = openAPI.components.examples.entries.firstOrNull { it.key == ref.replace("#/components/examples/", "") }
                        if (example != null) {
                            testBuilder.addTest(key, example.value.value.toString(), type)
                        }
                    }

                } else {
                    throw RuntimeException("Unknown type for ${requestBody.content.firstEntry().value.schema}")
                }
            }

            writer.write("}\n")
            testBuilder.buildTestClass(testDir, packageName, interfaceName, null)
            writer.toString()
        }
    }
}
