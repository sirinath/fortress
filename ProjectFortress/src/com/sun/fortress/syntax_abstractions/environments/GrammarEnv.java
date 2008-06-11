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

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import com.sun.fortress.nodes.Id;
import com.sun.fortress.useful.Debug;

/**
 * Contains a map from fully qualified nonterminal or terminal
 * names to their corresponding environment. 
 */
public class GrammarEnv {

    private static Map<Id, MemberEnv> members = new HashMap<Id, MemberEnv>();

    /**
     * Add name of terminal or nonterminal and its corresponding member environment
     * @param id
     * @param memberEnv
     */
    public static void add(Id id, MemberEnv memberEnv) {
        GrammarEnv.members.put(id, memberEnv);
    }

    /**
     * Returns true if the given name is contained in this environment
     * @param name
     * @return
     */
    public static boolean contains(Id name) {
        return GrammarEnv.members.containsKey(name);
    }

    /**
     * Returns the member environment associated with the given name 
     * @param name
     * @return
     */
    public static MemberEnv getMemberEnv(Id name) {
        return GrammarEnv.members.get(name);
    }

    private static String cutPackage(String name){
        int last = name.lastIndexOf('.');
        if ( last != -1 ){
            return name.substring( last + 1 );
        }
        return name;
    }

    public static String getType(Id name){
        Debug.debug( 4, "Looking up " + name.getText() + " in grammar env" );
        if ( name == null  || GrammarEnv.members.get(name) == null ){
            Debug.debug( 4, "Didn't find it.." );
            return "StringLiteralExpr";
        }
        String s = cutPackage(GrammarEnv.members.get(name).getType().toString());
        Debug.debug( 4, "Found " + s );
        return s;
    }

    /**
     * Dump the content of this environment as a string
     * @return
     */
    public static String getDump() {
        String s = "GrammarEnv: \n";
        for (Id id: GrammarEnv.members.keySet()) {
            s += " - "+id.toString()+"\n";
            s += " - "+GrammarEnv.members.get(id).toString()+"\n";
        }
        return s;
    }

    /**
     * Empty this environment
     */
    public static void clear() {
        GrammarEnv.members.clear();
    }
}
