package codegen

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import org.apache.commons.text.WordUtils
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File
import java.io.FileWriter
import java.util.stream.Stream

object CodegenHelper {
    fun todo(): String {
        val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        val frame = walker.walk { frames: Stream<StackWalker.StackFrame> -> frames.skip(1).findFirst() }
        return frame.map { "TODO ${it.fileName}:${it.lineNumber}" }.orElse("TODO: UNKNOWN")
    }

    fun codeRef(): String {
        val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)
        val frame = walker.walk { frames: Stream<StackWalker.StackFrame> -> frames.skip(1).findFirst() }
        return frame.map { "${it.fileName}:${it.lineNumber}" }.orElse("FIXME: UNKNOWN")
    }

    fun printObject(theObject: Any): String {
        val mapper = ObjectMapper()
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL)
        mapper.enable(SerializationFeature.INDENT_OUTPUT)
        return mapper.writeValueAsString(theObject).split("\n")
            .filter {
                !it.contains("exampleSetFlag") && !it.contains("valueSetFlag")
            }
            .joinToString("\n")
    }

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()

    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(listOf(TablesExtension.create()))
        .build()

    fun generated(from: String, by: String) = """@GHGenerated(from = "$from", by = "$by")""".trimIndent()

    fun mdToHtml(md: String?): String {
        if (md == null) {
            return ""
        }
        val document = parser.parse(md)
        return renderer.render(document)
            .replace("*/", "*&#47;")
            .replace(Regex("</p>\\s*<p>"), "\n<br/>\n")
            .replace("<p>", "")
            .replace("</p>", "")
            .replace(" <a href=", "\n<a href=")
            .replace("&quot;<a href=", "\n&quot;<a href=")
            .replace("</a> ", "</a>\n")
            .replace("</a>. ", "</a>.\n")
            .replace("</a>, ", "</a>,\n")
            .replace("</a>&quot; ", "</a>&quot;\n")
            .split("\n")
            .dropLastWhile { it.isBlank() }
            .joinToString("\n") {
                when {
                    it.contains("<a href") -> it
                    else -> WordUtils.wrap(it, 120)
                }
            }
    }

    fun createFile(packageName: String, topLevelClass: String, outputDir: File, content: String) {
        val packageDir = File(outputDir, packageName.replace(".", "/"))
        packageDir.mkdirs()
        FileWriter(File(packageDir, "${topLevelClass}.java")).use { writer ->
            writer.write("package $packageName;\n\n")
            writer.write("import com.github.rahulsom.javacat.common.*;\n")
            writer.write(content)
        }
    }

}
