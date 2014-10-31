'''
Copyright (c) 2014 Martin Kloesch
All rights reserved. This program and the accompanying materials
are made available under the terms of the Eclipse Public License v1.0
which accompanies this distribution, and is available at
http://www.eclipse.org/legal/epl-v10.html

Contributors:
 * Martin Kloesch - initial API and implementation
'''
# Python std library imports
import bdb
import threading
import os
import re

# Eclipse imports for communication with framework  
import org.eclipse.ease.debug.core
import org.eclipse.ease.lang.python.jython.debugger

# Java imports to easily cast objects
import java.lang
import java.util

         
class Edb(bdb.Bdb):
    '''
    Eclipse Debugger class.
     
    Inherits from bdb.Bdb and threading.Thread
     
    Used to have safe cross-thread debugging functionality
    '''
    # : member storing current frame object while breakpoint hit.
    # : :note: This member is accessed by several threads, always use
    # :        _frame_lock threading.Lock object to assure thread-safety.
    _current_frame = None
    _current_file = None
     
    # : member storing "step" function to be called after breakpoint.
    # : :note: Once again, this member is used by multiple threads.
    # :        use _step_lock threading.Lock object to assure thread safety.
    _step_func = None
    _step_param = None
    
    # : Flag to signal if debugger should suspend in dynamic code blocks.
    _showDynamic = False
    
    _resumeType = 0

    def __init__(self, breakpoints=[]):
        '''
        Constructor calls base class's constructor and
        sets up necessary members.
        '''
        bdb.Bdb.__init__(self)
         
        # RLocks can be acquired multiple times by same thread.
        # Should actually not make a difference but better safe than sorry
        self._frame_lock = threading.RLock()
        self._step_lock = threading.RLock()

    def set_debugger(self, debugger):
        '''
        Setter method for self._debugger.
        
        Since the actual object creation is handled by setup.py we need to set 
        the object here.
        
        :param org.eclipse.ease.lang.python.jython.debugger.JythonDebugger debugger:
            JythonDebugger object to handling communication with Eclipse.
        '''
        self._debugger = debugger
    
    def set_show_dynamic_code(self, showDynamic):
        '''
        Setter method for suspend_on_script_load flag.
        
        Since the actual object creation is handled by setup.py we need to set 
        the object here.
    
        :param bool suspend:
            Value for _suspend_on_script_load to be set.
        '''
        self._showDynamic = showDynamic

    def set_break(self, breakpoint):
        '''
        Sets a new breakpoint with the given BreakpointInfo.
        If a breakpoint already exists old breakpoint will be deleted.
     
        Overrides bdb.Bdb to use EASE BreakpointInfo class.
         
        :param org.eclipse.ease.lang.python.jython.debugger.BreakpointInfo breakpoint:
            BreakpointInfo object containing all necessary information.
        '''
        # Parse BreakpointInfo to named variables for easier understanding
        filename = breakpoint.getFilename()
        lineno = breakpoint.getLinenumber()
        temporary = breakpoint.getTemporary()
        cond = breakpoint.getCondition()
        hitcount = breakpoint.getHitcount()
        funcname = None
        
        # Just to be sure delete old breakpoint
        self.clear_break(filename, lineno)
        
        # Set breakpoint with parsed information
        bdb.Bdb.set_break(self, filename, lineno, temporary, cond, funcname)
        
        # bdb.Breakpoints do not have hitcount parameter in constructor so set it here
        if hitcount:
            self.get_break(filename, lineno).ignore = hitcount
 
    def update_break(self, breakpoint):
        '''
        Only wraps to set_break.
        Necessary to have definition because it overrides bdb.Bdb.update_break
         
        :param org.eclipse.ease.lang.python.jython.debugger.BreakpointInfo breakpoint:
            BreakpointInfo object containing all necessary information.
        '''
        self.set_break(breakpoint)

    def resume(self, type):
        '''
        Set type of last resume.
        
        Needed to know when to break next.
        '''
        self._resumeType = type
 
    def dispatch_call(self, frame, arg):
        '''
        Method called before each function call in debugged program.
        
        Only checks if new file is being used and updates breakpoints accordingly.
        '''
        #fn = frame.f_code.co_filename
        self._debugger.callback("dispatch_call: " + str(frame.f_code))
        
        
        # Check if file has changed
       # if fn != self._current_file:
            # In case of file change wait for JythonDebugger to set new breakpoints.
          #  if self._current_file and os.path.exists(self._current_file):
           #     self._debugger.checkBreakpoints(fn);
            
            # TODO: Check if locking would interfere with performance
            #self._current_file = fn
        return bdb.Bdb.dispatch_call(self, frame, arg)
 
    def user_line(self, frame):
        '''
        This method is called when debugger stops or breaks at line.
         
        Overrides bdb.Bdb.user_line method.
         
        Stores information about frame in member then
        waits for input from other thread.
         
        Thread-safe.
         
        :param frame: bdb.Frame object storing information about current line.
        '''
        # filename = frame.f_code.co_filename
        self._debugger.callback("user_line: " + str(frame.f_lineno))
        
        # Linenumber < 1 means this is the first call (<string> 0)
        if frame.f_lineno < 1:
            return
         
        # Safe bdb.Frame object to member
        # Lock since this can be accessed by several threads
        #with self._frame_lock:
        #    self._current_frame = frame

        # Simple sulution to handle suspend on startup
        #if self._first:
        #    self._first = False
        #    if not self._suspend_on_startup:
        #        self.set_continue()
        #        return
        
        # Call break function that notifies JythonDebugger and suspends execution
        #self._break()
        
        # If we are here everything necessary was handled
        #self._continue()

    def _break(self):
        '''
        Function called when Debugger stops (breakpoint or step command).
        
        Calls JythonDebugger to send event to Eclipse and waits for user input.
        '''
        # Use suspend in JythonDebugger. 
        # Would also be possible to directly raise new SuspendedEvent
        self._debugger.fireSuspendEvent(self._get_stack_trace())
        
    def _get_stack_trace(self):
        '''
        Helper method returning current stack as List<JythonDebugFrame>.
        '''
        # Get stack in bdb.Bdb format
        bdb_stack, _ = self.get_stack(self._current_frame, None)
        stack = []
        
        # Convert stack to JythonDebugFrames
        for stack_entry in reversed(bdb_stack):
            frame, lineno = stack_entry
            filename = frame.f_code.co_filename
            
            # If file does not exist we can assume that it is a builtin and can be skipped.
            # This also means we are already down the stack and can abort.            
            if not os.path.exists(filename) and not re.match(r"^__hash_[0-9a-fA-F]{20}", filename):
                break
            
            # Convert from JythonDictionary to Java.util.HashMap
            java_locals = java.util.HashMap()
            for key, val in frame.f_locals.items():
                java_locals.put(key, val)
                
            # Append frame to stack
            stack.append(org.eclipse.ease.lang.python.jython.debugger.JythonDebugFrame(filename, lineno, java_locals))
            
        return stack

    def run(self, code, filename):
        '''
        Executes the file given using the bdb.Bdb.run method.
        '''

        self._debugger.callback("run " + filename)
        compiledCode = compile(code, filename, "exec")
        bdb.Bdb.run(self, compiledCode)


eclipse_jython_debugger = Edb()
