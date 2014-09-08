'''
Copyright (c) 2014 Martin Kloesch
All rights reserved. This program and the accompanying materials
are made available under the terms of the Eclipse Public License v1.0
which accompanies this distribution, and is available at
http://www.eclipse.org/legal/epl-v10.html

Contributors:
 * Martin Kloesch - initial implementation
'''

_ImportErrorString = "No module name {}"
def _is_javapackage(module):
    return repr(type(module)) == "<type 'javapackage'>"

from edb import Edb
if _is_javapackage(Edb): raise ImportError(_ImportErrorString.format('edb'))

eclipse_jython_debugger = Edb()
