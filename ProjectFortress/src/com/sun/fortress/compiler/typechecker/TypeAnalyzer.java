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
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;
import edu.rice.cs.plt.tuple.Triple;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.collect.Relation;
import edu.rice.cs.plt.collect.HashRelation;
import edu.rice.cs.plt.lambda.Lambda;

import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.compiler.GlobalEnvironment;
import com.sun.fortress.compiler.index.*;

import static edu.rice.cs.plt.debug.DebugUtil.debug;


public class TypeAnalyzer {
    private static final boolean SIMPLIFIED_SUBTYPING = true;

    public static final Type BOTTOM = new BottomType();
    public static final Type ANY = NodeFactory.makeInstantiatedType("FortressBuiltin", "Any");
    public static final Type OBJECT = NodeFactory.makeInstantiatedType("FortressLibrary", "Object");
    public static final Type TUPLE = NodeFactory.makeInstantiatedType("FortressBuiltin", "Tuple");

    private static final int MAX_SUBTYPE_DEPTH = 4;

    private static final Option<List<Type>> THROWS_BOTTOM =
        Option.some(Collections.singletonList(BOTTOM));

    private final TraitTable _table;
    private final SubtypeCache _cache;
    private final SubtypeHistory _emptyHistory;

    public TypeAnalyzer(TraitTable table) {
        _table = table;
        _cache = new SubtypeCache();
        _emptyHistory = new SubtypeHistory();
    }
    
    /**
     * Convert the type to a normal form.  A normalized type has the following properties:
     * <ul>
     * <li>The throws clause of all arrow types is a singleton list.
     * </ul>
     */
    public Type normalize(Type t) {
        return (Type) t.accept(new NodeUpdateVisitor() {
            
            public Node forArrowTypeOnly(ArrowType t, Type newDomain,
                                         Type newRange, Option<List<Type>> newThrows) {
                // fix newThrows so that it is a singleton list
                if (newThrows.isNone()) { newThrows = THROWS_BOTTOM; }
                else {
                    List<Type> throwsList = Option.unwrap(newThrows);
                    if (throwsList.isEmpty()) { newThrows = THROWS_BOTTOM; }
                    else if (throwsList.size() > 1) {
                        Type union = null;
                        for (Type elt : throwsList) {
                            if (union == null) { union = elt; }
                            else { union = new OrType(union, elt); }
                        }
                        newThrows = Option.some(Collections.singletonList(union));
                    }
                }
                if (t.getDomain() == newDomain && t.getRange() == newRange &&
                    t.getThrowsClause() == newThrows) {
                    return t;
                }
                else {
                    return new ArrowType(t.getSpan(), t.isParenthesized(), newDomain,
                                         newRange, newThrows, t.isIo());
                }
            }
        
        });
    }
    
    /**
     * Produce a formula that, if satisfied, will support s as a subtype of t.
     * Assumes s and t are normalized.
     */
    public ConstraintFormula subtype(Type s, Type t) {
        //return ConstraintFormula.TRUE; 
        return SIMPLIFIED_SUBTYPING ? sub(s, t, _emptyHistory) : subtype(s, t, _emptyHistory);
    }
    
    private ConstraintFormula sub(final Type s, final Type t, SubtypeHistory history) {
        debug.logStart(new String[]{"s", "t"}, s, t);
        try {
        if (_cache.contains(s, t)) { return _cache.value(s, t); }
        else if (history.contains(s, t)) { return ConstraintFormula.FALSE; }
        else {
            final SubtypeHistory h = history.extend(s, t);
            ConstraintFormula result = ConstraintFormula.FALSE;
            
            if (s instanceof BottomType) { result = result.or(ConstraintFormula.TRUE, h); }
            
            if (!result.isTrue()) {
                ConstraintFormula tResult = t.accept(new NodeAbstractVisitor<ConstraintFormula>() {
                    
                    @Override public ConstraintFormula forType(Type t) {
                        return ConstraintFormula.FALSE;
                    }
                    
                    @Override public ConstraintFormula forInferenceVarType(InferenceVarType t) {
                        if (s instanceof InferenceVarType && s.equals(t)) {
                            return ConstraintFormula.TRUE;
                        }
                        else {
                            return ConstraintFormula.lowerBound(t, s, h);
                        }
                    }
                    
                    @Override public ConstraintFormula forIdType(IdType t) {
                        if (s.equals(t)) { return ConstraintFormula.TRUE; }
                        else if (s instanceof OrType) {
                            return ConstraintFormula.FALSE;
                        }
                        else {
                            // TODO: recur on lower bounds of variables
                            return ConstraintFormula.FALSE;
                        }
                    }
                    
                    @Override public ConstraintFormula forInstantiatedType(InstantiatedType t) {
                        ConstraintFormula result;
                        if (s instanceof InstantiatedType && ((InstantiatedType) s).getName().equals(t.getName())) {
                            return equiv(((InstantiatedType) s).getArgs(), t.getArgs(), h);
                        }
                        else {
                            TypeConsIndex index = _table.typeCons(t.getName());
                            if (index instanceof TypeAliasIndex) {
                                TypeAliasIndex aliasIndex = (TypeAliasIndex) index;
                                Lambda<Type, Type> subst = makeSubstitution(aliasIndex.staticParameters(),
                                                                            t.getArgs());
                                return sub(s, subst.value(aliasIndex.type()), h);
                            }
                            else { return ConstraintFormula.FALSE; }
                        }
                    }
                    
                    @Override public ConstraintFormula forOrType(OrType t) {
                        if (s instanceof OrType || s instanceof AndType) {
                            return ConstraintFormula.FALSE;
                        }
                        else {
                            return sub(s, t.getFirst(), h).or(sub(s, t.getSecond(), h), h);
                        }
                    }
                    
                    @Override public ConstraintFormula forAndType(AndType t) {
                        return sub(s, t.getFirst(), h).and(sub(s, t.getSecond(), h), h);
                    }
                    
                });
                result = result.or(tResult, h);
            }
            
            if (!result.isTrue()) {
                                      
                ConstraintFormula sResult = s.accept(new NodeAbstractVisitor<ConstraintFormula>() {
                    
                    @Override public ConstraintFormula forType(Type s) {
                        return ConstraintFormula.FALSE;
                    }
                    
                    @Override public ConstraintFormula forInferenceVarType(InferenceVarType s) {
                        return ConstraintFormula.upperBound(s, t, h);
                    }
                    
                    @Override public ConstraintFormula forIdType(IdType s) {
                        // TODO: recur on upper bounds
                        if (t instanceof AndType) { return ConstraintFormula.FALSE; }
                        else {
                            // TODO: recur on upper bounds
                            return ConstraintFormula.FALSE;
                        }
                    }
                    
                    @Override public ConstraintFormula forInstantiatedType(InstantiatedType s) {
                        TypeConsIndex index = _table.typeCons(s.getName());
                        if (index instanceof TraitIndex) {
                            if (!(t instanceof InstantiatedType)) { return ConstraintFormula.FALSE; }
                            else {
                                TraitIndex traitIndex = (TraitIndex) index;
                                Lambda<Type, Type> subst = makeSubstitution(traitIndex.staticParameters(),
                                                                            s.getArgs(),
                                                                            traitIndex.hiddenParameters());
                                ConstraintFormula result = ConstraintFormula.FALSE;
                                for (TraitTypeWhere _sup : traitIndex.extendsTypes()) {
                                    TraitType sup = _sup.getType();
                                    ConstraintFormula f = ConstraintFormula.TRUE;
                                    for (Pair<Type, Type> c : traitIndex.typeConstraints()) {
                                        f = f.and(sub(subst.value(c.first()), subst.value(c.second()), h), h);
                                        if (f.isFalse()) { break; }
                                    }
                                    if (!f.isFalse()) { f = f.and(sub(subst.value(sup), t, h), h); }
                                    result = result.or(f, h);
                                    if (result.isTrue()) { break; }
                                }
                                return result;
                            }
                        }
                        else if (index instanceof TypeAliasIndex) {
                            TypeAliasIndex aliasIndex = (TypeAliasIndex) index;
                            Lambda<Type, Type> subst = makeSubstitution(aliasIndex.staticParameters(),
                                                                        s.getArgs());
                            return sub(subst.value(aliasIndex.type()), t, h);
                        }
                        else { throw new IllegalStateException("Unexpected index type"); }
                    }
                    
                    /*
                    @Override public ConstraintFormula forTupleType(TupleType s) {
                        if (t instanceof TupleType) {
                            if (compatibleTuples(s, (TupleType) t)) {
                                ConstraintFormula result = ConstraintFormula.TRUE;
                                TupleType tCast = (TupleType) t;
                                result = sub(s.getElements(), tCast.getElements(), h);
                                if (!result.isFalse()) {
                                    result = result.and(sub(s.getVarargs(), tCast.getVarargs(), h), h);
                                }
                                if (!result.isFalse()) {
                                    result = result.and(sub(keywordTypes(s.getKeywords()),
                                                            keywordTypes(tCast.getKeywords()), h), h);
                                }
                            }
                            else { return ConstraintFormula.FALSE; }
                        }
                        
                        else if (t instanceof InstantiatedType) {
                            return sub(TUPLE, t, h);
                        }
                        
                        else if (t instanceof AndType) {
                            // split to an And
                            List<Type> infElements1 = newInferenceVars(s.getElements().size());
                            List<Type> infElements2 = newInferenceVars(s.getElements().size());
                            Option<VarargsType> infVarargs1 = newInferenceVar(s.getVarargs());
                            Option<VarargsType> infVarargs2 = newInferenceVar(s.getVarargs());
                            List<KeywordType> infKeywords1 = newInferenceVars(s.getKeywords());
                            List<KeywordType> infKeywords2 = newInferenceVars(s.getKeywords());
                            ConstraintFormula f = ConstraintFormula.TRUE;
                            for (Triple<Type, Type, Type> ts :
                                     IterUtil.zip(s.getElements(), infElements1, infElements2)) {
                                f = f.and(equiv(ts.first(), new AndType(ts.second(), ts.third()), h), h);
                                if (f.isFalse()) { break; }
                            }
                            if (!f.isFalse() && infVarargs1.isSome()) {
                                Type varargs = Option.unwrap(s.getVarargs()).getType();
                                Type inf1 = Option.unwrap(infVarargs1).getType();
                                Type inf2 = Option.unwrap(infVarargs1).getType();
                                f = f.and(equiv(varargs, new AndType(inf1, inf2), h), h);
                            }
                            for (Triple<KeywordType, KeywordType, KeywordType> ks :
                                     IterUtil.zip(s.getKeywords(), infKeywords1, infKeywords2)) {
                                if (f.isFalse()) { break; }
                                AndType andT = new AndType(ks.second().getType(), ks.third().getType());
                                f = f.and(equiv(ks.first().getType(), andT, h), h);
                            }
                            if (!f.isFalse()) {
                                Type sup = new AndType(new TupleType(infElements1, infVarargs1, infKeywords1),
                                                       new TupleType(infElements2, infVarargs2, infKeywords2));
                                f = f.and(sub(sup, t, h), h);
                            }
                            return f;
                        }
                        
                        else if (t instanceof BottomType) {
                            ConstraintFormula result = ConstraintFormula.FALSE;
                            for (Type eltT : s.getElements()) {
                                ConstraintFormula f = sub(eltT, BOTTOM, h);
                                if (!f.isFalse()) { f = f.and(sub(BOTTOM, t, h), h); }
                                result = result.or(f, h);
                            }
                            for (KeywordType key : s.getKeywords()) {
                                ConstraintFormula f = sub(key.getType(), BOTTOM, h);
                                if (!f.isFalse()) { f = f.and(sub(BOTTOM, t, h), h); }
                                result = result.or(f, h);
                            }
                            return result;
                        }
                        
                        else { return ConstraintFormula.FALSE; }
                    }
                    */
                    
                    @Override public ConstraintFormula forVoidType(VoidType s) {
                        if (t instanceof VoidType) { return ConstraintFormula.TRUE; }
                        else if (t instanceof InstantiatedType) {
                            // extends Any
                            return sub(ANY, t, h);
                        }
                        else { return ConstraintFormula.FALSE; }
                    }
                    
                    @Override public ConstraintFormula forArrowType(ArrowType s) {
                        Type throwsType = throwsType(s);
                        
                        // domain is BottomType
                        ConstraintFormula result = sub(s.getDomain(), BOTTOM, h);
                        if (!result.isFalse()) {
                            result = result.and(sub(makeArrow(BOTTOM, BOTTOM, BOTTOM, false), t, h), h);
                        }
                        
                        if (!result.isTrue()) {
                            
                            if (t instanceof ArrowType) {
                                if (!s.isIo() || ((ArrowType) t).isIo()) {
                                    ArrowType tCast = (ArrowType) t;
                                    ConstraintFormula f = sub(tCast.getDomain(), s.getDomain(), h);
                                    if (!f.isFalse()) {
                                        f = f.and(sub(s.getRange(), tCast.getRange(), h), h);
                                    }
                                    if (!f.isFalse()) {
                                        f = f.and(sub(throwsType, throwsType(tCast), h), h);
                                    }
                                    result = result.or(f, h);
                                }
                            }
                            
                            else if (t instanceof InstantiatedType) {
                                // extends Object
                                result = result.or(sub(OBJECT, t, h), h);
                            }
                            
                            else if (t instanceof AndType) {
                                // split Or domain to an And
                                InferenceVarType d1 = newInferenceVar();
                                InferenceVarType d2 = newInferenceVar();
                                ConstraintFormula f = equiv(s.getDomain(), new OrType(d1, d2), h);
                                if (!f.isFalse()) {
                                    Type sup = new AndType(makeArrow(d1, s.getRange(), throwsType, s.isIo()),
                                                           makeArrow(d2, s.getRange(), throwsType, s.isIo()));
                                    f = f.and(sub(sup, t, h), h);
                                }
                                result = result.or(f, h);
                                
                                if (!result.isTrue()) {
                                    // split And range/throws to an And
                                    InferenceVarType r1 = newInferenceVar();
                                    InferenceVarType r2 = newInferenceVar();
                                    InferenceVarType th1 = newInferenceVar();
                                    InferenceVarType th2 = newInferenceVar();
                                    ConstraintFormula f2 = equiv(s.getRange(), new AndType(r1, r2), h);
                                    if (!f2.isFalse()) {
                                        f2 = f2.and(equiv(throwsType, new AndType(th1, th2), h), h);
                                    }
                                    if (!f2.isFalse()) {
                                        Type sup = new AndType(makeArrow(s.getDomain(), r1, th1, s.isIo()),
                                                               makeArrow(s.getDomain(), r2, th2, s.isIo()));
                                        f2 = f2.and(sub(sup, t, h), h);
                                    }
                                    result = result.or(f2, h);
                                }
                            }
                        }
                        return result;
                    }
                    
                    @Override public ConstraintFormula forOrType(OrType s) {
                        return sub(s.getFirst(), t, h).and(sub(s.getSecond(), t, h), h);
                    }
                    
                    @Override public ConstraintFormula forAndType(AndType s) {
                        if (t instanceof AndType) { return excl(s.getFirst(), s.getSecond(), h); }
                        else {
                            ConstraintFormula result = excl(s.getFirst(), s.getSecond(), h);
                            if (!result.isTrue()) {
                                result = result.or(sub(s.getFirst(), t, h), h);
                            }
                            if (!result.isTrue()) {
                                result = result.or(sub(s.getSecond(), t, h), h);
                            }
                            /*
                            if (!result.isTrue()) {
                                // merge tuple
                                // Simplification: assumes this rule is only relevant if one of the
                                // two types is a tuple, and then only for the tuple form it matches.
                                TupleType form = null;
                                if (s.getFirst() instanceof TupleType) { form = (TupleType) s.getFirst(); }
                                if (s.getSecond() instanceof TupleType) {
                                    if (form == null) { form = (TupleType) s.getSecond(); }
                                    else if (!compatibleTuples(form, (TupleType) s.getSecond())) { form = null; }
                                }
                                if (form != null) {
                                    List<Type> infElements1 = newInferenceVars(form.getElements().size());
                                    List<Type> infElements2 = newInferenceVars(form.getElements().size());
                                    Option<VarargsType> infVarargs1 = newInferenceVar(form.getVarargs());
                                    Option<VarargsType> infVarargs2 = newInferenceVar(form.getVarargs());
                                    List<KeywordType> infKeywords1 = newInferenceVars(form.getKeywords());
                                    List<KeywordType> infKeywords2 = newInferenceVars(form.getKeywords());
                                    TupleType match1 = new TupleType(infElements1, infVarargs1, infKeywords1);
                                    TupleType match2 = new TupleType(infElements2, infVarargs2, infKeywords2);
                                    ConstraintFormula f = equiv(s.getFirst(), match1, h);
                                    if (!f.isFalse()) { f = f.and(equiv(s.getSecond(), match2, h), h); }
                                    if (!f.isFalse()) {
                                        List<Type> andElements = new LinkedList<Type>();
                                        for (Pair<Type, Type> ts : IterUtil.zip(infElements1, infElements2)) {
                                            andElements.add(new AndType(ts.first(), ts.second()));
                                        }
                                        Option<VarargsType> andVarargs;
                                        if (infVarargs1.isSome()) {
                                            AndType vt = new AndType(Option.unwrap(infVarargs1).getType(),
                                                                     Option.unwrap(infVarargs2).getType());
                                            andVarargs = Option.some(new VarargsType(vt));
                                        }
                                        else { andVarargs = Option.none(); }
                                        List<KeywordType> andKeywords = new LinkedList<KeywordType>();
                                        for (Pair<KeywordType, KeywordType> ks :
                                                 IterUtil.zip(infKeywords1, infKeywords2)) {
                                            AndType kt = new AndType(ks.first().getType(), ks.second().getType());
                                            andKeywords.add(new KeywordType(ks.first().getName(), kt));
                                        }
                                        TupleType sup = new TupleType(andElements, andVarargs, andKeywords);
                                        f = f.and(sub(sup, t, h), h);
                                    }
                                    result = result.or(f, h);
                                }
                            }
                            */
                            return result;
                        }
                    }
                    
                });
                result = result.or(sResult, h);
            }
            return result;
        }
        } finally { debug.logEnd(); }
    }

    /**
     * Produce a formula that, if satisfied, will support s as a subtype of t.
     * Assumes s and t are normalized.
     */
    private ConstraintFormula subtype(final Type s, final Type t, SubtypeHistory history) {
        debug.logStart(new String[]{"s", "t"}, s, t);
        //debug.logStack();
        try {
        if (_cache.contains(s, t)) { return _cache.value(s, t); }
        else if (history.size() > MAX_SUBTYPE_DEPTH || history.contains(s, t)) {
            return ConstraintFormula.FALSE;
        }
        else {
            final SubtypeHistory h = history.extend(s, t);
            
            ConstraintFormula result = t.accept(new NodeAbstractVisitor<ConstraintFormula>() {
                
                @Override public ConstraintFormula forType(Type t) {
                    return ConstraintFormula.FALSE;
                }
                
                @Override public ConstraintFormula forInferenceVarType(InferenceVarType t) {
                    if (s instanceof InferenceVarType && s.equals(t)) {
                        return ConstraintFormula.TRUE;
                    }
                    else {
                        return ConstraintFormula.lowerBound(t, s, h);
                    }
                }
                
            });
            
            if (!result.isTrue()) {
                                                 
                ConstraintFormula sResult = s.accept(new NodeAbstractVisitor<ConstraintFormula>() {
                    
                    @Override public ConstraintFormula forBottomType(BottomType s) {
                        return ConstraintFormula.TRUE;
                    }
                    
                    @Override public ConstraintFormula forInferenceVarType(InferenceVarType s) {
                        return ConstraintFormula.upperBound(s, t, h);
                    }
                    
                    @Override public ConstraintFormula forIdType(IdType s) {
                        if (s.equals(t)) { return ConstraintFormula.TRUE; }
                        else { return ConstraintFormula.FALSE; }
                    }
                    
                    @Override public ConstraintFormula forInstantiatedType(InstantiatedType s) {
                        ConstraintFormula result;
                        if (t instanceof InstantiatedType && s.getName().equals(((InstantiatedType) t).getName())) {
                            result = equivalent(s.getArgs(), ((InstantiatedType) t).getArgs(), h);
                        }
                        else { result = ConstraintFormula.FALSE; }
                        
                        if (!result.isTrue()) {
                            TypeConsIndex index = _table.typeCons(s.getName());
                            if (index instanceof TraitIndex) {
                                TraitIndex traitIndex = (TraitIndex) index;
                                Lambda<Type, Type> subst = makeSubstitution(traitIndex.staticParameters(),
                                                                            s.getArgs(),
                                                                            traitIndex.hiddenParameters());
                                for (TraitTypeWhere _sup : traitIndex.extendsTypes()) {
                                    TraitType sup = _sup.getType();
                                    ConstraintFormula f = ConstraintFormula.TRUE;
                                    for (Pair<Type, Type> c : traitIndex.typeConstraints()) {
                                        f = f.and(subtype(subst.value(c.first()), subst.value(c.second()), h), h);
                                        if (f.isFalse()) { break; }
                                    }
                                    if (!f.isFalse()) { f = f.and(subtype(subst.value(sup), t, h), h); }
                                    result = result.or(f, h);
                                    if (result.isTrue()) { break; }
                                }
                            }
                            else if (index instanceof TypeAliasIndex) {
                                TypeAliasIndex aliasIndex = (TypeAliasIndex) index;
                                Lambda<Type, Type> subst = makeSubstitution(aliasIndex.staticParameters(),
                                                                            s.getArgs());
                                result = result.or(subtype(subst.value(aliasIndex.type()), t, h), h);
                            }
                        }
                        return result;
                    }
                    
                    /*
                    @Override public ConstraintFormula forTupleType(TupleType s) {
                        ConstraintFormula result;
                        if (t instanceof TupleType && compatibleTuples(s, (TupleType) t)) {
                            // Simplification: we allow covariance here
                            TupleType tCast = (TupleType) t;
                            result = subtype(s.getElements(), tCast.getElements(), h);
                            if (!result.isFalse()) {
                                result = result.and(subtype(s.getVarargs(), tCast.getVarargs(), h), h);
                            }
                            if (!result.isFalse()) {
                                result = result.and(subtype(keywordTypes(s.getKeywords()),
                                                            keywordTypes(tCast.getKeywords()), h), h);
                            }
                        }
                        else { result = ConstraintFormula.FALSE; }
                        
                        if (!result.isTrue()) {
                            // extends Tuple
                            result = result.or(subtype(TUPLE, t, h), h);
                        }
                        if (!result.isTrue()) {
                            // covariance
                            List<Type> infElements = newInferenceVars(s.getElements().size());
                            Option<VarargsType> infVarargs = newInferenceVar(s.getVarargs());
                            List<KeywordType> infKeywords = newInferenceVars(s.getKeywords());
                            ConstraintFormula f = subtype(s.getElements(), infElements, h);
                            if (!f.isFalse()) { f = f.and(subtype(s.getVarargs(), infVarargs, h), h); }
                            if (!f.isFalse()) {
                                f = f.and(subtype(keywordTypes(s.getKeywords()),
                                                  keywordTypes(infKeywords), h), h);
                            }
                            if (!f.isFalse()) {
                                TupleType sup = new TupleType(infElements, infVarargs, infKeywords);
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // split to an And
                            List<Type> infElements1 = newInferenceVars(s.getElements().size());
                            List<Type> infElements2 = newInferenceVars(s.getElements().size());
                            Option<VarargsType> infVarargs1 = newInferenceVar(s.getVarargs());
                            Option<VarargsType> infVarargs2 = newInferenceVar(s.getVarargs());
                            List<KeywordType> infKeywords1 = newInferenceVars(s.getKeywords());
                            List<KeywordType> infKeywords2 = newInferenceVars(s.getKeywords());
                            ConstraintFormula f = ConstraintFormula.TRUE;
                            for (Triple<Type, Type, Type> ts :
                                     IterUtil.zip(s.getElements(), infElements1, infElements2)) {
                                f = f.and(equivalent(ts.first(), new AndType(ts.second(), ts.third()), h), h);
                                if (f.isFalse()) { break; }
                            }
                            if (!f.isFalse() && infVarargs1.isSome()) {
                                Type varargs = Option.unwrap(s.getVarargs()).getType();
                                Type inf1 = Option.unwrap(infVarargs1).getType();
                                Type inf2 = Option.unwrap(infVarargs1).getType();
                                f = f.and(equivalent(varargs, new AndType(inf1, inf2), h), h);
                            }
                            for (Triple<KeywordType, KeywordType, KeywordType> ks :
                                     IterUtil.zip(s.getKeywords(), infKeywords1, infKeywords2)) {
                                if (f.isFalse()) { break; }
                                AndType andT = new AndType(ks.second().getType(), ks.third().getType());
                                f = f.and(equivalent(ks.first().getType(), andT, h), h);
                            }
                            if (!f.isFalse()) {
                                Type sup = new AndType(new TupleType(infElements1, infVarargs1, infKeywords1),
                                                       new TupleType(infElements2, infVarargs2, infKeywords2));
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // BottomType
                            for (Type eltT : s.getElements()) {
                                ConstraintFormula f = subtype(eltT, BOTTOM, h);
                                if (!f.isFalse()) { f = f.and(subtype(BOTTOM, t, h), h); }
                                result = result.or(f, h);
                            }
                            for (KeywordType key : s.getKeywords()) {
                                ConstraintFormula f = subtype(key.getType(), BOTTOM, h);
                                if (!f.isFalse()) { f = f.and(subtype(BOTTOM, t, h), h); }
                                result = result.or(f, h);
                            }
                        }
                        return result;
                    }
                    */
                    
                    @Override public ConstraintFormula forVoidType(VoidType s) {
                        if (t instanceof VoidType) { return ConstraintFormula.TRUE; }
                        else {
                            // extends Any
                            return subtype(ANY, t, h);
                        }
                    }
                    
                    @Override public ConstraintFormula forArrowType(ArrowType s) {
                        ConstraintFormula result;
                        Type throwsType = throwsType(s);
                        if (t instanceof ArrowType && s.isIo() == ((ArrowType) t).isIo()) {
                            // Simplification: allow covariance/contravariance here
                            ArrowType tCast = (ArrowType) t;
                            result = subtype(tCast.getDomain(), s.getDomain(), h);
                            if (!result.isFalse()) {
                                result = result.and(subtype(s.getRange(), tCast.getRange(), h), h);
                            }
                            if (!result.isFalse()) {
                                result = result.and(subtype(throwsType, throwsType(tCast), h), h);
                            }
                        }
                        else { result = ConstraintFormula.FALSE; }
                        
                        if (!result.isTrue()) {
                            // extends Object
                            result = result.or(subtype(OBJECT, t, h), h);
                        }
                        if (!result.isTrue()) {
                            // covariance/contravariance
                            InferenceVarType infDomain = newInferenceVar();
                            InferenceVarType infRange = newInferenceVar();
                            InferenceVarType infThrowsType = newInferenceVar();
                            ConstraintFormula f = ConstraintFormula.upperBound(infDomain, s.getDomain(), h);
                            f = f.and(ConstraintFormula.lowerBound(infRange, s.getRange(), h), h);
                            f = f.and(ConstraintFormula.lowerBound(infThrowsType, throwsType, h), h);
                            Type sup = makeArrow(infDomain, infRange, infThrowsType, s.isIo());
                            ConstraintFormula f1 = f.and(subtype(sup, t, h), h);
                            result = result.or(f1, h);
                            if (!result.isTrue() && !s.isIo()) {
                                sup = makeArrow(infDomain, infRange, infThrowsType, true);
                                ConstraintFormula f2 = f.and(subtype(sup, t, h), h);
                                result = result.or(f2, h);
                            }
                        }
                        if (!result.isTrue()) {
                            // domain is BottomType
                            ConstraintFormula f = subtype(s.getDomain(), BOTTOM, h);
                            if (!f.isFalse()) {
                                f = f.and(subtype(makeArrow(BOTTOM, BOTTOM, BOTTOM, false), t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // split Or domain to an And
                            InferenceVarType d1 = newInferenceVar();
                            InferenceVarType d2 = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getDomain(), new OrType(d1, d2), h);
                            if (!f.isFalse()) {
                                Type sup = new AndType(makeArrow(d1, s.getRange(), throwsType, s.isIo()),
                                                       makeArrow(d2, s.getRange(), throwsType, s.isIo()));
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // split And range/throws to an And
                            InferenceVarType r1 = newInferenceVar();
                            InferenceVarType r2 = newInferenceVar();
                            InferenceVarType th1 = newInferenceVar();
                            InferenceVarType th2 = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getRange(), new AndType(r1, r2), h);
                            if (!f.isFalse()) {
                                f = f.and(equivalent(throwsType, new AndType(th1, th2), h), h);
                            }
                            if (!f.isFalse()) {
                                Type sup = new AndType(makeArrow(s.getDomain(), r1, th1, s.isIo()),
                                                       makeArrow(s.getDomain(), r2, th2, s.isIo()));
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        return result;
                    }
                    
                    @Override public ConstraintFormula forOrType(OrType s) {
                        ConstraintFormula result;
                        if (t instanceof OrType) {
                            result = equivalent(s.getFirst(), ((OrType) t).getFirst(), h);
                            if (!result.isFalse()) {
                                result = result.and(equivalent(s.getSecond(), ((OrType) t).getSecond(), h), h);
                            }
                        }
                        else { result = ConstraintFormula.FALSE; }
                        
                        if (!result.isTrue()) {
                            // common supertype
                            ConstraintFormula f = subtype(s.getFirst(), t, h);
                            if (!f.isFalse()) { f = f.and(subtype(s.getSecond(), t, h), h); }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // distribution of And
                            InferenceVarType inf1 = newInferenceVar();
                            InferenceVarType inf2 = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getFirst(), new AndType(inf1, inf2), h);
                            if (!f.isFalse()) {
                                Type sup = new AndType(new OrType(inf1, s.getSecond()),
                                                       new OrType(inf2, s.getSecond()));
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // commutativity
                            result = result.or(subtype(new OrType(s.getSecond(), s.getFirst()), t, h), h);
                        }
                        if (!result.isTrue()) {
                            // associativity
                            InferenceVarType inf1 = newInferenceVar();
                            InferenceVarType inf2 = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getFirst(), new OrType(inf1, inf2), h);
                            if (!f.isFalse()) {
                                Type sup = new OrType(inf1, new OrType(inf2, s.getSecond()));
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        return result;
                    }
                    
                    @Override public ConstraintFormula forAndType(AndType s) {
                        ConstraintFormula result;
                        if (t instanceof AndType) {
                            result = equivalent(s.getFirst(), ((AndType) t).getFirst(), h);
                            if (!result.isFalse()) {
                                result = result.and(equivalent(s.getSecond(), ((AndType) t).getSecond(), h), h);
                            }
                        }
                        else { result = ConstraintFormula.FALSE; }
                        
                        if (!result.isTrue()) {
                            // extends its first element
                            result = result.or(subtype(s.getFirst(), t, h), h);
                        }
                        if (!result.isTrue()) {
                            // extends its second element
                            result = result.or(subtype(s.getSecond(), t, h), h);
                        }
                        if (!result.isTrue()) {
                            // elements exclude each other
                            ConstraintFormula f = excludes(s.getFirst(), s.getSecond(), h);
                            if (!f.isFalse()) {
                                f = f.and(subtype(BOTTOM, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        /*
                        if (!result.isTrue()) {
                            // merge tuple
                            // Simplification: assumes this rule is only relevant if one of the
                            // two types is a tuple, and then only for the tuple form it matches.
                            TupleType form = null;
                            if (s.getFirst() instanceof TupleType) { form = (TupleType) s.getFirst(); }
                            if (s.getSecond() instanceof TupleType) {
                                if (form == null) { form = (TupleType) s.getSecond(); }
                                else if (!compatibleTuples(form, (TupleType) s.getSecond())) { form = null; }
                            }
                            if (form != null) {
                                List<Type> infElements1 = newInferenceVars(form.getElements().size());
                                List<Type> infElements2 = newInferenceVars(form.getElements().size());
                                Option<VarargsType> infVarargs1 = newInferenceVar(form.getVarargs());
                                Option<VarargsType> infVarargs2 = newInferenceVar(form.getVarargs());
                                List<KeywordType> infKeywords1 = newInferenceVars(form.getKeywords());
                                List<KeywordType> infKeywords2 = newInferenceVars(form.getKeywords());
                                TupleType match1 = new TupleType(infElements1, infVarargs1, infKeywords1);
                                TupleType match2 = new TupleType(infElements2, infVarargs2, infKeywords2);
                                ConstraintFormula f = equivalent(s.getFirst(), match1, h);
                                if (!f.isFalse()) { f = f.and(equivalent(s.getSecond(), match2, h), h); }
                                if (!f.isFalse()) {
                                    List<Type> andElements = new LinkedList<Type>();
                                    for (Pair<Type, Type> ts : IterUtil.zip(infElements1, infElements2)) {
                                        andElements.add(new AndType(ts.first(), ts.second()));
                                    }
                                    Option<VarargsType> andVarargs;
                                    if (infVarargs1.isSome()) {
                                        AndType vt = new AndType(Option.unwrap(infVarargs1).getType(),
                                                                 Option.unwrap(infVarargs2).getType());
                                        andVarargs = Option.some(new VarargsType(vt));
                                    }
                                    else { andVarargs = Option.none(); }
                                    List<KeywordType> andKeywords = new LinkedList<KeywordType>();
                                    for (Pair<KeywordType, KeywordType> ks :
                                             IterUtil.zip(infKeywords1, infKeywords2)) {
                                        AndType kt = new AndType(ks.first().getType(), ks.second().getType());
                                        andKeywords.add(new KeywordType(ks.first().getName(), kt));
                                    }
                                    TupleType sup = new TupleType(andElements, andVarargs, andKeywords);
                                    f = f.and(subtype(sup, t, h), h);
                                }
                                result = result.or(f, h);
                            }
                        }
                        */
                        if (!result.isTrue()) {
                            // merge arrow domain (non-io)
                            InferenceVarType d1 = newInferenceVar();
                            InferenceVarType d2 = newInferenceVar();
                            InferenceVarType r = newInferenceVar();
                            InferenceVarType th = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getFirst(), makeArrow(d1, r, th, false), h);
                            if (!f.isFalse()) {
                                f = f.and(equivalent(s.getSecond(), makeArrow(d2, r, th, false), h), h);
                            }
                            if (!f.isFalse()) {
                                ArrowType sup = makeArrow(new AndType(d1, d2), r, th, false);
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // merge arrow domain (io)
                            InferenceVarType d1 = newInferenceVar();
                            InferenceVarType d2 = newInferenceVar();
                            InferenceVarType r = newInferenceVar();
                            InferenceVarType th = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getFirst(), makeArrow(d1, r, th, true), h);
                            if (!f.isFalse()) {
                                f = f.and(equivalent(s.getSecond(), makeArrow(d2, r, th, true), h), h);
                            }
                            if (!f.isFalse()) {
                                ArrowType sup = makeArrow(new AndType(d1, d2), r, th, true);
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // merge arrow range (non-io)
                            InferenceVarType d = newInferenceVar();
                            InferenceVarType r1 = newInferenceVar();
                            InferenceVarType r2 = newInferenceVar();
                            InferenceVarType th1 = newInferenceVar();
                            InferenceVarType th2 = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getFirst(), makeArrow(d, r1, th1, false), h);
                            if (!f.isFalse()) {
                                f = f.and(equivalent(s.getSecond(), makeArrow(d, r2, th2, false), h), h);
                            }
                            if (!f.isFalse()) {
                                ArrowType sup = makeArrow(d, new AndType(r1, r2), new AndType(th1, th2), false);
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // merge arrow range (io)
                            InferenceVarType d = newInferenceVar();
                            InferenceVarType r1 = newInferenceVar();
                            InferenceVarType r2 = newInferenceVar();
                            InferenceVarType th1 = newInferenceVar();
                            InferenceVarType th2 = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getFirst(), makeArrow(d, r1, th1, true), h);
                            if (!f.isFalse()) {
                                f = f.and(equivalent(s.getSecond(), makeArrow(d, r2, th2, true), h), h);
                            }
                            if (!f.isFalse()) {
                                ArrowType sup = makeArrow(d, new AndType(r1, r2), new AndType(th1, th2), true);
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // distribution of Or
                            InferenceVarType inf1 = newInferenceVar();
                            InferenceVarType inf2 = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getFirst(), new OrType(inf1, inf2), h);
                            if (!f.isFalse()) {
                                Type sup = new OrType(new AndType(inf1, s.getSecond()),
                                                      new AndType(inf2, s.getSecond()));
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        if (!result.isTrue()) {
                            // commutativity
                            result = result.or(subtype(new AndType(s.getSecond(), s.getFirst()), t, h), h);
                        }
                        if (!result.isTrue()) {
                            // associativity
                            InferenceVarType inf1 = newInferenceVar();
                            InferenceVarType inf2 = newInferenceVar();
                            ConstraintFormula f = equivalent(s.getFirst(), new AndType(inf1, inf2), h);
                            if (!f.isFalse()) {
                                Type sup = new AndType(inf1, new OrType(inf2, s.getSecond()));
                                f = f.and(subtype(sup, t, h), h);
                            }
                            result = result.or(f, h);
                        }
                        return result;
                    }
                    
                });
                result = result.or(sResult, h);
            }
            
            if (!result.isTrue()) {
                // expand to intersection
                InferenceVarType inf = newInferenceVar();
                ConstraintFormula f = subtype(new AndType(s, inf), t, h);
                f = f.and(ConstraintFormula.lowerBound(inf, s, h), h);
                result = result.or(f, h);
            }
            if (!result.isTrue()) {
                // expand to union
                InferenceVarType inf = newInferenceVar();
                result = result.or(subtype(new OrType(s, inf), t, h), h);
            }
            
            // match where declarations
            // reverse aliases
            
            return result;
        }
        } finally { debug.logEnd(); }
    }

    public ConstraintFormula equivalent(Type s, Type t, SubtypeHistory history) {
        ConstraintFormula result = subtype(s, t, history);
        if (!result.isFalse()) { result = result.and(subtype(t, s, history), history); }
        return result;
    }
    
    public ConstraintFormula equiv(Type s, Type t, SubtypeHistory history) {
        ConstraintFormula result = sub(s, t, history);
        if (!result.isFalse()) { result = result.and(sub(t, s, history), history); }
        return result;
    }
    

    public ConstraintFormula equivalent(StaticArg a1, final StaticArg a2,
                                        final SubtypeHistory history) {
        return a1.accept(new NodeAbstractVisitor<ConstraintFormula>() {
            @Override public ConstraintFormula forIdArg(IdArg a1) {
                if (a2 instanceof IdArg) {
                    boolean result = a1.getName().equals(((IdArg) a2).getName());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forTypeArg(TypeArg a1) {
                if (a2 instanceof TypeArg) {
                    return equivalent(a1.getType(), ((TypeArg) a2).getType(), history);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forIntArg(IntArg a1) {
                if (a2 instanceof IntArg) {
                    boolean result = a1.getVal().equals(((IntArg) a2).getVal());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forBoolArg(BoolArg a1) {
                if (a2 instanceof BoolArg) {
                    boolean result = a1.getBool().equals(((BoolArg) a2).getBool());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forOprArg(OprArg a1) {
                if (a2 instanceof OprArg) {
                    boolean result = a1.getName().equals(((OprArg) a2).getName());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forDimArg(DimArg a1) {
                if (a2 instanceof DimArg) {
                    boolean result = a1.getDim().equals(((DimArg) a2).getDim());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forUnitArg(UnitArg a1) {
                if (a2 instanceof UnitArg) {
                    boolean result = a1.getUnit().equals(((UnitArg) a2).getUnit());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
        });
    }

    public ConstraintFormula equiv(StaticArg a1, final StaticArg a2, final SubtypeHistory history) {
        return a1.accept(new NodeAbstractVisitor<ConstraintFormula>() {
            @Override public ConstraintFormula forIdArg(IdArg a1) {
                if (a2 instanceof IdArg) {
                    boolean result = a1.getName().equals(((IdArg) a2).getName());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forTypeArg(TypeArg a1) {
                if (a2 instanceof TypeArg) {
                    return equiv(a1.getType(), ((TypeArg) a2).getType(), history);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forIntArg(IntArg a1) {
                if (a2 instanceof IntArg) {
                    boolean result = a1.getVal().equals(((IntArg) a2).getVal());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forBoolArg(BoolArg a1) {
                if (a2 instanceof BoolArg) {
                    boolean result = a1.getBool().equals(((BoolArg) a2).getBool());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forOprArg(OprArg a1) {
                if (a2 instanceof OprArg) {
                    boolean result = a1.getName().equals(((OprArg) a2).getName());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forDimArg(DimArg a1) {
                if (a2 instanceof DimArg) {
                    boolean result = a1.getDim().equals(((DimArg) a2).getDim());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
            @Override public ConstraintFormula forUnitArg(UnitArg a1) {
                if (a2 instanceof UnitArg) {
                    boolean result = a1.getUnit().equals(((UnitArg) a2).getUnit());
                    return ConstraintFormula.fromBoolean(result);
                }
                else { return ConstraintFormula.FALSE; }
            }
        });
    }

    public ConstraintFormula excludes(Type s, Type t, SubtypeHistory history) {
        return ConstraintFormula.FALSE;
    }
    
    public ConstraintFormula excl(Type s, Type t, SubtypeHistory history) {
        return ConstraintFormula.FALSE;
    }

    public Type meet(Type s, Type t, SubtypeHistory history) {
        if (subtype(s, t, history).isTrue()) { return s; }
        else if (subtype(t, s, history).isTrue()) { return t; }
        else { return new AndType(s, t); }
    }
    
    public Type mt(Type s, Type t, SubtypeHistory history) {
        if (sub(s, t, history).isTrue()) { return s; }
        else if (sub(t, s, history).isTrue()) { return t; }
        else { return new AndType(s, t); }
    }

    public Type join(Type s, Type t, SubtypeHistory history) {
        if (subtype(s, t, history).isTrue()) { return t; }
        else if (subtype(t, s, history).isTrue()) { return s; }
        else { return new OrType(s, t); }
    }

    public Type jn(Type s, Type t, SubtypeHistory history) {
        if (sub(s, t, history).isTrue()) { return t; }
        else if (sub(t, s, history).isTrue()) { return s; }
        else { return new OrType(s, t); }
    }


    private Lambda<Type, Type> makeSubstitution(Iterable<? extends StaticParam> params,
                                                Iterable<? extends StaticArg> args) {
        return makeSubstitution(params, args, IterUtil.<Id>empty());
    }

    /** Assumes param/arg lists have the same length and corresponding elements are compatible. */
    private Lambda<Type, Type> makeSubstitution(Iterable<? extends StaticParam> params,
                                                Iterable<? extends StaticArg> args,
                                                Iterable<? extends Id> hiddenParams) {
        final Map<QualifiedIdName, Type> typeSubs = new HashMap<QualifiedIdName, Type>();
        final Map<Op, Op> opSubs = new HashMap<Op, Op>();
        final Map<QualifiedIdName, IntExpr> intSubs = new HashMap<QualifiedIdName, IntExpr>();
        final Map<QualifiedIdName, BoolExpr> boolSubs = new HashMap<QualifiedIdName, BoolExpr>();
        final Map<QualifiedIdName, DimExpr> dimSubs = new HashMap<QualifiedIdName, DimExpr>();
        final Map<QualifiedIdName, Expr> unitSubs = new HashMap<QualifiedIdName, Expr>();
        for (Pair<StaticParam, StaticArg> pair : IterUtil.zip(params, args)) {
            final StaticArg a = pair.second();
            pair.first().accept(new NodeAbstractVisitor_void() {
                @Override public void forSimpleTypeParam(SimpleTypeParam p) {
                    typeSubs.put(NodeFactory.makeQualifiedIdName(p.getName()),
                                 ((TypeArg) a).getType());
                }
                @Override public void forOperatorParam(OperatorParam p) {
                    opSubs.put(p.getName(), ((OprArg) a).getName());
                }
                @Override public void forIntParam(IntParam p) {
                    intSubs.put(NodeFactory.makeQualifiedIdName(p.getName()),
                                ((IntArg) a).getVal());
                }
                @Override public void forNatParam(NatParam p) {
                    intSubs.put(NodeFactory.makeQualifiedIdName(p.getName()),
                                ((IntArg) a).getVal());
                }
                @Override public void forBoolParam(BoolParam p) {
                    boolSubs.put(NodeFactory.makeQualifiedIdName(p.getName()),
                                 ((BoolArg) a).getBool());
                }
                @Override public void forDimensionParam(DimensionParam p) {
                    dimSubs.put(NodeFactory.makeQualifiedIdName(p.getName()),
                                ((DimArg) a).getDim());
                }
                @Override public void forUnitParam(UnitParam p) {
                    unitSubs.put(NodeFactory.makeQualifiedIdName(p.getName()),
                                 ((UnitArg) a).getUnit());
                }

            });
        }
        for (Id id : hiddenParams) {
            typeSubs.put(NodeFactory.makeQualifiedIdName(id), newInferenceVar());
        }

        return new Lambda<Type, Type>() {
            public Type value(Type t) {
                return (Type) t.accept(new NodeUpdateVisitor() {

                    /** Handle type variables */
                    @Override public Type forIdType(IdType n) {
                        if (typeSubs.containsKey(n.getName())) {
                            return typeSubs.get(n.getName());
                        }
                        else { return n; }
                    }

                    /** Handle arguments to opr parameters */
                    @Override public OprArg forOprArg(OprArg n) {
                        if (opSubs.containsKey(n.getName())) {
                            return new OprArg(n.getSpan(), n.isParenthesized(),
                                              opSubs.get(n.getName()));
                        }
                        else { return n; }
                    }

                    /** Handle names in IntExprs */
                    @Override public IntExpr forIntRef(IntRef n) {
                        if (intSubs.containsKey(n.getName())) {
                            return intSubs.get(n.getName());
                        }
                        else { return n; }
                    }

                    /** Handle names in BoolExprs */
                    @Override public BoolExpr forBoolRef(BoolRef n) {
                        if (boolSubs.containsKey(n.getName())) {
                            return boolSubs.get(n.getName());
                        }
                        else { return n; }
                    }

                    /** Handle names in DimExprs */
                    @Override public DimExpr forDimRef(DimRef n) {
                        if (dimSubs.containsKey(n.getName())) {
                            return dimSubs.get(n.getName());
                        }
                        else { return n; }
                    }
                });
            }
        };
    }


    /** Assumes type lists have the same length. */
    private ConstraintFormula subtype(Iterable<? extends Type> ss, Iterable<? extends Type> ts,
                                      SubtypeHistory history) {
        ConstraintFormula result = ConstraintFormula.TRUE;
        for (Pair<Type, Type> pair : IterUtil.zip(ss, ts)) {
            result = result.and(subtype(pair.first(), pair.second(), history), history);
            if (result.isFalse()) { break; }
        }
        return result;
    }

    /** Assumes type lists have the same length. */
    private ConstraintFormula sub(Iterable<? extends Type> ss, Iterable<? extends Type> ts,
                                  SubtypeHistory history) {
        ConstraintFormula result = ConstraintFormula.TRUE;
        for (Pair<Type, Type> pair : IterUtil.zip(ss, ts)) {
            result = result.and(sub(pair.first(), pair.second(), history), history);
            if (result.isFalse()) { break; }
        }
        return result;
    }

    /** Assumes the options are consistent -- either both some or both none. */
    private ConstraintFormula subtype(Option<VarargsType> v1, Option<VarargsType> v2,
                                      SubtypeHistory history) {
        if (v1.isSome()) {
            return subtype(Option.unwrap(v1).getType(), Option.unwrap(v2).getType(), history);
        }
        else { return ConstraintFormula.TRUE; }
    }

    /** Assumes the options are consistent -- either both some or both none. */
    private ConstraintFormula sub(Option<VarargsType> v1, Option<VarargsType> v2,
                                  SubtypeHistory history) {
        if (v1.isSome()) {
            return sub(Option.unwrap(v1).getType(), Option.unwrap(v2).getType(), history);
        }
        else { return ConstraintFormula.TRUE; }
    }


    private InferenceVarType newInferenceVar() {
        return new InferenceVarType(new Object());
    }

    private List<Type> newInferenceVars(int size) {
        List<Type> result = new ArrayList<Type>(size);
        for (int i = 0; i < size; i++) { result.add(newInferenceVar()); }
        return result;
    }

    private Option<VarargsType> newInferenceVar(Option<VarargsType> varargs) {
        if (varargs.isSome()) { return Option.some(new VarargsType(newInferenceVar())); }
        else { return Option.none(); }
    }

    private List<KeywordType> newInferenceVars(List<KeywordType> keys) {
        List<KeywordType> result = new ArrayList<KeywordType>(keys.size());
        for (KeywordType k : keys) {
            result.add(new KeywordType(k.getName(), newInferenceVar()));
        }
        return result;
    }



    /** Assumes arg lists have the same length. */
    private ConstraintFormula equivalent(Iterable<? extends StaticArg> a1s,
                                         Iterable<? extends StaticArg> a2s,
                                         SubtypeHistory history) {
        ConstraintFormula result = ConstraintFormula.TRUE;
        for (Pair<StaticArg, StaticArg> pair : IterUtil.zip(a1s, a2s)) {
            result = result.and(equivalent(pair.first(), pair.second(), history), history);
            if (result.isFalse()) { break; }
        }
        return result;
    }

    /** Assumes arg lists have the same length. */
    private ConstraintFormula equiv(Iterable<? extends StaticArg> a1s,
                                    Iterable<? extends StaticArg> a2s,
                                    SubtypeHistory history) {
        ConstraintFormula result = ConstraintFormula.TRUE;
        for (Pair<StaticArg, StaticArg> pair : IterUtil.zip(a1s, a2s)) {
            result = result.and(equiv(pair.first(), pair.second(), history), history);
            if (result.isFalse()) { break; }
        }
        return result;
    }


    public ArrowType makeArrow(Type domain, Type range, Type throwsT, boolean io) {
        return new ArrowType(domain, range,
                             Option.some(Collections.singletonList(throwsT)), io);
    }

    public Type throwsType(ArrowType t) {
        return IterUtil.first(Option.unwrap(t.getThrowsClause()));
    }

    public Iterable<Type> keywordTypes(Iterable<? extends KeywordType> keys) {
        return IterUtil.map(keys, KEYWORD_TO_TYPE);
    }

    private static final Lambda<KeywordType, Type> KEYWORD_TO_TYPE =
        new Lambda<KeywordType, Type>() {
        public Type value(KeywordType k) { return k.getType(); }
    };

    /** Test whether the given tuples have the same arity and matching varargs/keyword entries */
    /*
    private boolean compatibleTuples(TupleType s, TupleType t) {
        if (s.getElements().size() == t.getElements().size() &&
            s.getVarargs().isSome() == t.getVarargs().isSome() &&
            s.getKeywords().size() == t.getKeywords().size()) {
            for (Pair<KeywordType, KeywordType> keys :
                 IterUtil.zip(s.getKeywords(), t.getKeywords())) {
                if (!keys.first().getName().equals(keys.second().getName())) {
                    return false;
                }
            }
            return true;
        }
        else { return false; }
    }
    */




    // Package private -- accessed by ConstraintFormula
    class SubtypeHistory {
        private final Relation<Type, Type> _entries;
        public SubtypeHistory() { _entries = new HashRelation<Type, Type>(false, false); }
        private SubtypeHistory(Relation<Type, Type> entries) { _entries = entries; }
        public int size() { return _entries.size(); }
        public boolean contains(Type s, Type t) { return _entries.contains(s, t); }
        public SubtypeHistory extend(Type s, Type t) {
            Relation<Type, Type> newEntries = new HashRelation<Type, Type>();
            newEntries.addAll(_entries);
            newEntries.add(s, t);
            return new SubtypeHistory(newEntries);
        }
        public ConstraintFormula subtype(Type s, Type t) {
            return SIMPLIFIED_SUBTYPING ?
                TypeAnalyzer.this.sub(s, t, this) :
                TypeAnalyzer.this.subtype(s, t, this);
        }
        public Type meet(Type s, Type t) {
            return SIMPLIFIED_SUBTYPING ?
                TypeAnalyzer.this.mt(s, t, this) :
                TypeAnalyzer.this.meet(s, t, this);
        }
        public Type join(Type s, Type t) {
            return SIMPLIFIED_SUBTYPING ?
                TypeAnalyzer.this.jn(s, t, this) :
                TypeAnalyzer.this.join(s, t, this);
        }
    }

    private static class SubtypeCache {
        public boolean contains(Type s, Type t) { return false; }
        public ConstraintFormula value(Type s, Type t) { throw new IllegalArgumentException(); }
    }

}
