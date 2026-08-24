package me.a1i.contextualbookmarks.editor

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon

class ContextualBookmarkGutterRenderer(
    private val bookmarkId: String,
    mnemonic: String?,
    private val navigate: (String) -> Unit,
) : GutterIconRenderer() {
    private val normalizedMnemonic = mnemonic?.uppercase()?.take(1)
    private val badge = BookmarkBadgeIcon(normalizedMnemonic)
    private val clickAction = object : AnAction() {
        override fun actionPerformed(event: AnActionEvent) = navigate(bookmarkId)
    }

    override fun getIcon(): Icon = badge
    override fun getTooltipText(): String = normalizedMnemonic
        ?.let { "Contextual bookmark $it" }
        ?: "Contextual bookmark"
    override fun getClickAction(): AnAction = clickAction
    override fun isNavigateAction(): Boolean = true
    override fun getAlignment(): Alignment = Alignment.CENTER

    override fun equals(other: Any?): Boolean =
        other is ContextualBookmarkGutterRenderer &&
            bookmarkId == other.bookmarkId && normalizedMnemonic == other.normalizedMnemonic

    override fun hashCode(): Int = 31 * bookmarkId.hashCode() + normalizedMnemonic.hashCode()
}

private class BookmarkBadgeIcon(private val text: String?) : Icon {
    private val size: Int
        get() = JBUI.scale(16)

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = JBColor(Color(0x3574F0), Color(0x4B8BFF))
            g.fillRoundRect(x + JBUI.scale(1), y + JBUI.scale(1), size - JBUI.scale(2), size - JBUI.scale(2), JBUI.scale(5), JBUI.scale(5))
            if (text != null) {
                g.color = JBColor.WHITE
                g.font = component?.font?.deriveFont(Font.BOLD, JBUI.scale(10).toFloat())
                    ?: Font(Font.SANS_SERIF, Font.BOLD, JBUI.scale(10))
                val metrics = g.fontMetrics
                val tx = x + (size - metrics.stringWidth(text)) / 2
                val ty = y + (size - metrics.height) / 2 + metrics.ascent
                g.drawString(text, tx, ty)
            } else {
                val dot = JBUI.scale(4)
                g.color = JBColor.WHITE
                g.fillOval(x + (size - dot) / 2, y + (size - dot) / 2, dot, dot)
            }
        } finally {
            g.dispose()
        }
    }
}
