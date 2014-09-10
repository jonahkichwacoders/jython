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
    
    # : Flag to signalize if debugger should suspend on startup
    _suspend_on_startup = False
    
    # : Flag to signalize if debugger should suspend when new script is loaded.
    # : Not in use yet
    _suspend_on_script_load = False

    def __init__(self, breakpoints=[]):
        '''
        Constructor calls base class's constructor and
        sets up necessary members.
        '''
        bdb.Bdb.__init__(self)
        self._continue_event = threading.Event()
         
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

    def set_suspend_on_startup(self, suspend):
        '''
        Setter method for suspend_on_startup flag.
        
        Since the actual object creation is handled by setup.py we need to set 
        the object here.
    
        :param bool suspend:
            Value for _suspend_on_startup to be set.
        '''
        self._suspend_on_startup = suspend
        
    def set_suspend_on_script_load(self, suspend):
        '''
        Setter method for suspend_on_script_load flag.
        
        Since the actual object creation is handled by setup.py we need to set 
        the object here.
    
        :param bool suspend:
            Value for _suspend_on_script_load to be set.
        '''
        self._suspend_on_script_load = suspend

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

    def dispatch_call(self, frame, arg):
        '''
        Method called before each function call in debugged program.
        
        Only checks if new file is being used and updates breakpoints accordingly.
        '''
        fn = frame.f_code.co_filename
        
        # Check if file has changed
        if fn != self._current_file:
            # In case of file change wait for JythonDebugger to set new breakpoints.
            if self._current_file and os.path.exists(self._current_file):
                self._debugger.checkBreakpoints(fn);
            
            # TODO: Check if locking would interfere with performance
            self._current_file = fn
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
        filename = frame.f_code.co_filename
        
        # Linenumber < 1 means this is the first call (<string> 0)
        if frame.f_lineno < 1:
            return
         
        # Safe bdb.Frame object to member
        # Lock since this can be accessed by several threads
        with self._frame_lock:
            self._current_frame = frame

        # Simple sulution to handle suspend on startup
        if self._first:
            self._first = False
            if not self._suspend_on_startup:
                self.set_continue()
                return
        
        # Call break function that notifies JythonDebugger and suspends execution
        self._break()
        
        # If we are here everything necessary was handled
        self._continue()

    def _break(self):
        '''
        Function called when Debugger stops (breakpoint or step command).
        
        Calls JythonDebugger to send event to Eclipse and waits for user input.
        '''
        # Use suspend in JythonDebugger. 
        # Would also be possible to directly raise new SuspendedEvent
        self._debugger.fireSuspendEvent(self._get_stack_trace())
        
        # Wait for continuation from Eclipse
        self._continue_event.wait()
        self._continue_event.clear()

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
            if not os.path.exists(filename):
                break
            
            # Convert from JythonDictionary to Java.util.HashMap
            java_locals = java.util.HashMap()
            for key, val in frame.f_locals.items():
                java_locals.put(key, val)
                
            # Append frame to stack
            stack.append(org.eclipse.ease.lang.python.jython.debugger.JythonDebugFrame(filename, lineno, java_locals))
            
        return stack

    def _continue(self):
        '''
        Function called when Debugger is about to continue (step or resume).
        '''
        self._debugger.fireResumedEvent(java.lang.Thread.currentThread(), self._resume_event_type)
        
        # TODO: think if file should be locked
        # Probably not necessary because communication with JythonDebugger is synchronous
        self._debugger.checkBreakpoints(self._current_file)
        
        # Double checked locking to assure thread safety
        if self._step_func:
            with self._step_lock:
                if self._step_func:
                    # Since in Python everything is an object, this works
                    self._step_func(*(self._step_param or []))
                self._step_func = self._step_param = None
                
        # Reset resume event. Integers are thread-safe by default
        self._resume_event_type = -1
             
    def _continue_wrapper(func):
        '''
        Decorator calls function and sets the _continue_event and 
        assures thread-safety of member _step_func.
         
        This can be used for all execution handling methods like:
         
          * continue
          * step over
          * step into
          * quit
        '''
        def wrapped(self, *args, **kwargs):
            with self._step_lock:
                func(self, *args, **kwargs)
            self._continue_event.set()
        return wrapped
            
    @_continue_wrapper
    def step_continue(self):
        '''
        Simply stores self.set_continue method to member.
        Thread safety assured by _continue_wrapper.
        '''
        self._step_func = self.set_continue
        self._resume_event_type = 0x20
     
    @_continue_wrapper
    def step_stepover(self):
        '''
        Simply stores self.set_step method to member.
        Thread safety assured by _continue_wrapper.
        '''
        self._step_func = self.set_until
        self._step_param = [self._current_frame]
        self._resume_event_type = org.eclipse.debug.core.DebugEvent.STEP_OVER
 
    @_continue_wrapper
    def step_stepinto(self):
        '''
        Simply stores self.set_next method to member.
        Thread safety assured by _continue_wrapper.
        '''
        self._step_func = self.set_step
        self._resume_event_type = org.eclipse.debug.core.DebugEvent.STEP_INTO
 
    @_continue_wrapper
    def step_stepout(self):
        '''
        Simply stores self.set_next method to member.
        Thread safety assured by _continue_wrapper.
        '''
        self._step_func = self.set_return
        self._step_param = [self._current_frame]
        self._resume_event_type = org.eclipse.debug.core.DebugEvent.STEP_RETURN
 
    @_continue_wrapper
    def step_quit(self):
        '''
        Simply stores self.set_continue method to member.
        Thread safety assured by _continue_wrapper.
        '''
        self._step_func = self.set_quit
             
    def get_var(self, var):
        '''
        Thread-safe getter for local variables.
         
        Uses double checked locking to acquire variable by name.
         
        :note:  local variables have priority.
                if no local variable found, tries to get global.
        :param var: variable name as string
        :returns: * **Value of variable** if successful.
                  * **None** does not return if not at breakpoint
        '''
        # FIXME: not used yet <kmh>
        if self._current_frame:
            with self._frame_lock:
                if self._current_frame:
                    return self._current_frame.f_locals.get(var) or \
                           self._current_frame.f_globals.get(var)
         
    def list_vars(self):
        '''
        Thread-safe getter for map of local variables.
         
        Uses double checked locking to get all locals.
         
        :returns: * **Map<variable_name, variable_value**
                    returns map of all local variables if successful
                  * **None** if not at breakpoint
        '''
        # FIXME: not used yet <kmh>
        if self._current_frame:
            with self._frame_lock:
                if self._current_frame:
                    as_java = HashMap()
                    for key, val in self._current_frame.f_locals.items():
                        as_java.put(key, val)
                    return as_java
                    return self._current_frame.f_locals
             
    def set_var(self, var, val):
        '''
        Thread-safe setter for variables.
         
        Uses double checked locking to set variable by name.
         
        Checks if local variable can be updated, otherwise global will be set.
         
        :param var: variable name as string.
        :param val: value to be set.
        :returns: * **True** if successful
                  * **False** in case of error
        '''
        # FIXME: not used yet <kmh>
        if self._current_frame:
            with self._frame_lock:
                if self._current_frame:
                    if self._current_frame.f_locals.get(var):
                        self._current_frame.f_locals.update({var:val})
                    else:
                        self._current_frame.f_globals.update({var:val})
                    return True
        return False
             
    def run(self, file_to_run):
        '''
        Executes the file given using the bdb.Bdb.run method.
         
        :raises ValueError: if empty filename given.
        :raises IOError: if file does not exist.
        '''
        if not file_to_run:
            raise ValueError("filename for run must not be empty")
        if not os.path.exists(file_to_run):
            raise IOError("file {} does not exist".format(file_to_run))
                
        self._first = True
        
        cmd = 'execfile({})'.format(repr(file_to_run))
        bdb.Bdb.run(self, cmd)
        self._debugger = None
        bdb.Bdb.__init__(self, None)


eclipse_jython_debugger = Edb()
