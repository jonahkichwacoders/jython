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

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.ease.IDebugEngine;
import org.eclipse.ease.Script;
import org.eclipse.ease.debugging.EventDispatchJob;
import org.eclipse.ease.lang.python.jython.JythonScriptEngine;
import org.eclipse.ease.lang.python.jython.debugger.model.JythonDebugTarget;
import org.python.core.Py;
import org.python.core.PyList;
import org.python.core.PyString;


/**
 * A script engine to execute/debug Python code on a Jython interpreter.
 * 
 * Uses most of JythonScriptEngine's functionality and only extends it
 * when file is to be debugged.
 */
public class JythonDebuggerEngine extends JythonScriptEngine implements IDebugEngine {
	private JythonDebugger mDebugger = null;
	
	private boolean mDebugRun;
	private String mPyDir;
	
	public JythonDebuggerEngine() {
		super();
		mPyDir = getPyDir();
	}
	
	public void setDebugger(JythonDebugger debugger) {
		mDebugger = debugger;
	}
	
	/**
	 * Parses the plugin bundle to get absolute path to plugin's python directory.
	 * 
	 * FIXME: this is a hack, refactor (kmh)
	 * @return String pyDir: absolute path to python directory
	 */
	private String getPyDir() {
		String pluginRoot = Platform.getBundle("org.eclipse.ease.lang.python.jython.debugger").getLocation();
		// FIXME: remove - this is ridiculus
		if(pluginRoot.startsWith("reference:file:")) pluginRoot = pluginRoot.substring(15);
		return new File(new File(pluginRoot), "python").getAbsolutePath();
	}

	/**
	 * Sets up the engine (simply calls JythonScriptEngine's constructor).
	 * If configuration is launched in debug mode Python file to set up debugger will be called.
	 */
	@Override
	protected boolean setupEngine() {
		if (!super.setupEngine())
			return false;

		// Check if currently run in debug mode
		if (mDebugger != null) {
			// add python directory to Jython search path
			addPyDirToJythonPath();
			
			// set objects in JythonDebugTarget
			// FIXME: use events to correctly setup interpreter in JythonDebugTarget
			mDebugger.setInterpreter(mEngine);
			mDebugger.setPyDir(mPyDir);
		}
		return true;
	}

	/**
	 * Executes a script or other command.
	 * 
	 * If actual script is to be executed with debug run patch command to start debugger.
	 */
	@Override
	protected Object execute(final Script script, final Object reference, final String fileName, final boolean uiThread) throws Exception {
		if (uiThread || !mDebugRun || fileName == null) {
			return super.execute(script, reference, fileName, uiThread);
		} else {
			// FIXME: copied code from JythonScriptEngine necessary for imports.
			Object file = script.getFile();
			File f = null;
			if (file instanceof IFile) {
				f = ((IFile) file).getLocation().toFile();
			} else if (file instanceof File) {
				f = ((File) file);
			}
			
			if (f != null) {
				String absolutePath = f.getAbsolutePath();
				setVariable("__file__", absolutePath);
				String containerPart = f.getParent();
				Py.getSystemState().path.insert(0, Py.newString(containerPart));
			}
			
			// use absolute file location that Jython can handle breakpoints correctly
			String absoluteFilename = new File(ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile(), fileName).getAbsolutePath().replace("\\","\\\\");
			
			// Patch Script to use debugger to start file
			String patchedCommandString = String.format("%s.run('%s')", JythonDebugger.PyDebuggerName, absoluteFilename);
			Script patchedScript = new Script(patchedCommandString);
			mDebugger.scriptReady(script);
			
			return super.execute(patchedScript, reference, fileName, uiThread);
		}
	}

	/**
	 * Adds the plugin's python directory to Jython search path.
	 * Necessary to have Python Edb debugger class available.
	 */
	private void addPyDirToJythonPath() {
		PyString pythonDirectory = new PyString(mPyDir);

		// only append if not on path already
		PyList systemPath = mEngine.getSystemState().path;
		if (!systemPath.contains(pythonDirectory)) {
			systemPath.add(0, pythonDirectory);
		}
    }
	
	/**
	 * Creates new JythonDebugTarget, JythonDebugger and sets up EventHandlers
	 */
	@Override
	public void setupDebugger(ILaunch launch, boolean suspendOnStartup, boolean suspendOnScriptLoad, boolean showDynamicCode) {
		JythonDebugTarget target = new JythonDebugTarget(launch, suspendOnStartup);
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
