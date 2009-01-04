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

package com.sun.fortress.compiler.desugarer;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.DesugarerUtil;
import com.sun.fortress.nodes_util.ExprFactory;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.OprUtil;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.interpreter.glue.WellKnownNames;
import com.sun.fortress.useful.Useful;

import static com.sun.fortress.nodes_util.DesugarerUtil.*;
import static com.sun.fortress.exceptions.InterpreterBug.bug;

/** Run desugaring phases that must occur before disambiguation.
 *  1) Rewrite trait, object, and object expressions to explicitly extend Object.
 *  2) Remove conditional operators, replacing their operands with thunks.
 *  Desugar conditional operators into operators that take thunks.
 *  This desugaring is described in section 22.8 of the specification.
 *  We find {@code e_1 AND: e_2}, for example, and change it into
 *  {@code e_1 AND (fn () => e_2)}, for which an overloading must exist.
 *  This desugaring must go before disambiguation, and is therefore called
 *  by {@code PreDisambiguationDesugarer}.
 *  3) Remwrite reductions to explicit invocations of big operators.
 */
public class PreDisambiguationDesugaringVisitor extends NodeUpdateVisitor {

    private final Id anyTypeId = NodeFactory.makeId(NodeFactory.makeSpan("singleton"), WellKnownNames.anyTypeName);

    /** If the extends clause of a trait declaration, an object declaration, or
     *  an object expression is empty, then replace the empty extends clause
     *  with {Object}.
     */
    private List<TraitTypeWhere> rewriteExtendsClause(Node whence,
                                                      List<TraitTypeWhere> extendsClause) {
        if (extendsClause.size() > 0) return extendsClause;
        if ( ! ( whence instanceof ASTNode ) )
            bug(whence, "Only ASTNodes are supported.");
        Id objectId = NodeFactory.makeId(NodeUtil.getSpan((ASTNode)whence),
                                         WellKnownNames.objectTypeName);
        TraitType typeObject = NodeFactory.makeTraitType(objectId);
        TraitTypeWhere extendsObject = NodeFactory.makeTraitTypeWhere(typeObject);
        return Collections.singletonList(extendsObject);
    }

    @Override
        public Node forObjectExprOnly(ObjectExpr that,
                                      ExprInfo info,
                                      TraitTypeHeader header) {
        Span span = NodeUtil.getSpan(that);
        List<TraitTypeWhere> extendsClause = rewriteExtendsClause(that, header.getExtendsClause());
        header = NodeFactory.makeTraitTypeHeader(NodeFactory.makeId(span,"_"),
                                                 extendsClause,
                                                 header.getDecls());
        return super.forObjectExprOnly(that, info, header);
    }

    @Override
        public Node forTraitDecl(TraitDecl that) {
        TraitTypeHeader header_result = (TraitTypeHeader) recur(that.getHeader());
        List<BaseType> excludesClause_result = recurOnListOfBaseType(that.getExcludesClause());
        Option<List<BaseType>> comprisesClause_result = recurOnOptionOfListOfBaseType(that.getComprisesClause());

        if (!NodeUtil.getName(that).equals(anyTypeId)) {
            header_result = NodeFactory.makeTraitTypeHeader(header_result,
                                                            rewriteExtendsClause(that, header_result.getExtendsClause()));
        }

        return super.forTraitDeclOnly(that, that.getInfo(), header_result,
                                      excludesClause_result,
                                      comprisesClause_result);
    }

    @Override
        public Node forObjectDecl(ObjectDecl that) {
        TraitTypeHeader header_result = (TraitTypeHeader) recur(that.getHeader());
        Option<List<Param>> params_result = recurOnOptionOfListOfParam(that.getParams());

        header_result = NodeFactory.makeTraitTypeHeader(header_result,
                                                        rewriteExtendsClause(that, header_result.getExtendsClause()));
        return super.forObjectDeclOnly(that, that.getInfo(),
                                       header_result, params_result);
    }

    @Override
    public Node forAmbiguousMultifixOpExpr(AmbiguousMultifixOpExpr that) {
        // If there is a colon at all, the operator is no longer ambiguous:
        // It must be infix.
        IdOrOp name = that.getInfix_op().getNames().get(0);
        if ( ! (name instanceof Op) )
            return bug(name, "The name field of OpRef should be Op.");
        Op op_name = (Op)name;
        boolean prefix = OprUtil.hasPrefixColon(op_name);
        boolean suffix = OprUtil.hasSuffixColon(op_name);

        if( prefix || suffix ) {
            OpExpr new_op = ExprFactory.makeOpExpr(NodeUtil.getSpan(that),
                                                   NodeUtil.isParenthesized(that),
                                                   NodeUtil.getExprType(that),
                                                   that.getInfix_op(),
                                                   that.getArgs());
            return recur(new_op);
        }
        else {
            return super.forAmbiguousMultifixOpExpr(that);
        }
    }

    @Override
    public Node forOpExpr(OpExpr that) {
        FunctionalRef op_result = (FunctionalRef) recur(that.getOp());

        /***
         * For  BIG OP <| BIG OT <| f y | y <- ys |> | ys <- gg |>
         * Is this case, BIG <||> is being removed.
         * The nested reduction is replaced with
         *   __bigOperator2(BIG OP, BIG OT, gg)
         *
         * by Kento 
         */
        String str = op_result.toString();
        String theListEnclosingOperatorName = "BIG <| BIG |>";
        String someBigOperatorName = "BIG";
        //make sure the body is of application of some big operator
        if ((str.length() >= someBigOperatorName.length()
             && str.substring(0, someBigOperatorName.length()).equals(someBigOperatorName))) {
            // make sure that BIG OP (Accumulator (BIG <||>, gs))
            if(that.getArgs().size()==1 && that.getArgs().get(0) instanceof Accumulator && ((Accumulator)that.getArgs().get(0)).getAccOp().toString().equals(theListEnclosingOperatorName)) {

                Accumulator acc = (Accumulator)that.getArgs().get(0);
                Expr body = visitGenerators(NodeUtil.getSpan(acc), acc.getGens(), acc.getBody());
                /***
                 * If the accumulation is a nested reduction like <| BIG OT <| f y | y <- ys |> | ys <- gg |> ,
                 * visitGenerators returns a tuple of ((BIG OT, f), gg)
                 *  (this should be refactored, though)
                 * In this case, the nested reduction is replaced with __bigOperator2
                 */
                if(body instanceof TupleExpr) {
                    // a tuple of the inner Accumulator (op, body) and the gg 
                    TupleExpr tuple = (TupleExpr)body;
                    TupleExpr innerAccumTuple = (TupleExpr)tuple.getExprs().get(0);
                    Expr opexpI = (Expr)innerAccumTuple.getExprs().get(0);
                    Expr innerBody = (Expr)innerAccumTuple.getExprs().get(1);
                    FunctionalRef ref = (FunctionalRef) op_result;
                    IdOrOp name = ref.getNames().get(0);
                    // make sure the operator is actually an operator
                    if ( ! (name instanceof Op) ) return null;
                    Expr opexpO = ExprFactory.makeOpExpr(NodeUtil.getSpan(that),(Op)name,ref.getStaticArgs());                   
                    Expr gg = tuple.getExprs().get(1);
                    Expr res = ExprFactory.makeTightJuxt(NodeUtil.getSpan(that), false,
                                                         Useful.list(BIGOP2_NAME,
                                                                     ExprFactory.makeTupleExpr(NodeUtil.getSpan(body), opexpO,opexpI,gg, innerBody)));
                    return (Expr)recur(res);
                }
            }
        }

        List<Expr> args_result = recurOnListOfExpr(that.getArgs());

        OpExpr new_op;
        if( op_result == that.getOp() && args_result == that.getArgs() ) {
            new_op = that;
        }
        else {
            new_op = ExprFactory.makeOpExpr(NodeUtil.getSpan(that),
                                            NodeUtil.isParenthesized(that),
                                            NodeUtil.getExprType(that),
                                            op_result,
                                            args_result);
        }
        return cleanupOpExpr(new_op);
    }

    private static Expr thunk(Expr e) {
        return ExprFactory.makeFnExpr(NodeUtil.getSpan(e),
                                      Collections.<Param>emptyList(), e);
    }

    private Expr cleanupOpExpr(OpExpr opExp) {
        FunctionalRef ref = opExp.getOp();

        List<Expr> args = opExp.getArgs();

        if (args.size() <= 1) return opExp;
        IdOrOp name = ref.getNames().get(0);
        if ( ! (name instanceof Op) )
            return bug(name, "The name field of OpRef should be Op.");
        Op qop = (Op)name;

        if (OprUtil.isEnclosing(qop)) return opExp;
        if (OprUtil.isUnknownFixity(qop))
            return bug(opExp, "The operator fixity is unknown: " +
                       ((Op)qop).getText());
        boolean prefix = OprUtil.hasPrefixColon(qop);
        boolean suffix = OprUtil.hasSuffixColon(qop);
        if (!prefix && !suffix) return opExp;
        qop = OprUtil.noColon(qop);
        Iterator<Expr> i = args.iterator();
        Expr res = i.next();
        Span sp = NodeUtil.getSpan(opExp);
        while (i.hasNext()) {
            Expr arg = (Expr)i.next();
            if (prefix) {
                res = thunk(res);
            }
            if (suffix) {
                arg = thunk(arg);
            }
            res = ExprFactory.makeOpExpr(sp, qop, res, arg);
        }
        return res;
    }

    @Override
    public Node forAccumulator(Accumulator that) {
        return visitAccumulator(NodeUtil.getSpan(that), that.getGens(),
                                that.getAccOp(), that.getBody(),
                                that.getStaticArgs());
    }

    private Expr visitAccumulator(Span span, List<GeneratorClause> gens,
                                  Op op, Expr body,
                                  List<StaticArg> staticArgs) {
        body = visitGenerators(span, gens, body); 
        /***
         * If the accumulation is a nested reduction like BIG OP [ys <- gg] BIG OT <| f y | y <- ys |> ,
         * visitGenerators returns a tuple of ((BIG OT, f), gg)
         *  (this should be refactored, though)
         */
        Expr res = null;
        if (body instanceof FnExpr) {
            Expr opexp = ExprFactory.makeOpExpr(span,op,staticArgs);
            res = ExprFactory.makeTightJuxt(span,
                                            BIGOP_NAME,
                                            ExprFactory.makeTupleExpr(span,opexp,body));
        } else if (body instanceof TupleExpr){
            /***
             * For  BIG OP [ys <- gg] BIG OT <| f y | y <- ys |>
             * The nested reduction is replaced with
             *   __bigOperator2(BIG OP, BIG OT, gg)
             *
             * This is similar to forOpExpr(OpExpr that) .
             *
             * by Kento 
             */
            // a tuple of the inner Accumulator (op, body) and the gg 
            TupleExpr tuple = (TupleExpr)body;
            TupleExpr innerAccumTuple = (TupleExpr)tuple.getExprs().get(0);
            Expr opexpI = (Expr)innerAccumTuple.getExprs().get(0);
            Expr innerBody = (Expr)innerAccumTuple.getExprs().get(1);
            Expr opexpO = ExprFactory.makeOpExpr(span,op,staticArgs);
            Expr gg = tuple.getExprs().get(1);

            res = ExprFactory.makeTightJuxt(span,
                                            BIGOP2_NAME,
                                            ExprFactory.makeTupleExpr(span,opexpO,opexpI,gg, innerBody));
        }
        return (Expr)recur(res);
    }


    
}
