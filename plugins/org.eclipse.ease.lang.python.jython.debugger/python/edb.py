'''
Copyright (c) 2014 Martin Kloesch
All rights reserved. This program and the accompanying materials
are made available under the terms of the Eclipse Public License v1.0
which accompanies this distribution, and is available at
http://www.eclipse.org/legal/epl-v10.html

Contributors:
 * Martin Kloesch - initial API and implementation
 * Christian Pontesegger - stripped most parts to simply trace and relay to java
'''
# Python std library imports
import os
import sys
import __main__

class CodeTracer:
    '''
    Eclipse Debugger class.
    '''
    _debugger = None

    def __init__(self):
        '''
        Default Constructor.
        '''

    def set_debugger(self, debugger):
        '''
        Setter method for self._debugger.
        
        :param org.eclipse.ease.lang.python.jython.debugger.JythonDebugger debugger:
            JythonDebugger object to handling communication with Eclipse.
        '''
        self._debugger = debugger
        sys.settrace(self.trace_dispatch)         
    
    def trace_dispatch(self, frame, event, arg):
        self._debugger.traceDispatch(frame, event)
        return self.trace_dispatch
                
    def run(self, script, filename):
        '''
        Executes the file given using the bdb.Bdb.run method.
        '''
        compiledCode = compile(script.getCode() + "\n", filename, "exec")

        globals = __main__.__dict__
        locals = __main__.__dict__
        exec compiledCode in globals, locals


eclipse_jython_debugger = CodeTracer()
