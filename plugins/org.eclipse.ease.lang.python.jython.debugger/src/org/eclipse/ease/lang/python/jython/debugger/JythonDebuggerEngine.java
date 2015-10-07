/*******************************************************************************
 * Copyright (c) 2014 Martin Kloesch and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Martin Kloesch - initial implementation of Debugger extensions
 *******************************************************************************/
package org.eclipse.ease.lang.python.jython.debugger;

import java.io.InputStream;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.ease.IDebugEngine;
import org.eclipse.ease.Script;
import org.eclipse.ease.debugging.EventDispatchJob;
import org.eclipse.ease.lang.python.jython.JythonScriptEngine;
import org.eclipse.ease.lang.python.jython.debugger.model.JythonDebugTarget;
import org.python.core.PyObject;

/**
 * A script engine to execute/debug Python code on a Jython interpreter.
 *
 * Uses most of JythonScriptEngine's functionality and only extends it when file is to be debugged.
 */
public class JythonDebuggerEngine extends JythonScriptEngine implements IDebugEngine {
	private JythonDebugger fDebugger = null;

	public void setDebugger(final JythonDebugger debugger) {
		fDebugger = debugger;
	}

	@Override
	protected boolean setupEngine() {
		if (super.setupEngine()) {

			if (fDebugger == null)
				// in case we were called using "Run as"
				return true;

			// load python part of debugger
			final InputStream stream = ResourceHelper.getResourceStream("org.eclipse.ease.lang.python.jython.debugger", "python/edb.py");

			try {
				// load debugger class as normal python code
				// this.internalExecute will already wrap code using the debugger
				super.internalExecute(new Script("Load Python debugger", stream), null, null);

				final Object pyDebugger = internalGetVariable("eclipse_jython_debugger");
				if (pyDebugger instanceof PyObject) {
					fDebugger.setupJythonObjects((PyObject) pyDebugger);
					return true;
				}

			} catch (final Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		return false;
	}

	@Override
	protected Object internalExecute(final Script script, final Object reference, final String fileName) throws Exception {
		if (fDebugger != null)
			return fDebugger.execute(script);

		return super.internalExecute(script, reference, fileName);
	}

	@Override
	public void setupDebugger(final ILaunch launch, final boolean suspendOnStartup, final boolean suspendOnScriptLoad, final boolean showDynamicCode) {
		final JythonDebugTarget target = new JythonDebugTarget(launch, suspendOnStartup, suspendOnScriptLoad, showDynamicCode);
		launch.addDebugTarget(target);

		final JythonDebugger debugger = new JythonDebugger(this, showDynamicCode);

		setDebugger(debugger);

		final EventDispatchJob dispatcher = new EventDispatchJob(target, debugger);
		target.setDispatcher(dispatcher);
		debugger.setDispatcher(dispatcher);
		dispatcher.schedule();
	}
}
