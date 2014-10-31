/*******************************************************************************
 * Copyright (c) 2014 Martin Kloesch and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Martin Kloesch - implementation
 *******************************************************************************/
package org.eclipse.ease.lang.python.jython.debugger;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
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
import org.eclipse.ease.debugging.events.TerminateRequest;
import org.eclipse.ease.lang.python.jython.debugger.model.JythonDebugModelPresentation;
import org.python.core.Py;
import org.python.core.PyBoolean;
import org.python.core.PyObject;
import org.python.core.PyString;

/**
 * Debugger class handling communication between JythonDebugTarget and edb.py.
 */
public class JythonDebugger implements IEventProcessor, IExecutionListener {
	private PyObject fPythonDebugger;

	/** Declarations for variables and function names in Jython. */
	public static final String PY_DEBUGGER_NAME = "eclipse_jython_debugger";

	private static final String PY_CMD_SET_DEBUGGER = "set_debugger";
	private static final String PY_CMD_SET_SHOW_DYNAMIC_CODE = "set_show_dynamic_code";
	private static final String PY_CMD_RESUME = "resume";
	private static final String PY_CMD_RUN = "run";

	private static final String PySetBreakpointCmd = "set_break";
	private static final String PyClearBreakpointsCmd = "clear_all_file_breaks";

	private static final String PyTerminateCmd = "step_quit";

	private JythonDebuggerEngine fEngine;
	private EventDispatchJob fDispatcher;

	private final List<Script> fScriptStack = new ArrayList<Script>();

	private boolean fSuspended;

	private final boolean fShowDynamicCode;

	public JythonDebugger(final JythonDebuggerEngine engine, final boolean showDynamicCode) {
		fEngine = engine;
		fShowDynamicCode = showDynamicCode;
		fEngine.addExecutionListener(this);
	}

	/**
	 * Method setting up all necessary objects in Jython.
	 */
	public void setupJythonObjects(PyObject edb) {
		fPythonDebugger = edb;
		fPythonDebugger.invoke(PY_CMD_SET_DEBUGGER, Py.java2py(this));
		fPythonDebugger.invoke(PY_CMD_SET_SHOW_DYNAMIC_CODE, new PyBoolean(fShowDynamicCode));
	}

	/**
	 * Setter method for dispatcher.
	 *
	 * @param dispatcher
	 *            dispatcher for communication between debugger and debug target.
	 */
	public void setDispatcher(final EventDispatchJob dispatcher) {
		fDispatcher = dispatcher;
	}

	/**
	 * Helper method to raise event via dispatcher.
	 *
	 * @param event
	 *            Debug event to be raised.
	 */
	private void fireDispatchEvent(final IDebugEvent event) {
		synchronized (fDispatcher) {
			if (fDispatcher != null)
				fDispatcher.addEvent(event);
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
			fireDispatchEvent(new EngineStartedEvent());
			break;
		case ENGINE_END:
			fireDispatchEvent(new EngineTerminatedEvent());

			// allow for garbage collection
			fEngine = null;
			synchronized (fDispatcher) {
				fDispatcher = null;
			}
			break;

		case SCRIPT_INJECTION_START:
			fScriptStack.add(0, script);
			break;

		case SCRIPT_INJECTION_END:
			fScriptStack.remove(0);
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
			fPythonDebugger.invoke(PY_CMD_RESUME, Py.javas2pys(((ResumeRequest) event).getType()));
			resume();

		} else if (event instanceof BreakpointRequest) {
			handleBreakpointRequest((BreakpointRequest) event);
		} else if (event instanceof GetStackFramesRequest) {
		} else if (event instanceof TerminateRequest) {
			terminate();
		}
	}

	/**
	 * Terminates the debugger.
	 */
	private void terminate() {
		if (fPythonDebugger != null) {
			fPythonDebugger.invoke(PyTerminateCmd);
		}
		fPythonDebugger = null;
	}

	public void fireResumedEvent(Thread thread, int type) {
		fireDispatchEvent(new ResumedEvent(thread, type));
	}

	/**
	 * Handles BreakpointRequest by setting Breakpoint in Jython.
	 *
	 * @param event
	 *            Event containing all necessary information for the desired Breakpoint.
	 */
	private void handleBreakpointRequest(final BreakpointRequest event) {
		// Simple check to see if breakpoint is enabled.
		try {
			if (!event.getBreakpoint().isEnabled()) {
				return;
			}
		} catch (final CoreException e) {
			return;
		}
		// Create parameters in correct format
		final PyObject[] args = new PyObject[1];
		args[0] = Py.java2py(new BreakpointInfo(event.getBreakpoint()));
		fPythonDebugger.invoke(PySetBreakpointCmd, args);
	}

	/**
	 * Function called by Jython Edb object when a new file is being executed.
	 *
	 * Checks if it is necessary to set new breakpoint in Jython
	 *
	 * @param filename
	 *            filename of new Jython file currently being executed.
	 */
	public void checkBreakpoints(final String filename) {
		// Simple check to see if debugger already Garbage-collected
		if (fPythonDebugger == null)
			return;

		// BreakpointInfo object is used to have easier access to Breakpoint
		// information
		BreakpointInfo info;
		// Iterate over all Jython breakpoints and set the ones matching new
		// file.
		fPythonDebugger.invoke(PyClearBreakpointsCmd, new PyString(filename));
		for (final IBreakpoint bp : DebugPlugin.getDefault().getBreakpointManager().getBreakpoints(JythonDebugModelPresentation.ID)) {
			// simple check to see if Breakpoint is enabled. Try - catch
			// necessary
			try {
				if (!bp.isEnabled()) {
					continue;
				}
			} catch (final CoreException e) {
				continue;
			}
			info = new BreakpointInfo(bp);

			// If filename matches add new breakpoint
			if (info.getFilename().equals(filename)) {
				final PyObject[] args = new PyObject[1];
				args[0] = Py.java2py(info);

				// We can call set_break since it will update existing
				// breakpoint if necessary.
				fPythonDebugger.invoke(PySetBreakpointCmd, args);
			}
		}
	}

	/**
	 * Handler called when script is ready to be executed.
	 *
	 * @param script
	 *            Script to be executed.
	 */
	public void scriptReady(final Script script) {
		fireDispatchEvent(new ScriptReadyEvent(script, Thread.currentThread(), true));
		suspend(null);
	}

	private void resume() {
		synchronized (fEngine) {
			fSuspended = false;
			fEngine.notifyAll();
		}
	}

	private void suspend(final List<IScriptDebugFrame> stack) {
		// fireDispatchEvent(new SuspendedEvent(1, Thread.currentThread(), stack));

		synchronized (fEngine) {
			fSuspended = true;

			try {
				while (fSuspended)
					fEngine.wait();

			} catch (final InterruptedException e) {
				fSuspended = false;
			}

			// FIXME mode is not set here, find out!
			fireDispatchEvent(new ResumedEvent(Thread.currentThread(), 1));
		}
	}

	public void callback(String type) {
		System.out.println(type);
	}

	public Object execute(Script script) {
		try {
			fPythonDebugger.invoke(PY_CMD_RUN, Py.javas2pys(script.getCode(), registerScript(script)));
		} catch (final Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	private final Map<String, Script> fScriptRegistry = new HashMap<String, Script>();

	private String registerScript(Script script) {
		final String reference = script.isDynamic() ? getHash(script) : script.getCommand().toString();
		fScriptRegistry.put(reference, script);
		return reference;
	}

	private static String getHash(Script script) {
		try {
			final MessageDigest md = MessageDigest.getInstance("MD5");
			final byte[] digest = md.digest(script.getCode().getBytes("UTF-8"));
			return "__hash_" + bytesToHex(digest);
		} catch (final Exception e) {
			e.printStackTrace();
		}

		return "__hash_none";
	}

	// taken from http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java
	final protected static char[] hexArray = "0123456789ABCDEF".toCharArray();

	// taken from http://stackoverflow.com/questions/9655181/convert-from-byte-array-to-hex-string-in-java
	public static String bytesToHex(byte[] bytes) {
		final char[] hexChars = new char[bytes.length * 2];
		for (int j = 0; j < bytes.length; j++) {
			final int v = bytes[j] & 0xFF;
			hexChars[j * 2] = hexArray[v >>> 4];
			hexChars[j * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}

}