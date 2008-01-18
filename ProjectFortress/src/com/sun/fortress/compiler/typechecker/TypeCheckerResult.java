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

package com.sun.fortress.compiler.typechecker;

import java.util.*;
import com.sun.fortress.compiler.*;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.nodes.*;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.tuple.Option;

public class TypeCheckerResult extends StaticPhaseResult {
    private final Node ast;
    private final Option<Type> type;
    
    private static Iterable<? extends StaticError> collectErrors(Iterable<? extends TypeCheckerResult> results) {
        Iterable<? extends StaticError> allErrors = new ArrayList<StaticError>();
        
        for (TypeCheckerResult result: results) {
            allErrors = IterUtil.compose(allErrors, result.errors());
        }
        return allErrors;
    }
    
    public static TypeCheckerResult compose(Node _ast,
                                            TypeCheckerResult... results) {
        return new TypeCheckerResult(_ast,
                                     Option.<Type>none(),
                                     collectErrors(Arrays.asList(results)));
    }
    
    public static TypeCheckerResult compose(Node _ast, List<TypeCheckerResult> results) {
        return new TypeCheckerResult(_ast,
                                     Option.<Type>none(),
                                     collectErrors(results));
    }       
    
    public static TypeCheckerResult compose(Node _ast, Option<List<TypeCheckerResult>> results) {
        if (results.isSome()) {
            Iterable<? extends StaticError> allErrors = collectErrors(Option.unwrap(results));
            
            return new TypeCheckerResult(_ast,
                                         Option.<Type>none(),
                                         allErrors);
        } else {
            return new TypeCheckerResult(_ast);
        }
    }
    
    public TypeCheckerResult(Node _ast, Type _type,
                             Iterable<? extends StaticError> _errors) {
        super(_errors);
        ast = _ast;
        type = Option.wrap(_type);
    }
    
    public TypeCheckerResult(Node _ast, 
                             Iterable<? extends StaticError> _errors) {
        super(_errors);
        ast = _ast;
        type = Option.none();
    }
                           
    public TypeCheckerResult(Node _ast) {
        super();
        ast = _ast;
        type = Option.none();
    }
    
    public TypeCheckerResult(Node _ast, Type _type) {
        super();
        ast = _ast;
        type = Option.wrap(_type);
    }
    
    public TypeCheckerResult(Node _ast, Option<Type> _type) {
        super();
        ast = _ast;
        type = _type;
    }
    
    public TypeCheckerResult(Node _ast, Option<Type> _type, Iterable<? extends StaticError> _errors) {
        super(_errors);
        ast = _ast;
        type = _type;
    }
    
    public TypeCheckerResult(Node _ast, StaticError _error) {
        super(IterUtil.make(_error));
        ast = _ast;
        type = Option.none();
    }
    
    public Node ast() { return ast; }
    public Option<Type> type() { return type; }
}
