/*******************************************************************************
    Copyright 2007 Sun Microsystems, Inc.,
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

package com.sun.fortress.interpreter.evaluator.types;

import java.util.ArrayList;
import java.util.List;

import com.sun.fortress.interpreter.env.BetterEnv;
import com.sun.fortress.interpreter.evaluator.InterpreterBug;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.nodes.AbsDeclOrDecl;
import com.sun.fortress.useful.NI;


abstract public class SymbolicType extends FTypeTrait {

    /* (non-Javadoc)
     * @see com.sun.fortress.interpreter.evaluator.types.FType#typeMatch(com.sun.fortress.interpreter.evaluator.values.FValue)
     */
    @Override
    public boolean typeMatch(FValue val) {
        NI.nyi("Symbolic types never do this, do they?");
        return super.typeMatch(val);
    }

    public SymbolicType(String name, BetterEnv interior, List<? extends AbsDeclOrDecl> members) {
        super(name, interior, interior.getAt(), members);
        membersInitialized = true;
    }

    public void addExtend(FType t) {
        if (transitiveExtends != null)
            throw new InterpreterBug("Extending type added after transitive extends probed.");

        if (extends_ == null)
            extends_ = new ArrayList<FType>();

        extends_.add(t);
    }

    public void addExtends(List<FType> t) {
        if (transitiveExtends != null)
            throw new InterpreterBug("Extending type added after transitive extends probed.");
        extends_.addAll(t);
    }

}
