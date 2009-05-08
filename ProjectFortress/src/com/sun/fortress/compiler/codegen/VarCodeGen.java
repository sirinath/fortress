/*******************************************************************************
    Copyright 2009 Sun Microsystems, Inc.,
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
package com.sun.fortress.compiler.codegen;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.MethodVisitor;

import com.sun.fortress.exceptions.CompilerError;
import com.sun.fortress.nodes.IdOrOp;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes_util.NodeFactory;
// import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.useful.Debug;

import static com.sun.fortress.exceptions.ProgramError.errorMsg;

/**
 * VarCodeGen stores necessary information about a (local) variable
 * and generates code for references to that variable.
 */
public abstract class VarCodeGen {
    public final IdOrOp name;
    public final Type fortressType;
    public final int sizeOnStack;

    public VarCodeGen(IdOrOp name, Type fortressType) {
        super();
        this.name = name;
        this.fortressType = fortressType;
        // TODO: compute sizeOnStack correctly for non-pointer types.
        this.sizeOnStack = 1;
    }

    /** Generate code to push the value of this variable onto the Java stack.
     */
    public abstract void pushValue(MethodVisitor mv);

    /** Generate code to assign the value of this variable from the
     *  top of the Java stack. */
    public abstract void assignValue(MethodVisitor mv);

    /** Generate metadata after last reference to this variable. */
    public abstract void outOfScope(MethodVisitor mv);

    /************************************************************
     * Specific kinds of Variables.
     ************************************************************/

    private static abstract class StackVar extends VarCodeGen {
        protected final int offset;

        protected StackVar(IdOrOp name, Type fortressType,
                           int offset) {
            super(name, fortressType);
            this.offset = offset;
        }

        public void pushValue(MethodVisitor mv) {
            mv.visitVarInsn(Opcodes.ALOAD, offset);
        }

        public void assignValue(MethodVisitor mv) {
            mv.visitVarInsn(Opcodes.ASTORE, offset);
        }
    }

    /** Function parameter.  Since function parameters are immutable
     * in Fortress, we assume that we won't need other special
     * provisions for them (we'll simply copy references where
     * necessary).
     */
    public static class ParamVar extends StackVar {

        public ParamVar(IdOrOp name, Type fortressType,
                        int offset) {
            super(name, fortressType, offset);
        }

        public void assignValue(MethodVisitor mv) {
            throw new CompilerError(errorMsg("Invalid assignment to ",name,
                                             ": ", fortressType,
                                             " param ",offset,
                                             " size ", sizeOnStack));
        }

        public void outOfScope(MethodVisitor mv) {
            // Nothing to do?
        }
    }

    /** Self parameter for dotted methods. */
    public static class SelfVar extends ParamVar {
        public SelfVar(Span s, Type fortressType) {
            super(NodeFactory.makeId(s,"self"), fortressType, 0);
        }
    }

    /** Local variable not visible outside current activation.  We
     *  don't presently distinguish mutable and immutable locals;
     *  perhaps we should.  A LocalVar doesn't include any variable
     *  that might scope over a local lambda, local object
     *  declaration, across work items, etc.  Those will require
     *  separate subclasses, which is one of the reasons VarCodeGen is
     *  structured this way in the first place.
     */
    public static class LocalVar extends StackVar {
        final Label start;

        public LocalVar(IdOrOp name, Type fortressType,
                        int offset, MethodVisitor mv) {
            super(name, fortressType, offset);
            this.start = new Label();
            mv.visitLabel(start);
        }

        public void outOfScope(MethodVisitor mv) {
            Label finish = new Label();
            // call mv.visitLocalVariable here.
        }
    }
}