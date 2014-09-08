/*******************************************************************************
 * Copyright (c) 2014 Martin Kloesch and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Christian Pontesegger - initial API
 *     Martin Kloesch - implementation
 *******************************************************************************/
package org.eclipse.ease.lang.python.jython.debugger;

import java.io.File;
import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.ease.IExecutionListener;
import org.eclipse.ease.IScriptEngine;
import org.eclipse.ease.Script;
import org.eclipse.ease.debugging.EventDispatchJob;
import org.eclipse.ease.debugging.IEventProcessor;
import org.eclipse.ease.debugging.IScriptDebugFrame;
import org.eclipse.ease.debugging.events.BreakpointRequest;
import org.eclipse.ease.debugging.events.EngineStartedEvent;
import org.eclipse.ease.debugging.events.EngineTerminatedEvent;
import org.eclipse.ease.debugging.events.GetStackFramesRequest;
import org.eclipse.ease.debugging.events.IDebugEvent;
import org.eclipse.ease.debugging.events.ResumeRequest;
import org.eclipse.ease.debugging.events.ResumedEvent;
import org.eclipse.ease.debugging.events.ScriptReadyEvent;
import org.eclipse.ease.debugging.events.SuspendedEvent;
import org.eclipse.ease.debugging.events.TerminateRequest;
import org.eclipse.ease.lang.python.jython.debugger.model.JythonDebugModelPresentation;
import org.python.core.Py;
import org.python.core.PyBoolean;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.python.util.InteractiveInterpreter;

/**
 * Debugger class handling communication between JythonDebugTarget and edb.py
 *
 * @author kloeschmartin
 *
 */
public class JythonDebugger implements IEventProcessor, IExecutionListener {
	private InteractiveInterpreter mInterpreter;
	private PyObject mPyDebugger;
	private String mPyDir;
	private Thread mThread;

	/**
	 * Declarations for variables and function names in Jython:
	 */
	public static final String PyDebuggerName = "eclipse_jython_debugger";
	private static final String PySetDebuggerCmd = "set_debugger";
	private static final String PySetSuspendOnStartupCmd = "set_suspend_on_startup";
	private static final String PySetSuspendOnScriptLoad = "set_suspend_on_script_load";
	private static final String PySetBreakpointCmd = "set_break";
	private static final String PyClearBreakpointsCmd = "clear_all_file_breaks";

	private static final String PyStepoverCmd = "step_stepover";
	private static final String PyStepintoCmd = "step_stepinto";
	private static final String PyStepoutCmd = "step_stepout";
	private static final String PyResumeCmd = "step_continue";
	private static final String PyTerminateCmd = "step_quit";

	private JythonDebuggerEngine mEngine;
	private EventDispatchJob mDispatcher;
	private final boolean mSuspendOnStartup;
	private final boolean mSuspendOnScriptLoad;

	public JythonDebugger(final JythonDebuggerEngine engine, final boolean suspendOnStartup, final boolean suspendOnScriptLoad) {
		mEngine = engine;
		mEngine.addExecutionListener(this);
		mSuspendOnStartup = suspendOnStartup;
		mSuspendOnScriptLoad = suspendOnScriptLoad;
	}

	public void setInterpreter(final InteractiveInterpreter interpreter) {
		mInterpreter = interpreter;
	}

	public void setPyDir(final String pyDir) {
		mPyDir = pyDir;
	}

	/**
	 * Method setting up all necessary objects in Jython.
	 */
	private void setupJythonObjects() {
		mInterpreter.execfile(new File(new File(mPyDir), "setup_debugger.py").getAbsolutePath());
		mPyDebugger = mInterpreter.get(PyDebuggerName);
		mPyDebugger.invoke(PySetDebuggerCmd, Py.java2py(this));
		mPyDebugger.invoke(PySetSuspendOnStartupCmd, new PyBoolean(mSuspendOnStartup));
		mPyDebugger.invoke(PySetSuspendOnScriptLoad, new PyBoolean(mSuspendOnScriptLoad));
	}

	/**
	 * Setter method for dispatcher.
	 *
	 * @param dispatcher
	 *            : dispatcher for communication between debugger and debug target.
	 */
	public void setDispatcher(final EventDispatchJob dispatcher) {
		mDispatcher = dispatcher;
	}

	/**
	 * Helper method to raise event via dispatcher.
	 *
	 * @param event
	 *            : Debug event to be raised.
	 */
	private void fireDispatchEvent(final IDebugEvent event) {
		synchronized (mDispatcher) {
			if (mDispatcher != null)
				mDispatcher.addEvent(event);
		}
	}

	/**
	 * Notify function called by Eclipse EASE framework.
	 *
	 * Raises according events depending on status
	 */
	@Override
	public void notify(final IScriptEngine engine, final Script script, final int status) {
		switch (status) {
		case ENGINE_START:
			setupJythonObjects();
			fireDispatchEvent(new EngineStartedEvent());
			break;
		case ENGINE_END:
			fireDispatchEvent(new EngineTerminatedEvent());

			// allow for garbage collection
			mEngine = null;
			synchronized (mDispatcher) {
				mDispatcher = null;
			}
			break;

		default:
			// unknown event
			break;
		}
	}

	/**
	 * Function called to handle incoming event.
	 *
	 * Depending on type corresponding handler will be called
	 */
	@Override
	public void handleEvent(final IDebugEvent event) {
		if (event instanceof ResumeRequest) {
			handleResumeRequest((ResumeRequest) event);
		} else if (event instanceof BreakpointRequest) {
			handleBreakpointRequest((BreakpointRequest) event);
		} else if (event instanceof GetStackFramesRequest) {
		} else if (event instanceof TerminateRequest) {
			terminate();
		}
	}

	/**
	 * Handles ResumeRequest from DebugTarget.
	 *
	 * Depending on type of ResumeRequest different method in Jython will be called. Currently implemented cases are: STEP_INTO STEP_OVER STEP_RETURN
	 *
	 * If other type given, then resume will be called.
	 *
	 * @param event
	 *            : ResumeRequest containing necessary information for action to be performed
	 */
	private void handleResumeRequest(final ResumeRequest event) {
		// Simply switch over the type and call according function
		switch (event.getType()) {
		case DebugEvent.STEP_INTO:
			mPyDebugger.invoke(PyStepintoCmd);
			break;
		case DebugEvent.STEP_OVER:
			mPyDebugger.invoke(PyStepoverCmd);
			break;
		case DebugEvent.STEP_RETURN:
			mPyDebugger.invoke(PyStepoutCmd);
			break;
		default:
			// TODO: think if it would be better to only handle resume request
			mPyDebugger.invoke(PyResumeCmd);
			break;
		}
		fireDispatchEvent(new ResumedEvent(mThread, event.getType()));
	}

	/**
	 * Terminates the debugger.
	 */
	private void terminate() {
		if (mPyDebugger != null) {
			mPyDebugger.invoke(PyTerminateCmd);
		}
		mPyDebugger = null;
	}

	/**
	 * Function called by Jython Edb object firing a SuspendedEvent with the given stacktrace
	 */
	public void fireSuspendEvent(final Thread thread, final List<IScriptDebugFrame> stack) {
		mThread = thread;
		fireDispatchEvent(new SuspendedEvent(1, thread, stack));
	}

	/**
	 * Handles BreakpointRequest by setting Breakpoint in Jython.
	 *
	 * @param event
	 *            : Event containing all necessary information for the desired Breakpoint.
	 */
	private void handleBreakpointRequest(final BreakpointRequest event) {
		// Simple check to see if breakpoint is enabled.
		try {
			if (!event.getBreakpoint().isEnabled()) {
				return;
			}
		} catch (CoreException e) {
			return;
		}
		// Create parameters in correct format
		PyObject[] args = new PyObject[1];
		args[0] = Py.java2py(new BreakpointInfo(event.getBreakpoint()));
		mPyDebugger.invoke(PySetBreakpointCmd, args);
	}

	/**
	 * Function called by Jython Edb object when a new file is being executed.
	 *
	 * Checks if it is necessary to set new breakpoint in Jython
	 *
	 * @param filename
	 *            : filename of new Jython file currently being executed.
	 */
	public void checkBreakpoints(final String filename) {
		// Simple check to see if debugger already Garbage-collected
		if (mPyDebugger == null)
			return;

		// BreakpointInfo object is used to have easier access to Breakpoint information
		BreakpointInfo info;
		// Iterate over all Jython breakpoints and set the ones matching new file.
		mPyDebugger.invoke(PyClearBreakpointsCmd, new PyString(filename));
		for (IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints(JythonDebugModelPresentation.ID)) {
			// simple check to see if Breakpoint is enabled. Try - catch necessary
			try {
				if (!bp.isEnabled()) {
					continue;
				}
			} catch (CoreException e) {
				continue;
			}
			info = new BreakpointInfo(bp);

			// If filename matches add new breakpoint
			if (info.getFilename().equals(filename)) {
				PyObject[] args = new PyObject[1];
				args[0] = Py.java2py(info);

				// We can call set_break since it will update existing
				// breakpoint if necessary.
				mPyDebugger.invoke(PySetBreakpointCmd, args);
			}
		}
	}

	/**
	 * Handler called when script is ready to be executed.
	 *
	 * @param script
	 *            : Script to be executed.
	 */
	public void scriptReady(final Script script) {
		ScriptReadyEvent ev = new ScriptReadyEvent(script, Thread.currentThread(), true);
		fireDispatchEvent(ev);
	}
}