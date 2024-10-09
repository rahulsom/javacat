package codegen

import codegen.CodegenHelper.codeRef
import codegen.ext.camelCase
import codegen.ext.pascalCase
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.File
import java.util.TreeMap

class TestBuilder {
    val tests = mutableListOf<String>()
    fun buildTestClass(testDir: File, packageName: String, interfaceName: String, staticImport: String?) {
        if (tests.isEmpty()) return
        val parentDir = File(testDir, packageName.replace(".", "/"))
        parentDir.mkdirs()
        val writer = File(parentDir, "${interfaceName}Test.java").bufferedWriter()
        writer.write(
            """
            package ${packageName};
            
            import com.fasterxml.jackson.core.JsonProcessingException;
            import io.github.pulpogato.common.GHGenerated;
            import io.github.pulpogato.rest.schemas.*;
            import io.github.pulpogato.test.TestUtils;
            import org.junit.jupiter.api.Test;
            import java.util.Map;
            import java.util.List;

            ${if (staticImport != null) "import static $staticImport;" else ""}
            import static org.assertj.core.api.Assertions.assertThat;
            
            class ${interfaceName}Test {
            
            """.trimIndent()
        )

        tests.forEach {
            writer.write(it)
            writer.write("\n\n")
        }

        writer.write(
            """
            
            }
            """.trimIndent()
        )
        writer.flush()
        writer.close()
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun addTest(key: String, example: String, type: String) {
        val om = ObjectMapper()

        val formatted = try {
            val parsed = om.readValue(example, TreeMap::class.java)
            om.writerWithDefaultPrettyPrinter().writeValueAsString(parsed)
        } catch (e: Exception) {
            example
        }

        val sb = StringBuilder()

        sb.append(
            """
                ${CodegenHelper.generated("", codeRef())}
                @Test
                void test${key.pascalCase()}() throws JsonProcessingException {
                    String input = /* language=JSON */ ${"\"\"\""}
            """.trimIndent()
        )
        sb.append("\n")
        sb.append(formatted.prependIndent("            ").replace("\\", "\\\\"))
        sb.append("\n")
        val type1 = when {
          type.matches("^Map<.+>$".toRegex()) ->  "Map"
          type.matches("^List<.+>$".toRegex()) ->  "List"
          else -> type
        }
        sb.append(
            """
                        ${"\"\"\""};
                
                    var processed = TestUtils.parseAndCompare(${type1}.class, input);
                    assertThat(processed).isNotNull();
                }
            """.trimIndent()
        )

        tests.add(sb.toString().prependIndent("    "))
    }
}