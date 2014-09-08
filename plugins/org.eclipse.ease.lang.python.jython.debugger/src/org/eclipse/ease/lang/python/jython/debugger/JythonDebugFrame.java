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
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.internal.resources.Workspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.ease.Script;
import org.eclipse.ease.debugging.IScriptDebugFrame;

/**
 * IScriptDebugFrame storing all necessary information from Jython
 * in Eclipse-friendly manor.
 * 
 * @author kloeschmartin
 *
 */
public class JythonDebugFrame implements IScriptDebugFrame {
	// Members to be displayed in Eclipse DebugView
	private String mName;
	private int mLineNumber;
	private Script mScript;
	private Map<String, Object> mLocals = new HashMap<String, Object>();
		
	/**
	 * Constructor stores necessary information and creates new script object
	 * from filename and linenumber.
	 * 
	 * This is necessary becaus actual debugger functionality is implemented 
	 * in edb.py
	 * 
	 * @param filename: Filename for current stack-frame
	 * @param linenumber: Linenumber of current stack-frame
	 * @param locals: map of all local variables
	 */
	public JythonDebugFrame(String filename, int linenumber, Map<String, Object> locals) {
		mLineNumber = linenumber;
		mLocals = locals;
		// Since edb.py can only handle absolute filepaths it is necessary to
		// convert locaion to path in workspace.
		String wsPath = "/" + ResourcesPlugin.getWorkspace().getRoot().getLocation().toFile().toURI().relativize(new File(filename).toURI()).getPath();
		
		mScript = new Script(new JythonFile(wsPath));
		mName = wsPath;
	}
	
	/**
	 * Overrides File class to have accessible constructor.
	 * @author kloeschmartin
	 */
	private class JythonFile extends org.eclipse.core.internal.resources.File {
		/**
		 * Public constructor only calls protected superclass constructor.
		 * @param fn
		 */
		public JythonFile(String fn) {
			super(new Path(fn), (Workspace) ResourcesPlugin.getWorkspace());
		}
	}

	// ************************************************************
	// Getter methods for necessary information
	// ************************************************************
	
	@Override
	public int getLineNumber() {
		return mLineNumber;
	}

	@Override
	public Script getScript() {
		return mScript;
	}

	@Override
	public int getType() {
		return 0;
	}

	@Override
	public String getName() {
		return mName;
	}

	@Override
	public Map<String, Object> getVariables() {
		return mLocals;
	}
}
