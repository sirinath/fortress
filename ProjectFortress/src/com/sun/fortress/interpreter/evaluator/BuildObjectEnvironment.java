/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
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

package com.sun.fortress.interpreter.evaluator;

import java.util.Set;

import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.interpreter.evaluator.values.GenericMethod;
import com.sun.fortress.interpreter.evaluator.values.MethodClosure;
import com.sun.fortress.interpreter.evaluator.values.Simple_fcn;
import com.sun.fortress.nodes_util.Applicable;
import com.sun.fortress.nodes.FnDecl;


public class BuildObjectEnvironment extends BuildTraitEnvironment {


    public BuildObjectEnvironment(Environment within, Environment methodEnvironment, FType definer, Set<String> fields) {
        super(within, methodEnvironment, definer, fields);
        // TODO Auto-generated constructor stub
    }

    protected Simple_fcn newClosure(Environment e, Applicable x) {
        return new MethodClosure(containing,x, definer);
    }

    protected GenericMethod newGenericClosure(Environment e, FnDecl x) {
        return new GenericMethod(containing, e, x, definer, false);
    }



}
