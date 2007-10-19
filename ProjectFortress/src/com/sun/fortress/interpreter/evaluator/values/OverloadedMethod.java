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

package com.sun.fortress.interpreter.evaluator.values;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.sun.fortress.interpreter.env.BetterEnv;
import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.nodes.SimpleName;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.useful.BATreeEC;
import com.sun.fortress.useful.HasAt;
import com.sun.fortress.useful.Useful;
import static com.sun.fortress.interpreter.evaluator.ProgramError.error;


public class OverloadedMethod extends OverloadedFunction implements Method {

    BATreeEC<List<FValue>, List<FType>, Method> mcache =
        new BATreeEC<List<FValue>, List<FType>, Method>(FValue.asTypesList);

    
    public OverloadedMethod(String fnName, BetterEnv within) {
        super(NodeFactory.makeIdName(fnName), within);
        // TODO Auto-generated constructor stub
    }

    public OverloadedMethod(String fnName, Set<? extends Simple_fcn> ssf,
            BetterEnv within) {
        super(NodeFactory.makeIdName(fnName), ssf, within);
        // TODO Auto-generated constructor stub
    }

   static int lastBest;

   public FValue applyMethod(List<FValue> args, FObject selfValue, HasAt loc, BetterEnv envForInference) {

       Method best_f = mcache.get(args);
       if (best_f == null) {
       
        int best = bestMatchIndex(args, loc, envForInference);
        lastBest = best;

        if (best == -1) {
            // TODO add checks for COERCE, right here.
            error(loc,  within,
                  "Failed to find matching method overload, args = " +
                  Useful.listInParens(args) + ", overload = " + this);
        }

        best_f = ((Method)overloads.get(best).getFn());
        mcache.syncPut(args, best_f);
       }
       return best_f.applyMethod(args, selfValue, loc, envForInference);
    }

       public void bless() {
           overloads = pendingOverloads;
           pendingOverloads = new ArrayList<Overload>();
           super.bless();
       }


}
