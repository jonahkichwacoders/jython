/*******************************************************************************
 * Copyright (c) 2013 Christian Pontesegger and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Christian Pontesegger - initial API and implementation
 *******************************************************************************/
package org.eclipse.ease.lang.python.jython;

import org.eclipse.ease.IScriptEngine;
import org.eclipse.ease.IScriptEngineLaunchExtension;

/**
 * Python loader. Loads initial environment module.
 */
public class PythonEnvironementBootStrapper implements IScriptEngineLaunchExtension {

	@Override
	public void createEngine(final IScriptEngine engine) {

		// load environment module
		final StringBuilder code = new StringBuilder("from org.eclipse.ease.modules import EnvironmentModule\n");
		code.append("EnvironmentModule().loadModule(\"/System/Environment\")\n");

		// register top level packages
		code.append("import java\n");
		code.append("import org\n");
		code.append("import com\n");

		engine.executeAsync(code);
	}
}
