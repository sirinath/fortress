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

/*
 * Fortress trait headers.
 * Fortress AST node local to the Rats! com.sun.fortress.interpreter.parser.
 */
package com.sun.fortress.parser_util;

import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.Param;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.TraitTypeWhere;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.useful.MagicNumbers;
import edu.rice.cs.plt.tuple.Option;

import java.util.List;

public class TraitHeader {

    private Id name;
    private List<StaticParam> staticParams;
    private Option<List<Param>> params;
    private List<TraitTypeWhere> extendsClause;

    public TraitHeader(Id name, List<StaticParam> staticParams,
                       Option<List<Param>> params,
                       List<TraitTypeWhere> extendsClause) {
        this.name = name;
        this.staticParams = staticParams;
        this.params = params;
        this.extendsClause = extendsClause;
    }

    public Id getName() {
        return name;
    }

    public List<StaticParam> getStaticParams() {
        return staticParams;
    }

    public Option<List<Param>> getParams() {
        return params;
    }

    public List<TraitTypeWhere> getExtendsClause() {
        return extendsClause;
    }

    public int hashCode() {
        return name.hashCode() * MagicNumbers.n + MagicNumbers.hashList(staticParams, MagicNumbers.e) +
               params.hashCode() * MagicNumbers.a +
               MagicNumbers.hashList(extendsClause, MagicNumbers.l);
    }

    public boolean equals(Object o) {
        if (o == null) return false;
        if (o.getClass().equals(this.getClass())) {
            TraitHeader th = (TraitHeader) o;
            return name.equals(th.getName()) && staticParams.equals(th.getStaticParams()) &&
                   params.equals(th.getParams()) &&
                   extendsClause.equals(th.getExtendsClause());
        }
        return false;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("trait ");
        sb.append(NodeUtil.nameString(name));
        return sb.toString();
    }
}
