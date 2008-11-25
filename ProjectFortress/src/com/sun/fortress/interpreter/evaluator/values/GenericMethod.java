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

package com.sun.fortress.interpreter.evaluator.values;

import static com.sun.fortress.exceptions.InterpreterBug.bug;
import static com.sun.fortress.exceptions.ProgramError.error;
import static com.sun.fortress.exceptions.ProgramError.errorMsg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.EvalType;
import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.interpreter.evaluator.types.FTypeTop;
import com.sun.fortress.nodes.DimParam;
import com.sun.fortress.nodes.FnDecl;
import com.sun.fortress.nodes.IdOrOpOrAnonymousName;
import com.sun.fortress.nodes.NatParam;
import com.sun.fortress.nodes.OpParam;
import com.sun.fortress.nodes.Param;
import com.sun.fortress.nodes.StaticArg;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes.TypeParam;
import com.sun.fortress.nodes_util.Applicable;
import com.sun.fortress.nodes_util.NodeComparator;
import com.sun.fortress.useful.Factory1P;
import com.sun.fortress.useful.HasAt;
import com.sun.fortress.useful.Memo1P;
import com.sun.fortress.useful.Useful;

import edu.rice.cs.plt.tuple.Option;

public class GenericMethod extends MethodClosure implements
        GenericFunctionOrMethod, Factory1P<List<FType>, MethodClosure, HasAt> {

    /* (non-Javadoc)
     * @see com.sun.fortress.interpreter.evaluator.values.FValue#getString()
     */
    @Override
    public String getString() {
        return s(getDef());
    }

    boolean isTraitMethod;

    Environment evaluationEnv;

    protected MethodClosure newClosure(Environment clenv, List<FType> args) {
        MethodClosure cl;
        if (!isTraitMethod) {
            cl = new MethodClosureInstance(getEnv(), clenv, getDef(),
                                           getDefiner(), args, this);
        } else if (FType.anyAreSymbolic(args)) {
            cl = new TraitMethodInstance(getEnv(), clenv, getDef(),
                                                    getDefiner(), args, this);
        } else {
            // TODO Intention is that this is a plain old instantiation,
            // however there are issues of capturing the evaluation
            // environment that makes this not work quite right
            // MethodClosureInstance ought to be MethodClosure, but
            // isn't, yet.
            cl = new TraitMethod(getEnv(), clenv, getDef(),
                                            getDefiner(), args);
        }
        cl.finishInitializing();
        return (MethodClosure) cl;
    }

    private class Factory implements
            Factory1P<List<FType>, MethodClosure, HasAt> {

        public MethodClosure make(List<FType> args, HasAt location) {
            Environment clenv = evaluationEnv.extendAt(location); // TODO is this the right environment?
            // It looks like it might be, or else good enough.  The disambiguating
            // pass effectively hides all the names defined in the interior
            // of the trait.
            List<StaticParam> params = getDef().getStaticParams();
            EvalType.bindGenericParameters(params, args, clenv, location,
                    getDef());
            clenv.bless();
            return newClosure(clenv, args);
        }
    }

    Memo1P<List<FType>, MethodClosure, HasAt> memo = new Memo1P<List<FType>, MethodClosure, HasAt>(
            new Factory());

    public MethodClosure make(List<FType> l, HasAt location) {
        return memo.make(l, location);
    }

    public GenericMethod(Environment declarationEnv, Environment evaluationEnv,
            FnDecl fndef, FType definer, boolean isTraitMethod) {
        super(// new SpineEnv(declarationEnv, fndef), // Add an extra scope/layer for the generics.
                declarationEnv, // not yet, it changes overloading semantics.
                fndef, definer );
        this.isTraitMethod = isTraitMethod;
        this.evaluationEnv = evaluationEnv;
    }

    //    public GenericMethod(Environment declarationEnv, Environment traitEnv, FnDecl fndef, String selfName) {
    //        super(declarationEnv, fndef, selfName);
    //
    //    }

    public MethodClosure typeApply(List<StaticArg> args, Environment e, HasAt location) {
        List<StaticParam> params = getDef().getStaticParams();

        // Evaluate each of the args in e, inject into clenv.
        if (args.size() != params.size()) {
            error(location, e,
                  "Generic instantiation (size) mismatch, expected "
                  + Useful.listInParens(params)
                  + " got " + Useful.listInParens(args));
        }
        EvalType et = new EvalType(e);
        // TODO Can combine these two functions if we enhance the memo and factory
        // to pass two parameters instead of one.

        ArrayList<FType> argValues = et.forStaticArgList(args);
        return make(argValues, location);
    }

    public Simple_fcn typeApply(HasAt location, List<FType> argValues) {
        return make(argValues, location);
    }

    public void finishInitializing() {
        Applicable x = getDef();
        List<Param> params = x.getParams();
        Option<Type> rt = x.getReturnType();
        Environment env = getEnv(); // should need this for types,
        // below.
        // TODO work in progress
        // Inject type parameters into environment as symbolics
        List<StaticParam> tparams = getDef().getStaticParams();
        for (StaticParam tp : tparams) {
            if (tp instanceof DimParam) {
                DimParam dp = (DimParam) tp;
            } else if (tp instanceof NatParam) {
                NatParam np = (NatParam) tp;
            } else if (tp instanceof OpParam) {
                OpParam op = (OpParam) tp;
            } else if (tp instanceof TypeParam) {
                TypeParam stp = (TypeParam) tp;
            } else {
                bug(tp, errorMsg("Unexpected StaticParam ", tp));
            }
        }

        FType ft = EvalType.getFTypeFromOption(rt, env, FTypeTop.ONLY);
        List<Parameter> fparams = EvalType.paramsToParameters(env, params);

        setParamsAndReturnType(fparams, ft);
        return;
    }

    static class GenericComparer implements Comparator<GenericMethod> {

        public int compare(GenericMethod arg0, GenericMethod arg1) {
            Applicable a0 = arg0.getDef();
            Applicable a1 = arg1.getDef();

            IdOrOpOrAnonymousName fn0 = a0.getName();
            IdOrOpOrAnonymousName fn1 = a1.getName();
            int x = NodeComparator.compare(fn0, fn1);
            if (x != 0)
                return x;

            List<StaticParam> oltp0 = a0.getStaticParams();
            List<StaticParam> oltp1 = a1.getStaticParams();

            return NodeComparator.compare(oltp0, oltp1);

        }

    }

    static final GenericComparer genComparer = new GenericComparer();

    // static class GenericFullComparer implements Comparator<GenericMethod> {

    //     public int compare(GenericMethod arg0, GenericMethod arg1) {
    //         return compare(arg0.getDef(), arg1.getDef());
    //     }

    //     int compare(Applicable left, Applicable right) {
    //         if (left instanceof FnExpr) {
    //             int x = Useful.compareClasses(left, right);
    //             if (x != 0) return x;
    //             return NodeUtil.nameString(((FnExpr)left).getName())
    //                 .compareTo(NodeUtil.nameString(((FnExpr)right).getName()));
    //         } else if (left instanceof FnDecl) {
    //             int x = Useful.compareClasses(left, right);
    //             if (x != 0) return x;
    //             return compare(left, (FnDecl)right);
    //         } else if (left instanceof NativeApp) {
    //             return Useful.compareClasses(left, right);
    //         } else {
    //             throw new InterpreterBug(left, "NodeComparator.compare(" +
    //                                      left.getClass() + ", " + right.getClass());
    //         }
    //     }

    // }
    // static final GenericFullComparer genFullComparer = new GenericFullComparer();

    public IdOrOpOrAnonymousName getName() {
        return getDef().getName();
    }

    public List<StaticParam> getStaticParams() {
        return getDef().getStaticParams();
    }

    public List<Param> getParams() {
        return getDef().getParams();
    }

    public Option<Type> getReturnType() {
        return getDef().getReturnType();
    }



}
