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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.debug.core.DebugEvent;
import org.eclipse.ease.ExitException;
import org.eclipse.ease.IExecutionListener;
import org.eclipse.ease.Script;
import org.eclipse.ease.debugging.AbstractScriptDebugger;
import org.eclipse.ease.debugging.IEventProcessor;
import org.eclipse.ease.debugging.IScriptDebugFrame;
import org.eclipse.ease.debugging.ScriptDebugFrame;
import org.eclipse.ease.debugging.events.IDebugEvent;
import org.eclipse.ease.debugging.events.TerminateRequest;
import org.python.core.Py;
import org.python.core.PyFrame;
import org.python.core.PyObject;

/**
 * Debugger class handling communication between JythonDebugTarget and edb.py.
 */
public class JythonDebugger extends AbstractScriptDebugger implements IEventProcessor, IExecutionListener {

	public class JythonDebugFrame extends ScriptDebugFrame implements IScriptDebugFrame {

		public JythonDebugFrame(final PyFrame frame) {
			super(fScriptRegistry.get(frame.f_code.co_filename), frame.f_lineno, TYPE_FILE);
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
			return getEngine().getVariables();
		}
	}

	/** Declarations for variables and function names in Jython. */
	private static final String PY_CMD_SET_DEBUGGER = "set_debugger";
	private static final String PY_CMD_RUN = "run";

	private PyObject fPythonStub;

	public JythonDebugger(final JythonDebuggerEngine engine, final boolean showDynamicCode) {
		super(engine, showDynamicCode);
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
	 * Function called to handle incoming event.
	 *
	 * Depending on type corresponding handler will be called
	 */
	@Override
	public void handleEvent(final IDebugEvent event) {
		if (event instanceof TerminateRequest) {
			resume(DebugEvent.STEP_END);

		} else
			super.handleEvent(event);
	}

	private static boolean isUserCode(final PyFrame frame) {
		return frame.f_code.co_filename.startsWith("__ref_");
	}

	private List<IScriptDebugFrame> getStacktrace(final PyFrame origin) {
		List<IScriptDebugFrame> trace = new ArrayList<IScriptDebugFrame>();

		PyFrame frame = origin;
		while (frame != null) {
			if (isUserCode(frame)) {
				if (isTrackedScript(fScriptRegistry.get(frame.f_code.co_filename)))
					trace.add(new JythonDebugFrame(frame));
			}

			frame = frame.f_back;
		}

		return trace;
	}

	public void traceDispatch(final PyFrame frame, final String type) {
		if (getResumeType() == DebugEvent.STEP_END)
			throw new ExitException("Debug aborted by user");

		if (isUserCode(frame)) {
			Script script = fScriptRegistry.get(frame.f_code.co_filename);

			if (isTrackedScript(script)) {

				// update stacktrace
				setStacktrace(getStacktrace(frame));

				// do not process script load event (line == 0)
				if (frame.f_lineno != 0)
					processLine(script, frame.f_lineno);
			}
		}
	}

	public Object execute(final Script script) {
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