/*******************************************************************************
 * Copyright (c) 2013 Martin Kloesch and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Christian Pontesegger - initial API
 *     Martin Kloesch - implementation
 *******************************************************************************/
package org.eclipse.ease.lang.python.jython.debugger.model;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.ease.Script;
import org.eclipse.ease.debugging.ScriptDebugTarget;

/**
 * ScriptDebugTarget for communication between Eclipse framework and Jython debugger.
 *
 * @author kloeschmartin
 */
public class JythonDebugTarget extends ScriptDebugTarget {
	private static final String pyBreakpointType = JythonDebugModelPresentation.ID;

	/**
	 * Constructor for now only calls super constructor.
	 *
	 * @param launch
	 * @param suspendOnStartup
	 * @param suspendOnScriptLoad
	 * @param showDynamicCode
	 */
	public JythonDebugTarget(final ILaunch launch, final boolean suspendOnStartup, final boolean suspendOnScriptLoad, boolean showDynamicCode) {
		super(launch, suspendOnStartup, suspendOnScriptLoad, showDynamicCode);
	}

	@Override
	public String getName() throws DebugException {
		return "EASE Jython Debugger";
	}

	// ************************************************************
	// IEventProcessor
	// ************************************************************

	/**
	 * Getter methods for all matching breakpoints in given script.
	 *
	 * Currently EASE Jython Debugger uses PyDev breakpoints, this could change though.
	 */
	@Override
	protected IBreakpoint[] getBreakpoints(final Script script) {
		return DebugPlugin.getDefault().getBreakpointManager().getBreakpoints(pyBreakpointType);
	}

	@Override
	public boolean supportsBreakpoint(final IBreakpoint breakpoint) {
		return true;
	}
}
