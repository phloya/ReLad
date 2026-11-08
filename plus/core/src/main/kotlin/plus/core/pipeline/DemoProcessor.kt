package plus.core.pipeline

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import plus.core.image.PageBitmaps

/**
 * Developer check of the whole pipeline (Re:Lad Dev only): draws a red frame and a label on the page.
 * If the reader shows them, pages go through the pipeline, the cache and the reload correctly.
 */
class DemoProcessor(private val enabled: () -> Boolean) : PageProcessor {

    override val id = "demo"
    override val order = 1_000

    override fun signature(context: PageContext): String? = if (enabled()) "v1" else null

    override suspend fun process(input: ByteArray, context: PageContext): ProcessedPage {
        val bitmap = PageBitmaps.decode(input)
        val canvas = Canvas(bitmap)
        val stroke = maxOf(4f, bitmap.width / 150f)
        val frame = Paint().apply {
            color = Color.RED
            style = Paint.Style.STROKE
            strokeWidth = stroke
        }
        canvas.drawRect(stroke / 2, stroke / 2, bitmap.width - stroke / 2, bitmap.height - stroke / 2, frame)
        val label = "Re:Lad pipeline · page ${context.pageIndex + 1}"
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = bitmap.width / 24f
        }
        val background = Paint().apply { color = Color.RED }
        val padding = text.textSize / 3
        canvas.drawRect(0f, 0f, text.measureText(label) + padding * 2, text.textSize + padding * 2, background)
        canvas.drawText(label, padding, text.textSize + padding / 2, text)
        return ProcessedPage(PageBitmaps.encode(bitmap))
    }
}
