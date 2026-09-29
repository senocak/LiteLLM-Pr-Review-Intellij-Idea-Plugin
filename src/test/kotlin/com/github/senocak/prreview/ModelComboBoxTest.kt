package com.github.senocak.prreview

import com.intellij.openapi.ui.ComboBox
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two boxes the plugin shows are Swing components, so these run on a real EDT rather than a
 * mock: the behaviour being pinned down is `setModel` dropping an editable box's text, which is a
 * property of the platform's combo box and not of this plugin's logic.
 */
class ModelComboBoxTest {
    private fun combo(vararg initial: String): ComboBox<String> =
        ComboBox<String>().apply {
            isEditable = true
            if (initial.isNotEmpty()) fillModelCombo(this, initial.toList())
        }

    private fun text(combo: ComboBox<String>): String = selectedModel(combo)

    @Test
    fun `a listed model is selectable`() {
        val box = combo()
        fillModelCombo(box, listOf("claude-3-5-sonnet", "gpt-4o"))
        box.selectedItem = "gpt-4o"
        assertEquals("gpt-4o", text(box))
    }

    @Test
    fun `a typed model is read back from the editor`() {
        val box = combo("gpt-4o")
        box.editor?.item = "some/model:free"
        assertEquals("some/model:free", text(box))
    }

    @Test
    fun `a list arriving does not overwrite what is already selected`() {
        // The case the box exists for: the saved model is chosen, the gateway answers late, and
        // without this the user's selection would be replaced by whatever the list happened to
        // contain first.
        val box = combo()
        box.editor?.item = "my-model"
        fillModelCombo(box, listOf("gpt-4o", "claude-3-5-sonnet"))
        assertEquals("my-model", text(box))
    }

    @Test
    fun `a list arriving does not overwrite a selection made from the list`() {
        val box = combo("gpt-4o", "claude-3-5-sonnet")
        box.selectedItem = "claude-3-5-sonnet"
        fillModelCombo(box, listOf("gpt-4o", "claude-3-5-sonnet", "o3"))
        assertEquals("claude-3-5-sonnet", text(box))
    }

    @Test
    fun `a model the gateway does not list stays visible`() {
        val box = combo()
        box.editor?.item = "team-reviewer"
        fillModelCombo(box, listOf("gpt-4o"))
        assertEquals("team-reviewer", text(box))
    }

    @Test
    fun `an empty list keeps the model the user has`() {
        // A gateway with nothing configured answers with an empty list. Turning that into a blank
        // box would be worse than useless.
        val box = combo("gpt-4o")
        fillModelCombo(box, emptyList())
        assertEquals("gpt-4o", text(box))
    }

    @Test
    fun `an empty box reads as empty rather than throwing`() {
        assertEquals("", text(ComboBox<String>().apply { isEditable = true }))
    }

    @Test
    fun `a list with a single model is a one-item box`() {
        val box = combo()
        fillModelCombo(box, listOf("gpt-4o"))
        assertEquals(1, box.itemCount)
    }

    @Test
    fun `surrounding whitespace on a typed model is dropped`() {
        val box = combo()
        box.editor?.item = "  gpt-4o  "
        assertEquals("gpt-4o", text(box))
    }

    @Test
    fun `a blank editor falls back to the selected item`() {
        val box = combo("gpt-4o")
        box.editor?.item = "   "
        assertEquals("gpt-4o", text(box))
    }
}
