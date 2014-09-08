/*******************************************************************************
 * Copyright (c) 2014 Kloesch Martin
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Martin Kloesch - initial implementation
 *******************************************************************************/
package org.eclipse.ease.lang.python.jython.debugger;

import java.io.File;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.LineBreakpoint;

/**
 * Helper class to parse IBreakpoint and have easy access to information in Jython.
 *
 * @author kloeschmartin
 */
public class BreakpointInfo {
	/**
	 * All necessary info for breakpoints (from Jython Edb point of view)
	 */
	private String mFilename;
	private int mLinenumber = -1;
	private final String mCondition = null;
	private final int mHitcount = 0;
	private final boolean mTemporary = false;

	/**
	 * Parses breakpoint info from IBreakpoint to members
	 *
	 * @param breakpoint
	 *            : breakpoint to be parsed.
	 */
	public BreakpointInfo(final IBreakpoint breakpoint) {
		// Calculate absolute filename (necessary for Jython debugger)
		mFilename = breakpoint.getMarker().getResource().getFullPath().toOSString();
		mFilename = new File(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile(), mFilename).getAbsolutePath();

		// If LineBreakpoint given calculate Linenumber
		if (breakpoint instanceof LineBreakpoint) {
			try {
				mLinenumber = ((LineBreakpoint) breakpoint).getLineNumber();
			} catch (CoreException e) {
				e.printStackTrace();
			}
		}

		// Get condition from PyBreakpoint
		// FIXME temporary disabled to get rid of pydev dependency
		// if (breakpoint instanceof PyBreakpoint) {
		// try {
		// mCondition = ((PyBreakpoint) breakpoint).getCondition();
		// } catch (DebugException e) {
		// }
		// }
	}

	// ************************************************************
	// Getter methods for necessary information
	// ************************************************************
	public String getFilename() {
		return mFilename;
	}

	public int getLinenumber() {
		return mLinenumber;
	}

	public String getCondition() {
		return mCondition;
	}

	public int getHitcount() {
		return mHitcount;
	}

	public boolean getTemporary() {
		return mTemporary;
	}
}
