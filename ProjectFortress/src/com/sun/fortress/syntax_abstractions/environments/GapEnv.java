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

package com.sun.fortress.syntax_abstractions.environments;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.LinkedList;

import com.sun.fortress.exceptions.MacroError;

import com.sun.fortress.nodes.AnyCharacterSymbol;
import com.sun.fortress.nodes.BackspaceSymbol;
import com.sun.fortress.nodes.BaseType;
import com.sun.fortress.nodes.CarriageReturnSymbol;
import com.sun.fortress.nodes.CharacterClassSymbol;
import com.sun.fortress.nodes.FormfeedSymbol;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.KeywordSymbol;
import com.sun.fortress.nodes.NewlineSymbol;
import com.sun.fortress.nodes.NodeDepthFirstVisitor_void;
import com.sun.fortress.nodes.NonterminalSymbol;
import com.sun.fortress.nodes.OptionalSymbol;
import com.sun.fortress.nodes.PrefixedSymbol;
import com.sun.fortress.nodes.RepeatOneOrMoreSymbol;
import com.sun.fortress.nodes.RepeatSymbol;
import com.sun.fortress.nodes.SyntaxDef;
import com.sun.fortress.nodes.SyntaxSymbol;
import com.sun.fortress.nodes.TabSymbol;
import com.sun.fortress.nodes.TokenSymbol;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes.WhitespaceSymbol;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.useful.Debug;
import edu.rice.cs.plt.tuple.Option;

public class GapEnv {

    private final NTEnv ntEnv;
    private final Map<Id, Depth> varToDepth;
    private final Map<Id, Id> varToNT;
    private final Set<Id> stringVars;

    protected GapEnv(NTEnv ntEnv, Map<Id, Depth> varToDepth, 
                     Map<Id, Id> varToNT, Set<Id> stringVars) {
        this.ntEnv = ntEnv;
        this.varToDepth = varToDepth;
        this.varToNT = varToNT;
        this.stringVars = stringVars;
    }

    public NTEnv getNTEnv() {
        return ntEnv;
    }

    public boolean isGap(Id var) {
        return varToDepth.containsKey(var);
    }

    public Collection<Id> gaps() {
        return varToDepth.keySet();
    }

    public Depth getDepth(Id var) {
        return varToDepth.get(var);
    }

    /* Nonterminals */

    public boolean hasNonterminal(Id var) {
        return varToNT.containsKey(var);
    }

    public Id getNonterminal(Id var) {
        Id nt = varToNT.get(var);
        if (nt == null) {
            throw new RuntimeException("Not bound to a nonterminal: " + var);
        } else {
            return nt;
        }
    }

    /* Types */

    /** id must be bound to a nonterminal
     * the type does not take depth into account
     * (that is, e:Expr* => e has type Expr, not List[\Expr\]
     */
    public BaseType getAstType(Id var) {
        Id nt = varToNT.get(var);
        if (nt != null) {
            return ntEnv.getType(nt);
        } else {
            throw new RuntimeException("Not a gap name bound to a nonterminal: " + var);
        }
    }

    public String getJavaType(Id var) {
        Id nt = varToNT.get(var);
        if (nt != null) {
            return ntEnv.getJavaType(nt);
        } else if (hasJavaStringType(var)) {
            return "String";
        } else {
            throw new RuntimeException("Not a gap name: " + var);
        }
    }

    public boolean hasJavaStringType(Id id) {
        return stringVars.contains(id);
    }

    public String toString() {
        return varToNT.toString() + "::" + varToDepth.toString();
    }
}
