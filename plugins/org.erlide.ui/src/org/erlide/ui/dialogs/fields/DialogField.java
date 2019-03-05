/*******************************************************************************
 * Copyright (c) 2000, 2005 IBM Corporation and others. All rights reserved. This program
 * and the accompanying materials are made available under the terms of the Eclipse Public
 * License v1.0 which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors: IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.erlide.ui.dialogs.fields;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;

/**
 * Base class of all dialog fields. Dialog fields manage controls together with the model,
 * independed from the creation time of the widgets. - support for automated layouting. -
 * enable / disable, set focus a concept of the base class.
 *
 * DialogField have a label.
 */
public class DialogField {

    private Label fLabel;

    protected String fLabelText;

    private IDialogFieldListener fDialogFieldListener;

    private boolean fEnabled;

    public DialogField() {
        fEnabled = true;
        fLabel = null;
        fLabelText = ""; //$NON-NLS-1$
    }

    /**
     * Sets the label of the dialog field.
     */
    public void setLabelText(final String labeltext) {
        fLabelText = labeltext;
        if (isOkToUse(fLabel)) {
            fLabel.setText(labeltext);
        }
    }

    // ------ change listener

    /**
     * Defines the listener for this dialog field.
     */
    public final void setDialogFieldListener(final IDialogFieldListener listener) {
        fDialogFieldListener = listener;
    }

    /**
     * Programatical invocation of a dialog field change.
     */
    public void dialogFieldChanged() {
        if (fDialogFieldListener != null) {
            fDialogFieldListener.dialogFieldChanged(this);
        }
    }

    // ------- focus management

    /**
     * Tries to set the focus to the dialog field. Returns <code>true</code> if the dialog
     * field can take focus. To be reimplemented by dialog field implementors.
     */
    public boolean setFocus() {
        return false;
    }

    /**
     * Posts <code>setFocus</code> to the display event queue.
     */
    public void postSetFocusOnDialogField(final Display display) {
        if (display != null) {
            display.asyncExec(() -> setFocus());
        }
    }

    // ------- layout helpers

    /**
     * Creates all controls of the dialog field and fills it to a composite. The composite
     * is assumed to have <code>MGridLayout</code> as layout. The dialog field will adjust
     * its controls' spans to the number of columns given. To be reimplemented by dialog
     * field implementors.
     */
    public Control[] doFillIntoGrid(final Composite parent, final int nColumns) {
        assertEnoughColumns(nColumns);

        final Label label = getLabelControl(parent);
        label.setLayoutData(DialogField.gridDataForLabel(nColumns));

        return new Control[] { label };
    }

    /**
     * Returns the number of columns of the dialog field. To be reimplemented by dialog
     * field implementors.
     */
    public int getNumberOfControls() {
        return 1;
    }

    protected static GridData gridDataForLabel(final int span) {
        final GridData gd = new GridData(GridData.HORIZONTAL_ALIGN_FILL);
        gd.horizontalSpan = span;
        return gd;
    }

    // ------- ui creation

    /**
     * Creates or returns the created label widget.
     *
     * @param parent
     *            The parent composite or <code>null</code> if the widget has already been
     *            created.
     */
    public Label getLabelControl(final Composite parent) {
        if (fLabel == null) {
            assertCompositeNotNull(parent);

            fLabel = new Label(parent, SWT.LEFT | SWT.WRAP);
            fLabel.setFont(parent.getFont());
            fLabel.setEnabled(fEnabled);
            if (fLabelText != null && !"".equals(fLabelText)) { //$NON-NLS-1$
                fLabel.setText(fLabelText);
            } else {
                fLabel.setText("."); //$NON-NLS-1$
                fLabel.setVisible(false);
            }
        }
        return fLabel;
    }

    /**
     * Creates a spacer control.
     *
     * @param parent
     *            The parent composite
     */
    public static Control createEmptySpace(final Composite parent) {
        return DialogField.createEmptySpace(parent, 1);
    }

    /**
     * Creates a spacer control with the given span. The composite is assumed to have
     * <code>MGridLayout</code> as layout.
     *
     * @param parent
     *            The parent composite
     */
    public static Control createEmptySpace(final Composite parent, final int span) {
        final Label label = new Label(parent, SWT.LEFT);
        final GridData gd = new GridData();
        gd.horizontalAlignment = GridData.BEGINNING;
        gd.grabExcessHorizontalSpace = false;
        gd.horizontalSpan = span;
        gd.horizontalIndent = 0;
        gd.widthHint = 0;
        gd.heightHint = 0;
        label.setLayoutData(gd);
        return label;
    }

    /**
     * Tests is the control is not <code>null</code> and not disposed.
     */
    protected final boolean isOkToUse(final Control control) {
        return control != null && Display.getCurrent() != null && !control.isDisposed();
    }

    // --------- enable / disable management

    /**
     * Sets the enable state of the dialog field.
     */
    public final void setEnabled(final boolean enabled) {
        if (enabled != fEnabled) {
            fEnabled = enabled;
            updateEnableState();
        }
    }

    /**
     * Called when the enable state changed. To be extended by dialog field implementors.
     */
    protected void updateEnableState() {
        if (fLabel != null) {
            fLabel.setEnabled(fEnabled);
        }
    }

    /**
     * Brings the UI in sync with the model. Only needed when model was changed in
     * different thread whil UI was lready created.
     */
    public void refresh() {
        updateEnableState();
    }

    /**
     * Gets the enable state of the dialog field.
     */
    public final boolean isEnabled() {
        return fEnabled;
    }

    protected final void assertCompositeNotNull(final Composite comp) {
        // assertThat("uncreated control requested with composite null", comp,
        // is(not(nullValue())));
    }

    protected final void assertEnoughColumns(final int nColumns) {
        // assertThat("given number of columns is too small", nColumns,
        // is(greaterThanOrEqualTo(getNumberOfControls())));
    }

}
