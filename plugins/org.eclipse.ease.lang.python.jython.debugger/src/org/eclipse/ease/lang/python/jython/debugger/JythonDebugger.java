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

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.ease.ExitException;
import org.eclipse.ease.IExecutionListener;
import org.eclipse.ease.IScriptEngine;
import org.eclipse.ease.Script;
import org.eclipse.ease.debugging.AbstractScriptDebugger;
import org.eclipse.ease.debugging.IEventProcessor;
import org.eclipse.ease.debugging.IScriptDebugFrame;
import org.eclipse.ease.debugging.events.BreakpointRequest;
import org.eclipse.ease.debugging.events.EngineStartedEvent;
import org.eclipse.ease.debugging.events.EngineTerminatedEvent;
import org.eclipse.ease.debugging.events.IDebugEvent;
import org.eclipse.ease.debugging.events.ResumeRequest;
import org.eclipse.ease.debugging.events.ResumedEvent;
import org.eclipse.ease.debugging.events.ScriptReadyEvent;
import org.eclipse.ease.debugging.events.SuspendedEvent;
import org.eclipse.ease.debugging.events.TerminateRequest;
import org.python.core.Py;
import org.python.core.PyFrame;
import org.python.core.PyObject;

/**
 * Debugger class handling communication between JythonDebugTarget and edb.py.
 */
public class JythonDebugger extends AbstractScriptDebugger implements IEventProcessor, IExecutionListener {

	public class JythonDebugFrame implements IScriptDebugFrame {

		private final PyFrame fFrame;

		public JythonDebugFrame(final PyFrame frame) {
			fFrame = frame;
		}

		@Override
		public int getLineNumber() {
			return fFrame.f_lineno;
		}

		@Override
		public Script getScript() {
			return fScriptRegistry.get(fFrame.f_code.co_filename);
		}

		@Override
		public int getType() {
			// return mFnOrScript.isFunction() ? TYPE_FUNCTION : TYPE_FILE;
			return TYPE_FILE;
		}

		@Override
		public String getName() {
			Script script = getScript();
			if (script.isDynamic()) {
				// dynamic script
				final String title = getScript().getTitle();
				return (title != null) ? "Dynamic: " + title : "(Dynamic)";

			} else {
				final Object command = getScript().getCommand();
				if (command != null) {
					if (command instanceof IFile)
						return ((IFile) command).getName();

					else if (command instanceof File)
						return ((File) command).getName();

					return command.toString();
				}
			}

			return "(unknown source)";
		}

		@Override
		public Map<String, Object> getVariables() {
			return fEngine.getVariables();
		}
	}

	/** Declarations for variables and function names in Jython. */
	private static final String PY_CMD_SET_DEBUGGER = "set_debugger";
	private static final String PY_CMD_RUN = "run";

	private JythonDebuggerEngine fEngine;

	private boolean fSuspended = false;

	private final boolean fShowDynamicCode;

	private int fResumeType;

	private PyObject fPythonStub;

	private List<IScriptDebugFrame> fLastTrace = Collections.emptyList();

	public JythonDebugger(final JythonDebuggerEngine engine, final boolean showDynamicCode) {
		fEngine = engine;
		fShowDynamicCode = showDynamicCode;
		fEngine.addExecutionListener(this);
	}

	/**
	 * Link Jython stub with this debugger instance.
	 *
	 * @param pythonStub
	 *            jython debugger stub instance
	 */
	public void setupJythonObjects(final PyObject pythonStub) {
		fPythonStub = pythonStub;
		fPythonStub.invoke(PY_CMD_SET_DEBUGGER, Py.java2py(this));
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
			fEngine.removeExecutionListener(this);
			fEngine = null;
			break;

		default:
			// unknown event
			break;
		}
	}

	private final Map<Script, Set<Integer>> fBreakpoints = new HashMap<Script, Set<Integer>>();

	/**
	 * Function called to handle incoming event.
	 *
	 * Depending on type corresponding handler will be called
	 */
	@Override
	public void handleEvent(final IDebugEvent event) {
		if (event instanceof ResumeRequest) {
			if (((ResumeRequest) event).getType() != DebugEvent.UNSPECIFIED)
				fResumeType = ((ResumeRequest) event).getType();

			resume();

		} else if (event instanceof BreakpointRequest) {
			IBreakpoint breakpoint = ((BreakpointRequest) event).getBreakpoint();

			try {
				Integer lineNumber = (Integer) breakpoint.getMarker().getAttribute("lineNumber");

				if (!fBreakpoints.containsKey(((BreakpointRequest) event).getScript()))
					fBreakpoints.put(((BreakpointRequest) event).getScript(), new HashSet<Integer>());

				fBreakpoints.get(((BreakpointRequest) event).getScript()).add(lineNumber);

			} catch (CoreException e) {
				// TODO handle this exception (but for now, at least know it happened)
				throw new RuntimeException(e);
			}

			// } else if (event instanceof GetStackFramesRequest) {
		} else if (event instanceof TerminateRequest) {
			fResumeType = DebugEvent.STEP_END;
			resume();
		}
	}

	private void resume() {
		synchronized (fEngine) {
			fSuspended = false;
			fEngine.notifyAll();
		}
	}

	private List<IScriptDebugFrame> getStackTrace(final PyFrame origin) {
		List<IScriptDebugFrame> trace = new ArrayList<IScriptDebugFrame>();

		PyFrame frame = origin;
		while (frame != null) {
			if (isUserCode(frame)) {
				if (fShowDynamicCode || !fScriptRegistry.get(frame.f_code.co_filename).isDynamic())
					trace.add(new JythonDebugFrame(frame));
			}

			frame = frame.f_back;
		}

		return trace;
	}

	private static boolean isUserCode(final PyFrame frame) {
		return frame.f_code.co_filename.startsWith("__ref_");
	}

	private void suspend(final IDebugEvent event) {
		if (event instanceof SuspendedEvent)
			fLastTrace = ((SuspendedEvent) event).getDebugFrames();

		synchronized (fEngine) {
			// need to fire event in synchronized code to avoid getting a resume event too soon
			fSuspended = true;
			fireDispatchEvent(event);

			try {
				while (fSuspended)
					fEngine.wait();

			} catch (final InterruptedException e) {
				fSuspended = false;
			}

			fireDispatchEvent(new ResumedEvent(Thread.currentThread(), fResumeType));
		}
	}

	public void traceDispatch(final PyFrame frame, final String type) {
		if (fResumeType == DebugEvent.STEP_END)
			throw new ExitException("Debug aborted by user");

		if (isUserCode(frame)) {
			Script script = fScriptRegistry.get(frame.f_code.co_filename);

			if (fShowDynamicCode || !script.isDynamic()) {
				System.out.println("traceDispatch (" + type.toString() + "): " + frame.f_code.co_filename + ", " + frame.f_lineno);

				List<IScriptDebugFrame> lastTrace = fLastTrace;
				List<IScriptDebugFrame> currentTrace = getStackTrace(frame);

				// check for user breakpoint
				Set<Integer> breakpoints = fBreakpoints.get(script);
				if ((breakpoints != null) && (breakpoints.contains(frame.f_lineno))) {
					// breakpoint hit
					suspend(new SuspendedEvent(DebugEvent.CLIENT_REQUEST, Thread.currentThread(), currentTrace));

				} else if (("call".equals(type)) && (frame.f_lineno == 0))
					// script ready event
					suspend(new ScriptReadyEvent(script, Thread.currentThread(), currentTrace.size() == 1));

				else if ("line".equals(type)) {
					// executing scripts
					switch (fResumeType) {
					case DebugEvent.STEP_INTO:
						if (lastTrace.size() <= currentTrace.size())
							suspend(new SuspendedEvent(fResumeType, Thread.currentThread(), currentTrace));

						break;

					case DebugEvent.STEP_OVER:
						if (lastTrace.size() >= currentTrace.size())
							suspend(new SuspendedEvent(fResumeType, Thread.currentThread(), currentTrace));

						break;

					case DebugEvent.STEP_RETURN:
						if (lastTrace.size() > currentTrace.size())
							suspend(new SuspendedEvent(fResumeType, Thread.currentThread(), currentTrace));

						break;
					}
				}
			}
		}
	}

	public Object execute(final Script script) {
		try {
			System.out.println(script.getCode());
		} catch (Exception e) {
			// TODO handle this exception (but for now, at least know it happened)
			throw new RuntimeException(e);
			
		}
		fPythonStub.invoke(PY_CMD_RUN, Py.javas2pys(script, registerScript(script)));

		// FIXME return execution result
		return null;
	}

	private final Map<String, Script> fScriptRegistry = new HashMap<String, Script>();

	private String registerScript(final Script script) {
		final String reference = getHash(script, fScriptRegistry.keySet());
		fScriptRegistry.put(reference, script);
		return reference;
	}

	private static String getHash(final Script script, final Set<String> existingKeys) {
		StringBuilder buffer = new StringBuilder("__ref_");
		buffer.append(script.isDynamic() ? "dyn" : script.getCommand().toString());
		buffer.append("_");

		for (int index = 0; index < 10; index++)
			buffer.append((char) ('a' + new Random().nextInt(26)));

		if (existingKeys.contains(buffer.toString()))
			return getHash(script, existingKeys);

		return buffer.toString();
	}

}