/*******************************************************************************
    Copyright 2010 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
******************************************************************************/
package com.sun.fortress.compiler.asmbytecodeoptimizer;

import org.objectweb.asm.*;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.util.*;

public class FieldInsn extends Insn {
    int opcode;
    String owner;
    String _name;
    String desc;

    FieldInsn(String name, int opcode, String owner, String _name, String desc) {
        this.name = name;
        this.opcode = opcode;
        this.owner = owner;
        this._name = _name;
        this.desc = desc;
    }

    public String toString() { 
        return "FieldInsn:" + name;
    }
    
    public void toAsm(MethodVisitor mv) { 
        mv.visitFieldInsn(opcode, owner, _name, desc);
    }
}