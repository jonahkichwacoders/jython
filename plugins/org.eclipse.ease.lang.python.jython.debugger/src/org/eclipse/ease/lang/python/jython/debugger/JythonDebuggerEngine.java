/*******************************************************************************
 * Copyright (c) 2014 Martin Kloesch and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Christian Pontesegger - initial API
 *     Arthur Daussy - initial implementation of JythonScriptEngine
 *     Martin Kloesch - implementation of Debugger extensions
 *******************************************************************************/
package org.eclipse.ease.lang.python.jython.debugger;

import java.io.File;
import java.io.InputStream;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.ease.IDebugEngine;
import org.eclipse.ease.Script;
import org.eclipse.ease.debugging.EventDispatchJob;
import org.eclipse.ease.lang.python.jython.JythonScriptEngine;
import org.eclipse.ease.lang.python.jython.debugger.model.JythonDebugTarget;
import org.python.core.Py;
import org.python.core.PyObject;

/**
 * A script engine to execute/debug Python code on a Jython interpreter.
 *
 * Uses most of JythonScriptEngine's functionality and only extends it when file is to be debugged.
 */
public class JythonDebuggerEngine extends JythonScriptEngine implements IDebugEngine {
	private JythonDebugger mDebugger = null;

	private boolean mDebugRun;

	public void setDebugger(final JythonDebugger debugger) {
		mDebugger = debugger;
	}

	@Override
	protected boolean setupEngine() {
		if (super.setupEngine()) {

			// load python part of debugger
			final InputStream stream = ResourceHelper.getResourceStream("org.eclipse.ease.lang.python.jython.debugger", "python/edb.py");

			try {
				execute(new Script("Load Python debugger", stream), null, null, false);

				final Object pyDebugger = internalGetVariable("eclipse_jython_debugger");
				if (pyDebugger instanceof PyObject) {
					mDebugger.setupJythonObjects((PyObject) pyDebugger);
					return true;
				}

			} catch (final Exception e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}

		return false;
	}

	/**
	 * Executes a script or other command.
	 *
	 * If actual script is to be executed with debug run patch command to start debugger.
	 */
	@Override
	protected Object execute(final Script script, final Object reference, final String fileName, final boolean uiThread) throws Exception {
		if (uiThread || !mDebugRun || (fileName == null)) {
			return super.execute(script, reference, fileName, uiThread);
		} else {
			// FIXME: copied code from JythonScriptEngine necessary for imports.
			final Object file = script.getFile();
			File f = null;
			if (file instanceof IFile) {
				f = ((IFile) file).getLocation().toFile();
			} else if (file instanceof File) {
				f = ((File) file);
			}

			if (f != null) {
				final String absolutePath = f.getAbsolutePath();
				setVariable("__file__", absolutePath);
				final String containerPart = f.getParent();
				Py.getSystemState().path.insert(0, Py.newString(containerPart));
			}

			// use absolute file location that Jython can handle breakpoints correctly
			final String absoluteFilename = new File(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile(), fileName).getAbsolutePath().replace("\\",
					"\\\\");

			// Patch Script to use debugger to start file
			final String patchedCommandString = String.format("%s.run('%s')", JythonDebugger.PyDebuggerName, absoluteFilename);
			final Script patchedScript = new Script(patchedCommandString);
			mDebugger.scriptReady(script);

			return super.execute(patchedScript, reference, fileName, uiThread);
		}
	}

	/**
	 * Creates new JythonDebugTarget, JythonDebugger and sets up EventHandlers
	 */
	@Override
	public void setupDebugger(final ILaunch launch, final boolean suspendOnStartup, final boolean suspendOnScriptLoad, final boolean showDynamicCode) {
		final JythonDebugTarget target = new JythonDebugTarget(launch, suspendOnStartup, suspendOnScriptLoad);
		mDebugRun = true;
		launch.addDebugTarget(target);

		final JythonDebugger debugger = new JythonDebugger(this, suspendOnStartup, suspendOnScriptLoad);

		setDebugger(debugger);

		final EventDispatchJob dispatcher = new EventDispatchJob(target, debugger);
		target.setDispatcher(dispatcher);
		debugger.setDispatcher(dispatcher);
		dispatcher.schedule();
	}
}
