/**
 * Holds the engine dialog framework: a {@link org.fogofwarlabs.sc4j.engine.client.dialog.Dialog} runs a dialog definition of
 * the resource model with live {@link org.fogofwarlabs.sc4j.engine.client.dialog.Control}s (panels, labels, buttons, check boxes
 * and option buttons, lists, combo boxes, text inputs and scroll bars from the
 * {@link org.fogofwarlabs.sc4j.engine.client.dialog.DefaultControlFactory}), with focus, Enter, Escape and hotkeys; the
 * {@link org.fogofwarlabs.sc4j.engine.client.dialog.DialogStack} routes pointer and key input to the open dialogs, and a
 * {@link org.fogofwarlabs.sc4j.engine.client.dialog.DialogScene} shows a dialog as a scene or a modal popup.
 * <p>
 * The widget models hold the behaviour of the controls without a look; the default controls use them, and a mod's own controls draw
 * them in their look. The {@link org.fogofwarlabs.sc4j.engine.client.dialog.TextEditModel} edits the text of a text input (keys,
 * selection, clipboard operations, undo and redo, limits) in Unicode code points; the
 * {@link org.fogofwarlabs.sc4j.engine.client.dialog.LogSelectionModel} the selection across the lines of a read-only log, such as a chat
 * history, and its copied text; both map the pointer through
 * {@link org.fogofwarlabs.sc4j.engine.client.dialog.TextBoundaryUtils}; the
 * {@link org.fogofwarlabs.sc4j.engine.client.dialog.ListModel} holds a list's items, selection and first shown row with the navigation
 * keys and the wheel; the {@link org.fogofwarlabs.sc4j.engine.client.dialog.ComboBoxModel} a combo box's items, selection and open list,
 * which takes the next press first; the {@link org.fogofwarlabs.sc4j.engine.client.dialog.ScrollModel} the value, held part, repeat and
 * thumb drag of a scroll bar or a slider; the {@link org.fogofwarlabs.sc4j.engine.client.dialog.ViewScrollModel} a view scrolled over
 * taller content in pixels that follows its end; and the {@link org.fogofwarlabs.sc4j.engine.client.dialog.CheckModel} the state of a
 * check box or an option button.
 */
package org.fogofwarlabs.sc4j.engine.client.dialog;
