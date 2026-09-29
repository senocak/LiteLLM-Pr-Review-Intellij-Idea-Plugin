package com.github.senocak.prreview

import com.intellij.openapi.ui.ComboBox
import javax.swing.DefaultComboBoxModel

/**
 * The model drop-down, shared by the settings page and the tool window.
 *
 * Both are populated from the same `/models` endpoint and both have to survive the same three
 * cases: a gateway that is down, a saved model the endpoint does not list, and a user who would
 * rather type a name than pick from a list. Keeping the behaviour in one place is what makes the
 * two boxes behave identically rather than nearly so.
 */

/** The value in the box, whether picked from the list or typed into the editor. */
internal fun selectedModel(combo: ComboBox<String>): String {
    val typed = (combo.editor?.item as? String)?.trim()
    if (!typed.isNullOrEmpty()) return typed
    return (combo.selectedItem as? String)?.trim().orEmpty()
}

/**
 * Replaces the contents of the box, keeping whatever was in it.
 *
 * An editable combo drops the editor's text when its model is replaced, and the value already in
 * the box is the one the user has: a saved model the gateway does not list, or one they typed a
 * moment ago. It is put back afterwards, so a list arriving never overwrites a choice.
 */
internal fun fillModelCombo(combo: ComboBox<String>, models: List<String>) {
    val selected = selectedModel(combo)
    combo.model = DefaultComboBoxModel(models.toTypedArray())
    // An editable box shows its selected value whether or not it is one of the items, which is how
    // a served-but-unlisted model stays visible instead of silently becoming the first one.
    if (combo.isEditable) {
        combo.editor?.item = selected
    } else {
        combo.selectedItem = selected
    }
}
