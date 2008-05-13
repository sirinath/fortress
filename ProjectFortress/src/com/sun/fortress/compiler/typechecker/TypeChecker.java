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


import com.sun.fortress.compiler.*;
import com.sun.fortress.compiler.index.ApiIndex;
import com.sun.fortress.compiler.index.CompilationUnitIndex;
import com.sun.fortress.compiler.index.ComponentIndex;
import com.sun.fortress.compiler.index.FunctionalMethod;
import com.sun.fortress.compiler.index.TraitIndex;
import com.sun.fortress.compiler.index.Method;
import com.sun.fortress.compiler.typechecker.TypeEnv.BindingLookup;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.OprUtil;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.useful.NI;

import edu.rice.cs.plt.collect.HashRelation;
import edu.rice.cs.plt.collect.Relation;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.lambda.Lambda;
import edu.rice.cs.plt.lambda.Lambda2;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;

import java.util.*;

import java.io.PrintWriter;

import static com.sun.fortress.compiler.TypeError.errorMsg;
import static edu.rice.cs.plt.tuple.Option.*;

public class TypeChecker extends NodeDepthFirstVisitor<TypeCheckerResult> {

    private TraitTable table;
    private StaticParamEnv staticParamEnv;
    private TypeEnv typeEnv;
    private final CompilationUnitIndex compilationUnit;
    private final SubtypeChecker subtypeChecker;
    private final Map<Id, Option<Set<Type>>> labelExitTypes; // Note: this is mutable state.

    public TypeChecker(TraitTable _table,
                       StaticParamEnv _staticParams,
                       TypeEnv _typeEnv,
                       CompilationUnitIndex _compilationUnit)
    {
        table = _table;
        staticParamEnv = _staticParams;
        typeEnv = _typeEnv;
        compilationUnit = _compilationUnit;
        subtypeChecker = SubtypeChecker.make(table);
        labelExitTypes = new HashMap<Id, Option<Set<Type>>>();
    }

    private TypeChecker(TraitTable _table,
                       StaticParamEnv _staticParams,
                       TypeEnv _typeEnv,
                       CompilationUnitIndex _compilationUnit,
                       SubtypeChecker _subtypeChecker,
                       Map<Id, Option<Set<Type>>> _labelExitTypes)
    {
        table = _table;
        staticParamEnv = _staticParams;
        typeEnv = _typeEnv;
        compilationUnit = _compilationUnit;
        subtypeChecker = _subtypeChecker;
        labelExitTypes = _labelExitTypes;
    }

    private static Type typeFromLValueBinds(List<LValueBind> bindings) {
        List<Type> results = new ArrayList<Type>();

        for (LValueBind binding: bindings) {
            results.add(binding.getType().unwrap());
        }
        return NodeFactory.makeTupleType(results);
    }

    private TypeChecker extend(List<StaticParam> newStaticParams, Option<List<Param>> newParams, WhereClause whereClause) {
        return new TypeChecker(table,
                               staticParamEnv.extend(newStaticParams, whereClause),
                               typeEnv.extend(newParams),
                               compilationUnit,
                               subtypeChecker.extend(newStaticParams, whereClause),
                               labelExitTypes);
    }

    private TypeChecker extend(List<StaticParam> newStaticParams, List<Param> newParams, WhereClause whereClause) {
        return new TypeChecker(table,
                               staticParamEnv.extend(newStaticParams, whereClause),
                               typeEnv.extendWithParams(newParams),
                               compilationUnit,
                               subtypeChecker.extend(newStaticParams, whereClause),
                               labelExitTypes);
    }

    private TypeChecker extend(List<StaticParam> newStaticParams, WhereClause whereClause) {
        return new TypeChecker(table,
                               staticParamEnv.extend(newStaticParams, whereClause),
                               typeEnv,
                               compilationUnit,
                               subtypeChecker.extend(newStaticParams, whereClause),
                               labelExitTypes);
    }

    private TypeChecker extend(WhereClause whereClause) {
        return new TypeChecker(table,
                               staticParamEnv.extend(Collections.<StaticParam>emptyList(), whereClause),
                               typeEnv,
                               compilationUnit,
                               subtypeChecker.extend(Collections.<StaticParam>emptyList(), whereClause),
                               labelExitTypes);
    }

    private TypeChecker extend(List<LValueBind> bindings) {
        return new TypeChecker(table, staticParamEnv,
                               typeEnv.extendWithLValues(bindings),
                               compilationUnit,
                               subtypeChecker,
                               labelExitTypes);
    }

    private TypeChecker extend(LocalVarDecl decl) {
        return new TypeChecker(table, staticParamEnv,
                               typeEnv.extend(decl),
                               compilationUnit,
                               subtypeChecker,
                               labelExitTypes);
    }

    private TypeChecker extend(Param newParam) {
        return new TypeChecker(table, staticParamEnv,
                               typeEnv.extend(newParam),
                               compilationUnit,
                               subtypeChecker,
                               labelExitTypes);
    }

    public TypeChecker extendWithMethods(Relation<IdOrOpOrAnonymousName, Method> methods) {
        return new TypeChecker(table, staticParamEnv,
                               typeEnv.extendWithMethods(methods),
                               compilationUnit,
                               subtypeChecker,
                               labelExitTypes);
    }

    public TypeChecker extendWithFunctions(Relation<IdOrOpOrAnonymousName, FunctionalMethod> methods) {
        return new TypeChecker(table, staticParamEnv,
                               typeEnv.extendWithFunctions(methods),
                               compilationUnit,
                               subtypeChecker,
                               labelExitTypes);
    }

    public TypeChecker extendWithFnDefs(Relation<IdOrOpOrAnonymousName, ? extends FnDef> fns) {
        return new TypeChecker(table, staticParamEnv,
                               typeEnv.extendWithFnDefs(fns),
                               compilationUnit,
                               subtypeChecker,
                               labelExitTypes);
    }

    public TypeChecker extendWithout(Set<? extends IdOrOpOrAnonymousName> names) {
        return new TypeChecker(table, staticParamEnv,
                               typeEnv.extendWithout(names),
                               compilationUnit,
                               subtypeChecker,
                               labelExitTypes);
    }

    /**
     * Check the subtype relation for the given types.  If subtype <: supertype, then a TypeCheckerResult
     * for the given node and corresponding type constraints will be returned.  Otherwise, a TypeCheckerResult
     * for the given node with a generic error message will be returned.
     */
    private TypeCheckerResult checkSubtype(Type subtype, Type supertype, Node ast) {
        return checkSubtype(subtype,
                            supertype,
                            ast,
                            errorMsg("Expected expression of type ", supertype, " ",
                                     "but was type ", subtype));
    }

    /**
     * Check the subtype relation for the given types.  If subtype <: supertype, then a TypeCheckerResult
     * for the given node and corresponding type constraints will be returned.  Otherwise, a TypeCheckerResult
     * for the given node with the a TypeError and the given error message will be returned.
     */
    private TypeCheckerResult checkSubtype(Type subtype, Type supertype, Node ast, String error) {
        if (!subtypeChecker.subtype(subtype, supertype)) {
            return new TypeCheckerResult(ast, TypeError.make(error, ast));
        } else {
            return new TypeCheckerResult(ast);
        }
    }


    /**
     * Check the subtype relation for the given types.  If subtype <: supertype, then a TypeCheckerResult
     * for the given node and corresponding type constraints will be returned, and the type of this node
     * will be the given type resultType.  Otherwise, a TypeCheckerResult
     * for the given node with the a TypeError and the given error message will be returned.
     */
    private TypeCheckerResult checkSubtype(Type subtype, Type supertype, Node ast, Type resultType, String error) {
        if (!subtypeChecker.subtype(subtype, supertype)) {
            return new TypeCheckerResult(ast, resultType, TypeError.make(error, ast));
        } else {
            return new TypeCheckerResult(ast, resultType);
        }
    }

    /**
     * Return an error complaining about usage of a label name as an identifier.
     * @param name the label name that is being used
     * @return a TypeError containing the error message and location
     */
    private StaticError makeLabelNameError(Id name) {
        return TypeError.make(errorMsg("Cannot use label name ", name.getText(),
                                       " as an identifier."),
                              name);
    }

    /** Ignore unsupported nodes for now. */
    /*public TypeCheckerResult defaultCase(Node that) {
        return new TypeCheckerResult(that, Types.VOID, IterUtil.<TypeError>empty());
    }*/

    public TypeCheckerResult forFnDef(FnDef that) {
        TypeChecker newChecker = this.extend(that.getStaticParams(), that.getParams(), that.getWhere());

        TypeCheckerResult contractResult = that.getContract().accept(newChecker);
        TypeCheckerResult bodyResult = that.getBody().accept(newChecker);
        TypeCheckerResult result = new TypeCheckerResult(that);

        Option<Type> returnType = that.getReturnType();
        if (bodyResult.type().isSome()) {
            Type bodyType = bodyResult.type().unwrap();
            if (returnType.isNone()) {
                returnType = wrap(bodyType);
            }

            result = checkSubtype(bodyType,
                                  returnType.unwrap(),
                                  that,
                                  errorMsg("Function body has type ", bodyType, ", but ",
                                           "declared return type is ", returnType.unwrap()));
        }

        return TypeCheckerResult.compose(new FnDef(that.getSpan(),
                                               that.getMods(),
                                               that.getName(),
                                               that.getStaticParams(),
                                               that.getParams(),
                                               returnType,
                                               that.getThrowsClause(),
                                               that.getWhere(),
                                               (Contract)contractResult.ast(),
                                               that.getSelfName(),
                                               (Expr)bodyResult.ast()),
                                     contractResult, bodyResult, result);
    }


    public TypeCheckerResult forVarDecl(VarDecl that) {
        // System.err.println("forVarDecl: " + that);
        List<LValueBind> lhs = that.getLhs();
        Expr init = that.getInit();

        TypeCheckerResult initResult = init.accept(this);

        if (lhs.size() == 1) { // We have a single variable binding, not a tuple binding
            LValueBind var = lhs.get(0);
            Option<Type> varType = var.getType();
            if (varType.isSome()) {
                // System.err.println("varType: " + varType.unwrap());
                // System.err.println("initResult.type(): " + initResult.type());
                if (initResult.type().isNone()) {
                    // System.err.println("initResult.ast(): " + initResult.ast());
                    // The right hand side could not be typed, which must have resulted in a
                    // signaled error. No need to signal another error.
                    return TypeCheckerResult.compose(new VarDecl(that.getSpan(),
                                                                 lhs,
                                                                 (Expr)initResult.ast()),
                                                     initResult);
                }
                return checkSubtype(initResult.type().unwrap(),
                                    varType.unwrap(),
                                    that,
                                    errorMsg("Attempt to define variable ", var, " ",
                                             "with an expression of type ", initResult.type().unwrap()));
            } else { // Eventually, this case will involve type inference
                // System.err.println("varType.isNone()");
                return NI.nyi();
            }
        } else { // lhs.size() >= 2
            // System.err.println("lhs.size() >= 2");
            Type varType = typeFromLValueBinds(lhs);
            if (initResult.type().isNone()) {
                // The right hand side could not be typed, which must have resulted in a
                // signaled error. No need to signal another error.
                return TypeCheckerResult.compose(new VarDecl(that.getSpan(),
                                                             lhs,
                                                             (Expr)initResult.ast()),
                                                 initResult);
            }
            return checkSubtype(initResult.type().unwrap(),
                                varType,
                                that,
                                errorMsg("Attempt to define variables ", lhs, " ",
                                         "with an expression of type ", initResult.type().unwrap()));
        }
    }

    public TypeCheckerResult forId(Id name) {
        Option<APIName> apiName = name.getApi();
        if (apiName.isSome()) {
            APIName api = apiName.unwrap();
            TypeEnv apiTypeEnv;
            if (compilationUnit.ast().getName().equals(api)) {
                apiTypeEnv = typeEnv;
            } else {
                apiTypeEnv = TypeEnv.make(table.compilationUnit(api));
            }

            Option<Type> type = apiTypeEnv.type(name);
            if (type.isSome()) {
                Type _type = type.unwrap();
                if (_type instanceof NamedType) { // Do we need to qualify?
                    NamedType _namedType = (NamedType)_type;

                    // Type was declared in that API, so it's not qualified;
                    // prepend it with the API.
                    if (_namedType.getName().getApi().isNone()) {
                        _type = NodeFactory.makeNamedType(api, (NamedType) type.unwrap());
                    }
                }
                return new TypeCheckerResult(name, _type);
            } else {
                // Operators are never qualified in source code, so if 'name' is qualified and not
                // found, it must be a Id, not a OpName.
                StaticError error = TypeError.make(errorMsg("Attempt to reference unbound variable: ", name),
                                                   name);
                return new TypeCheckerResult(name, error);
            }
        }
        Option<Type> type = typeEnv.type(name);
        if (type.isSome()) {
            Type _type = type.unwrap();
            if (_type instanceof LabelType) { // then name must be an Id
                return new TypeCheckerResult(name, makeLabelNameError((Id)name));
            } else {
                return new TypeCheckerResult(name, _type);
            }
        } else {
            StaticError error;
            error = TypeError.make(errorMsg("Variable '", name, "' not found."),
                                   name);
            return new TypeCheckerResult(name, error);
        }
    }

    public TypeCheckerResult forAPIName(APIName that) {
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forExportOnly(Export that, List<TypeCheckerResult> apis_result) {
        return new TypeCheckerResult(that);
    }

    private TypeCheckerResult forIdOrOpOrAnonymousName(IdOrOpOrAnonymousName that) {
        Option<APIName> apiName = that.getApi();
        if (apiName.isSome()) {
            APIName api = apiName.unwrap();
            TypeEnv apiTypeEnv;
            if (compilationUnit.ast().getName().equals(api)) {
                apiTypeEnv = typeEnv;
            } else {
                apiTypeEnv = TypeEnv.make(table.compilationUnit(api));
            }

            Option<Type> type = apiTypeEnv.type(that);
            if (type.isSome()) {
                Type _type = type.unwrap();
                if (_type instanceof NamedType) { // Do we need to qualify?
                    NamedType _namedType = (NamedType)_type;

                    // Type was declared in that API, so it's not qualified;
                    // prepend it with the API.
                    if (_namedType.getName().getApi().isNone()) {
                        _type = NodeFactory.makeNamedType(api, (NamedType) type.unwrap());
                    }
                }
                return new TypeCheckerResult(that, _type);
            } else {
                // Operators are never qualified in source code, so if 'that' is qualified and not
                // found, it must be a Id, not a OpName.
                StaticError error = TypeError.make(errorMsg("Attempt to reference unbound variable: ", that),
                                                   that);
                return new TypeCheckerResult(that, error);
            }
        }
        Option<Type> type = typeEnv.type(that);
        if (type.isSome()) {
            Type _type = type.unwrap();
            if (_type instanceof LabelType) { // then name must be an Id
                return new TypeCheckerResult(that, makeLabelNameError((Id)that));
            } else {
                return new TypeCheckerResult(that, _type);
            }
        } else {
            StaticError error;
            if (that instanceof Id) {
                error = TypeError.make(errorMsg("Variable '", that, "' not found."),
                                       that);
            } else if (that instanceof Op) {
                error = TypeError.make(errorMsg("Operator '", OprUtil.decorateOperator((Op)that),
                                                "' not found."),
                                       that);
            } else { // must be Enclosing
                error = TypeError.make(errorMsg("Enclosing operator '", (Enclosing)that, "' not found."),
                                       that);
            }
            return new TypeCheckerResult(that, error);
        }
    }

    public TypeCheckerResult forVarRefOnly(VarRef that, TypeCheckerResult var_result) {
        Option<Type> varType = var_result.type();
        if (varType.isSome()) {
            return TypeCheckerResult.compose(that, varType.unwrap(), var_result);
        } else {
            return TypeCheckerResult.compose(that, var_result);
        }
    }

    public TypeCheckerResult forObjectDecl(ObjectDecl that) {
        TypeCheckerResult modsResult = TypeCheckerResult.compose(that, recurOnListOfModifier(that.getMods()));
        TypeCheckerResult nameResult = that.getName().accept(this);
        TypeCheckerResult staticParamsResult = TypeCheckerResult.compose(that, recurOnListOfStaticParam(that.getStaticParams()));
        TypeCheckerResult extendsClauseResult = TypeCheckerResult.compose(that, recurOnListOfTraitTypeWhere(that.getExtendsClause()));
        TypeCheckerResult whereResult = that.getWhere().accept(this);
        TypeCheckerResult paramsResult = TypeCheckerResult.compose(that, recurOnOptionOfListOfParam(that.getParams()));
        TypeCheckerResult throwsClauseResult = TypeCheckerResult.compose(that, recurOnOptionOfListOfBaseType(that.getThrowsClause()));

        TypeChecker newChecker = this.extend(that.getStaticParams(), that.getParams(), that.getWhere());
        TypeCheckerResult contractResult = that.getContract().accept(newChecker);

        // Check field declarations.
        TypeCheckerResult fieldsResult = new TypeCheckerResult(that);
        for (Decl decl: that.getDecls()) {
            if (decl instanceof VarDecl) {
                VarDecl _decl = (VarDecl)decl;
                fieldsResult = TypeCheckerResult.compose(that, _decl.accept(newChecker), fieldsResult);
                newChecker = newChecker.extend(_decl.getLhs());
            }
        }

        // Check method declarations.

        TraitIndex thatIndex = (TraitIndex)table.typeCons(that.getName());
        newChecker = newChecker.extendWithMethods(thatIndex.dottedMethods());
        newChecker = newChecker.extendWithFunctions(thatIndex.functionalMethods());

        TypeCheckerResult methodsResult = new TypeCheckerResult(that);
        for (Decl decl: that.getDecls()) {
            if (decl instanceof FnDecl) {
                methodsResult = TypeCheckerResult.compose(that, decl.accept(newChecker), methodsResult);
            }
        }

        return TypeCheckerResult.compose(that, modsResult, nameResult, staticParamsResult,
                                         extendsClauseResult, whereResult, paramsResult, throwsClauseResult,
                                         contractResult, fieldsResult, methodsResult);
    }

    public TypeCheckerResult forTraitDecl(TraitDecl that) {
        TypeCheckerResult modsResult = TypeCheckerResult.compose(that, recurOnListOfModifier(that.getMods()));
        TypeCheckerResult staticParamsResult = TypeCheckerResult.compose(that, recurOnListOfStaticParam(that.getStaticParams()));
        TypeCheckerResult extendsClauseResult = TypeCheckerResult.compose(that, recurOnListOfTraitTypeWhere(that.getExtendsClause()));
        TypeCheckerResult whereResult = that.getWhere().accept(this);
        TypeCheckerResult excludesResult = TypeCheckerResult.compose(that, recurOnListOfBaseType(that.getExcludes()));

        TypeCheckerResult comprisesResult = new TypeCheckerResult(that);
        Option<List<BaseType>> comprises = that.getComprises();
        if (comprises.isSome()) {
            comprisesResult =
                TypeCheckerResult.compose
                    (that, recurOnOptionOfListOfBaseType(that.getComprises()).unwrap());
        }

        TypeChecker newChecker = this.extend(that.getStaticParams(), that.getWhere());

        // Check "field" declarations (really getter and setter declarations).
        TypeCheckerResult fieldsResult = new TypeCheckerResult(that);
        for (Decl decl: that.getDecls()) {
            if (decl instanceof VarDecl) {
                VarDecl _decl = (VarDecl)decl;

                fieldsResult = TypeCheckerResult.compose(that, _decl.accept(newChecker), fieldsResult);
                newChecker = newChecker.extend(_decl.getLhs());
            }
        }

        // Check method declarations.

        TraitIndex thatIndex = (TraitIndex)table.typeCons(that.getName());
        newChecker = newChecker.extendWithMethods(thatIndex.dottedMethods());
        newChecker = newChecker.extendWithFunctions(thatIndex.functionalMethods());

        TypeCheckerResult methodsResult = new TypeCheckerResult(that);
        for (Decl decl: that.getDecls()) {
            if (decl instanceof FnDecl) {
                methodsResult = TypeCheckerResult.compose(that, decl.accept(newChecker), methodsResult);
            }
        }

        return TypeCheckerResult.compose(that, modsResult, staticParamsResult,
                                         extendsClauseResult, whereResult, excludesResult, comprisesResult,
                                         fieldsResult, methodsResult);
    }

//    public TypeCheckerResult forVarRefOnly(VarRef that, TypeCheckerResult var_result) {
//        if (var_result.isSome()) {
//            return new TypeCheckerResult(that, varType);
//        } else {
//            TypeError error =
//                TypeError.make(errorMsg("Attempt to reference unbound variable: ", that),
//                                 that);
//            return new TypeCheckerResult(that, error);
//        }
//    }

    public TypeCheckerResult forIfOnly(If that,
                                       List<TypeCheckerResult> clauses_result,
                                       Option<TypeCheckerResult> elseClause_result) {

        if (elseClause_result.isSome()) {
            Type clauseType = Types.BOTTOM;
            TypeCheckerResult elseResult = elseClause_result.unwrap();

            // Get union of all clauses' types
            for (TypeCheckerResult clauseResult : clauses_result) {
                if (clauseResult.type().isSome()) {
                    clauseType = new OrType(clauseType, clauseResult.type().unwrap());
                }
            }
            if (elseResult.type().isSome()) {
                clauseType = new OrType(clauseType, elseResult.type().unwrap());
            }
            return TypeCheckerResult.compose(that,
                                             clauseType,
                                             TypeCheckerResult.compose(that, clauses_result),
                                             TypeCheckerResult.compose(that, elseResult));
        } else {
            // Check that each if/elif clause has void type
            TypeCheckerResult result = new TypeCheckerResult(that);
            for (TypeCheckerResult clauseResult : clauses_result) {
                if (clauseResult.type().isSome()) {
                    Type clauseType = clauseResult.type().unwrap();
                    result = TypeCheckerResult.compose(
                        that,
                        result,
                        checkSubtype(clauseType,
                                     Types.VOID,
                                     that,
                                     errorMsg("An 'if' clause without corresponding 'else' has type ",
                                              clauseType, " instead of type ()")));
                }
            }
            return TypeCheckerResult.compose(that,
                                             Types.VOID,
                                             TypeCheckerResult.compose(that, clauses_result),
                                             result);
        }
    }

    public TypeCheckerResult forIfClauseOnly(IfClause that,
                                             TypeCheckerResult test_result,
                                             TypeCheckerResult body_result) {

        TypeCheckerResult result = new TypeCheckerResult(that);

        // Check that test condition is Boolean.
        if (test_result.type().isSome()) {
            Type testType = test_result.type().unwrap();
            result = TypeCheckerResult.compose(
                that,
                checkSubtype(testType,
                             Types.BOOLEAN,
                             that,
                             errorMsg("Attempt to use expression of type ", testType, " ",
                                      "as a test condition")),
                result);
        }

        // IfClause's type is body's type.
        if (body_result.type().isSome()) {
            return TypeCheckerResult.compose(that, body_result.type().unwrap(), test_result, body_result, result);
        } else {
            return TypeCheckerResult.compose(that, test_result, body_result, result);
        }
    }

    public TypeCheckerResult forGeneratorClauseOnly(GeneratorClause that,
                                                    List<TypeCheckerResult> bind_result,
                                                    TypeCheckerResult init_result) {
        TypeCheckerResult result = new TypeCheckerResult(that);
        return TypeCheckerResult.compose(that, init_result, result);
    }

    public TypeCheckerResult forDoOnly(Do that, List<TypeCheckerResult> fronts_result) {
        // Get union of all clauses' types
        Type frontType = Types.BOTTOM;
        for (TypeCheckerResult frontResult : fronts_result) {
            if (frontResult.type().isSome()) {
                frontType = new OrType(frontType, frontResult.type().unwrap());
            }
        }
        return TypeCheckerResult.compose(that, frontType, fronts_result);
    }

    public TypeCheckerResult forDoFront(DoFront that) {
        TypeCheckerResult bodyResult =
            that.isAtomic() ? forAtomic(that,
                                        that.getExpr(),
                                        errorMsg("A 'spawn' expression must not occur inside",
                                                 "an 'atomic' do block."))
                            : that.getExpr().accept(this);
        TypeCheckerResult result = new TypeCheckerResult(that);
        if (that.getLoc().isSome()) {
            Expr loc = that.getLoc().unwrap();
            result = loc.accept(this);
            if (result.type().isSome()) {
                Type locType = result.type().unwrap();
                result = TypeCheckerResult.compose(that,
                                                   result,
                                                   checkSubtype(locType,
                                                                Types.REGION,
                                                                loc,
                                                                errorMsg("Location of 'do' block must ",
                                                                         "have type Region: ", locType)));
            }
        }
        return TypeCheckerResult.compose(that, bodyResult.type(), bodyResult, result);
    }

    public TypeCheckerResult forFnRefOnly(FnRef that,
                                          List<TypeCheckerResult> fns_result,
                                          List<TypeCheckerResult> staticArgs_result) {

        // Get intersection of overloaded function types.
        LinkedList<Type> overloadedTypes = new LinkedList<Type>();
        for (TypeCheckerResult fn_result : fns_result) {
            if (fn_result.type().isSome()) {
              overloadedTypes.add(fn_result.type().unwrap());
            }
        }
        Option<Type> type = (overloadedTypes.isEmpty()) ? Option.<Type>none()
                                                        : wrap(NodeFactory.makeAndType(overloadedTypes));

        return TypeCheckerResult.compose(that,
                                         type,
                                         TypeCheckerResult.compose(that, fns_result),
                                         TypeCheckerResult.compose(that, staticArgs_result));
    }

    public TypeCheckerResult forOpRefOnly(OpRef that,
                                          List<TypeCheckerResult> ops_result,
                                          List<TypeCheckerResult> staticArgs_result) {

        // Get intersection of overloaded operator types.
        LinkedList<Type> overloadedTypes = new LinkedList<Type>();
        for (TypeCheckerResult op_result : ops_result) {
            if (op_result.type().isSome()) {
              overloadedTypes.add(op_result.type().unwrap());
            }
        }
        Option<Type> type = (overloadedTypes.isEmpty()) ? Option.<Type>none()
                                                        : wrap(NodeFactory.makeAndType(overloadedTypes));
        return TypeCheckerResult.compose(that,
                                         type,
                                         TypeCheckerResult.compose(that, ops_result),
                                         TypeCheckerResult.compose(that, staticArgs_result));
    }

    public TypeCheckerResult forBlockOnly(Block that, List<TypeCheckerResult> exprs_result) {
        // Type is type of last expression or void if none.
        if (exprs_result.isEmpty()) {
            return TypeCheckerResult.compose(that, Types.VOID, exprs_result);
        } else {
            return TypeCheckerResult.compose(that, exprs_result.get(exprs_result.size()-1).type(), exprs_result);
        }
    }

    public TypeCheckerResult forLetFn(LetFn that) {
        TypeCheckerResult result = new TypeCheckerResult(that);
        Relation<IdOrOpOrAnonymousName, FnDef> fnDefs = new HashRelation<IdOrOpOrAnonymousName, FnDef>(true, false);
        for (FnDef fnDef : that.getFns()) {
            fnDefs.add(fnDef.getName(), fnDef);
        }

        TypeChecker newChecker = this.extendWithFnDefs(fnDefs);
        for (FnDef fnDef : that.getFns()) {
            result = TypeCheckerResult.compose(that, result, fnDef.accept(newChecker));
        }
        result = TypeCheckerResult.compose(that,
                                           result,
                                           TypeCheckerResult.compose(that,
                                                                     newChecker.recurOnListOfExpr(that.getBody())));
        return result;
    }

    public TypeCheckerResult forLocalVarDecl(LocalVarDecl that) {
        TypeCheckerResult result = new TypeCheckerResult(that);
        if (that.getRhs().isSome()) {
            result = TypeCheckerResult.compose(that, result, that.getRhs().unwrap().accept(this));
        }
        TypeChecker newChecker = this.extend(that);
        return TypeCheckerResult.compose(that,
                                         result,
                                         TypeCheckerResult.compose(that,
                                                                   newChecker.recurOnListOfExpr(that.getBody())));
    }

    public TypeCheckerResult forArgExprOnly(ArgExpr that,
                                            List<TypeCheckerResult> exprs_result,
                                            Option<TypeCheckerResult> varargs_result,
                                            List<TypeCheckerResult> keywords_result) {
        if (varargs_result.isSome()) {
            return TypeCheckerResult.compose(that,
                                             TypeCheckerResult.compose(that, exprs_result),
                                             TypeCheckerResult.compose(that, varargs_result.unwrap()),
                                             TypeCheckerResult.compose(that, keywords_result));
        } else {
            return TypeCheckerResult.compose(that,
                                             TypeCheckerResult.compose(that, exprs_result),
                                             TypeCheckerResult.compose(that, keywords_result));
        }
    }

    private TypeCheckerResult forTypeAnnotatedExprOnly(TypeAnnotatedExpr that,
                                                       TypeCheckerResult expr_result,
                                                       String errorMsg) {
        // Check that expression type <: annotated type.
        Type annotatedType = that.getType();
        if (expr_result.type().isSome()) {
            Type exprType = expr_result.type().unwrap();
            return TypeCheckerResult.compose(
                that,
                annotatedType,
                expr_result,
                checkSubtype(exprType,
                             annotatedType,
                             expr_result.ast(),
                             errorMsg));
        } else {
            return TypeCheckerResult.compose(that,
                                             annotatedType,
                                             expr_result);
        }
    }

    public TypeCheckerResult forAsExpr(AsExpr that) {
        Type ascriptedType = that.getType();
        TypeCheckerResult expr_result = that.getExpr().accept(this);
        Type exprType = expr_result.type().isSome() ? expr_result.type().unwrap() : Types.BOTTOM;
        return forTypeAnnotatedExprOnly(that,
                                        expr_result,
                                        errorMsg("Attempt to ascribe expression of type ",
                                                 exprType, " to non-supertype ", ascriptedType));
    }

    public TypeCheckerResult forAsIfExprOnly(AsIfExpr that) {
        Type assumedType = that.getType();
        TypeCheckerResult expr_result = that.getExpr().accept(this);
        Type exprType = expr_result.type().isSome() ? expr_result.type().unwrap() : Types.BOTTOM;
        return forTypeAnnotatedExprOnly(that,
                                        expr_result,
                                        errorMsg("Attempt to assume type ", assumedType,
                                                 " from non-subtype ", exprType));
    }

    public TypeCheckerResult forTupleExprOnly(TupleExpr that,
                                              List<TypeCheckerResult> exprs_result) {
        List<Type> types = new ArrayList<Type>(exprs_result.size());
        for (TypeCheckerResult r : exprs_result) {
            if (r.type().isNone()) {
                return TypeCheckerResult.compose(that, exprs_result);
            }
            types.add(r.type().unwrap());
        }
        return TypeCheckerResult.compose(that, NodeFactory.makeTupleType(types), exprs_result);
    }

    public TypeCheckerResult forContractOnly(Contract that,
                                             Option<List<TypeCheckerResult>> requires_result,
                                             Option<List<TypeCheckerResult>> ensures_result,
                                             Option<List<TypeCheckerResult>> invariants_result) {
        TypeCheckerResult result = new TypeCheckerResult(that);

        // Check that each 'requires' expression is Boolean
        if (requires_result.isSome()) {
            for (TypeCheckerResult r : requires_result.unwrap()) {
                if (r.type().isNone()) continue;
                Type exprType = r.type().unwrap();
                result = TypeCheckerResult.compose(
                        that,
                        result,
                        checkSubtype(exprType,
                                Types.BOOLEAN,
                                r.ast(),
                                errorMsg("Attempt to use expression of type ", exprType,
                                         " in a 'requires' clause, instead of ",Types.BOOLEAN)));
            }
        }

        return TypeCheckerResult.compose(that,
                TypeCheckerResult.compose(that, requires_result),
                TypeCheckerResult.compose(that, ensures_result),
                TypeCheckerResult.compose(that, invariants_result),
                                         result);
    }

    @Override
    public TypeCheckerResult forCaseExpr(CaseExpr that) {
        TypeCheckerResult result;
        Type caseType = null;

        // Check if we are dealing with a normal case (i.e. not a "most" case)
        if (that.getParam().isSome()) {
            Expr param = that.getParam().unwrap();
            result = TypeCheckerResult.compose(that, forCaseExprNormal(that, param));
        } else {
            result = TypeCheckerResult.compose(that, forCaseExprMost(that));
        }
        return TypeCheckerResult.compose(that, wrap(caseType), result);
    }

    private TypeCheckerResult forCaseExprNormal(CaseExpr that, Expr param) {
        TypeCheckerResult result = new TypeCheckerResult(that);

        // Try to type check everything before giving up on an error.
        TypeCheckerResult paramResult = param.accept(this);
        result = TypeCheckerResult.compose(that, result, paramResult);

        // Maps a distinct guard types to the first guard expr with said type
        Relation<Type, Expr> guards = new HashRelation<Type, Expr>(true, false);
        List<Type> clauseTypes = new ArrayList<Type>(that.getClauses().size()+1);
        int numClauses = 0;

        // Type check each guard and block
        for (CaseClause clause : that.getClauses()) {
            TypeCheckerResult guardResult = clause.getMatch().accept(this);
            result = TypeCheckerResult.compose(that, result, guardResult);
            if (guardResult.type().isSome()) {
                guards.add(guardResult.type().unwrap(), clause.getMatch());
            }
            TypeCheckerResult blockResult = clause.getBody().accept(this);
            result = TypeCheckerResult.compose(that, result, blockResult);
            if (blockResult.type().isSome()) {
                clauseTypes.add(blockResult.type().unwrap());
            }
            ++numClauses;
        }

        // Type check the else clause
        if (that.getElseClause().isSome()) {
            TypeCheckerResult blockResult = that.getElseClause().unwrap().accept(this);
            result = TypeCheckerResult.compose(that, result, blockResult);
            if (blockResult.type().isSome()) {
                clauseTypes.add(blockResult.type().unwrap());
            }
            ++numClauses;
        }

        // Type check compare operator if given, otherwise check IN and EQ
        Type givenOpType = null;
        Type inOpType = null;
        Type eqOpType = null;
        // TODO: Change these to be qualified operators
        Op givenOp = that.getCompare().unwrap(null);
        Op inOp = new Op("IN", some((Fixity)new InFixity()));
        Op eqOp = new Op("EQ", some((Fixity)new InFixity()));
        if (that.getCompare().isSome()) {
            TypeCheckerResult opResult = givenOp.accept(this);
            result = TypeCheckerResult.compose(that, result, opResult);
            givenOpType = opResult.type().unwrap(null);
        } else {
            inOpType = inOp.accept(this).type().unwrap(null);
            eqOpType = eqOp.accept(this).type().unwrap(null);
        }

        // Check if failures prevent us from continuing
        if ((givenOpType == null && inOpType == null && eqOpType == null)
                || paramResult.type().isNone()) {
            return result;
        }

        // Init some types
        Type paramType = paramResult.type().unwrap();
        Type paramGeneratorType = TypesUtil.makeGeneratorType(paramType);

        // Type check "paramExpr OP guardExpr" for each distinct type
        for (Type guardType : guards.firstSet()) {

            Op op = givenOp;
            Type opType = givenOpType;
            if (opType == null) {
                if (subtypeChecker.subtype(guardType, paramGeneratorType)) {
                    op = inOp;
                    opType = inOpType;
                } else {
                    op = eqOp;
                    opType = eqOpType;
                }
            }

            Option<Type> applicationType =
                TypesUtil.applicationType(subtypeChecker, opType,
                                          TypesUtil.argsToType(paramType, guardType));

            // Check if "opType paramType guardType" application has type Boolean
            if (applicationType.isSome() && subtypeChecker.subtype(applicationType.unwrap(), Types.BOOLEAN)) {
                for (Expr guardExpr : guards.getSeconds(guardType)) {
                    result = TypeCheckerResult.compose(that, result,
                            new TypeCheckerResult(guardExpr,
                                    TypeError.make(errorMsg("Guard expression has type ", guardType, ", which is invalid ",
                                                            "for 'case' parameter type ", paramType, " and operator ",
                                                            op.getText(), "."),
                                                   guardExpr)));
                }
            }
        }

        // Get the type of the whole expression
        Type caseType = null;
        if (numClauses == clauseTypes.size()) {
            // Only set a type for this node if all clauses were typed
            caseType = NodeFactory.makeOrType(clauseTypes);
        }
        return TypeCheckerResult.compose(that, caseType, result);
    }

    private TypeCheckerResult forCaseExprMost(CaseExpr that) {
        TypeCheckerResult result = new TypeCheckerResult(that);

        // Try to type check everything before giving up on an error.
        assert(that.getCompare().isSome());
        Op op = that.getCompare().unwrap();
        TypeCheckerResult opResult = op.accept(this);
        result = TypeCheckerResult.compose(that, result, opResult);
        Type opType = opResult.type().unwrap();

        // Maps a distinct guard types to the first guard expr with said type
        Relation<Type, Expr> guards = new HashRelation<Type, Expr>(true, false);
        List<Type> clauseTypes = new ArrayList<Type>(that.getClauses().size());

        // Type check each guard and block
        for (CaseClause clause : that.getClauses()) {
            TypeCheckerResult guardResult = clause.getMatch().accept(this);
            result = TypeCheckerResult.compose(that, result, guardResult);
            if (guardResult.type().isSome()) {
                guards.add(guardResult.type().unwrap(), clause.getMatch());
            }
            TypeCheckerResult blockResult = clause.getBody().accept(this);
            result = TypeCheckerResult.compose(that, result, blockResult);
            if (blockResult.type().isSome()) {
                clauseTypes.add(blockResult.type().unwrap());
            }
        }

        // Check if failures prevent us from continuing
        if (opResult.type().isNone()) {
            return result;
        }

        // Type check "guardExpr_i OP guardExpr_j" for each expr types i, j
        for (Pair<Type, Type> guardTypePair : IterUtil.cross(guards.firstSet(), guards.firstSet())) {
            Type guardTypeL = guardTypePair.first();
            Type guardTypeR = guardTypePair.second();

            Option<Type> applicationType =
                TypesUtil.applicationType(subtypeChecker, opType,
                                          TypesUtil.argsToType(guardTypeL, guardTypeR));

            // Check if "opType guardType_i guardType_j" application has type Boolean
            if (applicationType.isSome() && subtypeChecker.subtype(applicationType.unwrap(), Types.BOOLEAN)) {

                // The list of expressions for which to generate errors is got from both types'
                // lists of guard expressions. If the types are equal, do not compose these lists.
                Iterable<Expr> guardExprsForTypes =
                    (guardTypeL.equals(guardTypeR)) ? guards.getSeconds(guardTypeL)
                                                    : IterUtil.compose(guards.getSeconds(guardTypeL),
                                                                       guards.getSeconds(guardTypeR));
                for (Expr guardExpr : guardExprsForTypes) {
                    result = TypeCheckerResult.compose(that, result,
                            new TypeCheckerResult(guardExpr,
                                    TypeError.make(errorMsg("Guard expression types are invalid for ",
                                                            "extremum operator: ", guardTypeL, " ",
                                                            op.getText(), " ", guardTypeR),
                                                   guardExpr)));
                }
            }
        }

        // Get the type of the whole expression
        Type caseType = null;
        if (that.getClauses().size() == clauseTypes.size()) {
            // Only set a type for this node if all clauses were typed
            caseType = NodeFactory.makeOrType(clauseTypes);
        }
        return TypeCheckerResult.compose(that, caseType, result);
    }

//    public TypeCheckerResult forChainExpr(ChainExpr that) {
//        TypeCheckerResult result = new TypeCheckerResult(that);
//        TypeCheckerResult first_result = that.getFirst().accept(this);
//        final TypeChecker checker = this;
//
//
//        IterUtil.fold(that.getLinks(), first_result, new Lambda2<TypeCheckerResult, Pair<Op, Expr>, TypeCheckerResult>() {
//            public TypeCheckerResult value(TypeCheckerResult r, Pair<Op, Expr> p) {
//                TypeCheckerResult expr_result = p.getB().accept(checker);
//                Option<Type> opType = checker.params.type(p.getA());
//
//                if (r.type().isSome()) {
//                }
//                return null;
//            }
//        });
//
//        return null;
//    }

    public TypeCheckerResult forOp(Op that) {
        Option<BindingLookup> binding = typeEnv.binding(that);
        if (binding.isSome()) {
            return new TypeCheckerResult(that, binding.unwrap().getType());
        } else {
            return new TypeCheckerResult(that,
                                         TypeError.make(errorMsg("Operator not found: ",
                                                                 OprUtil.decorateOperator(that)),
                                                        that));
        }
    }

    public TypeCheckerResult forEnclosing(Enclosing that) {
        Option<BindingLookup> binding = typeEnv.binding(that);
        if (binding.isSome()) {
            return new TypeCheckerResult(that, binding.unwrap().getType());
        } else {
            return new TypeCheckerResult(that,
                                         TypeError.make(errorMsg("Enclosing operator not found: ",
                                                                 that),
                                                        that));
        }
    }

    public TypeCheckerResult forOpExprOnly(OpExpr that,
                                           TypeCheckerResult op_result,
                                           List<TypeCheckerResult> args_result) {
        Option<Type> applicationType = none();
        if (op_result.type().isSome()) {
            Type arrowType = op_result.type().unwrap();
            List<Type> argTypes = new ArrayList<Type>(args_result.size());
            boolean success = true;
            for (TypeCheckerResult r : args_result) {
                if (r.type().isNone()) {
                    success = false;
                    break;
                }
                argTypes.add(r.type().unwrap());
            }
            if (success) {
                applicationType = TypesUtil.applicationType(subtypeChecker, arrowType,
                                                            TypesUtil.argsToType(argTypes));
                if (applicationType.isNone()) {
                    // Guaranteed at least one operator because all the overloaded operators
                    // are created by disambiguation, not by the user.
                    OpName opName = that.getOp().getOps().get(0);
                    return TypeCheckerResult.compose(that,
                            op_result,
                            TypeCheckerResult.compose(that, args_result),
                            new TypeCheckerResult(that, TypeError.make(errorMsg("Call to operator ",
                                                                                opName,
                                                                                " has invalid arguments."),
                                                                       that)));
                }
            }
        }
        return TypeCheckerResult.compose(that,
                                         applicationType,
                                         op_result,
                                         TypeCheckerResult.compose(that, args_result));
    }

    public TypeCheckerResult forTightJuxtOnly(TightJuxt that,
                                              List<TypeCheckerResult> exprs_result) {
        // The expressions list contains at least two elements.
        assert (exprs_result.size() >= 2);

        Type lhsType = exprs_result.get(0).type().unwrap();
        Type rhsType = exprs_result.get(1).type().unwrap();

        // TODO: If LHS is not a function, treat juxtaposition as operator.
        return TypeCheckerResult.compose(that,
                                         TypesUtil.applicationType(subtypeChecker,
                                                                   lhsType,
                                                                   rhsType),
                                         exprs_result);
    }

    public TypeCheckerResult forLabel(Label that) {

        // Make sure this label isn't already bound
        Option<BindingLookup> b = typeEnv.binding(that.getName());
        if (b.isSome()) {
            TypeCheckerResult bodyResult = that.getBody().accept(this);
            return TypeCheckerResult.compose(that,
                new TypeCheckerResult(that, TypeError.make(errorMsg("Cannot use an existing identifier ",
                                                                    "for a 'label' expression: ",
                                                                    that.getName()),
                                                           that)),
                bodyResult);
        }

        // Check for nested label of same name
        if (labelExitTypes.containsKey(that.getName())) {
            TypeCheckerResult bodyResult = that.getBody().accept(this);
            return TypeCheckerResult.compose(that,
                new TypeCheckerResult(that, TypeError.make(errorMsg("Name of 'label' expression ",
                                                                    "already in scope: ", that.getName()),
                                                           that)),
                bodyResult);
        }

        // Initialize the set of exit types
        labelExitTypes.put(that.getName(), some((Set<Type>)new HashSet<Type>()));

        // Extend the checker with this label name in the type env
        TypeChecker newChecker = this.extend(Collections.singletonList(NodeFactory.makeLValue(that.getName(), Types.LABEL)));
        TypeCheckerResult bodyResult = that.getBody().accept(newChecker);

        // If the body was typed, union all the exit types with it.
        // If any exit type is none, then don't type this label.
        Option<Type> labelType = none();
        if (bodyResult.type().isSome()) {
            Option<Set<Type>> exitTypes = labelExitTypes.get(that.getName());
            if (exitTypes.isSome()) {
                Set<Type> _exitTypes = exitTypes.unwrap();
                _exitTypes.add(bodyResult.type().unwrap());
                labelType = some(NodeFactory.makeOrType(_exitTypes));
            }
        }

        // Destroy the mappings for this label
        labelExitTypes.remove(that.getName());
        return TypeCheckerResult.compose(that, labelType, bodyResult);
    }

    public TypeCheckerResult forExit(Exit that) {
        assert (that.getTarget().isSome()); // Filled in by disambiguator
        Id labelName = that.getTarget().unwrap();
        Option<BindingLookup> b = typeEnv.binding(labelName);
        if (b.isNone()) {
            TypeCheckerResult withResult = that.getReturnExpr().unwrap().accept(this);
            return TypeCheckerResult.compose(
                    that,
                    Types.BOTTOM,
                    new TypeCheckerResult(that,
                                          TypeError.make(errorMsg("Could not find 'label' expression with name: ",
                                                                  labelName),
                                                         labelName)),
                    withResult);
        }
        Type targetType = b.unwrap().getType().unwrap(null);
        if (!(targetType instanceof LabelType)) {
            TypeCheckerResult withResult = that.getReturnExpr().unwrap().accept(this);
            return TypeCheckerResult.compose(
                    that,
                    Types.BOTTOM,
                    new TypeCheckerResult(that,
                                         TypeError.make(errorMsg("Target of 'exit' expression is not a label name: ",
                                                                 labelName),
                                                        labelName)),
                    withResult);
        }

        // Append the 'with' type to the list for this label
        assert (that.getReturnExpr().isSome()); // Filled in by disambiguator
        TypeCheckerResult withResult = that.getReturnExpr().unwrap().accept(this);
        if (withResult.type().isNone()) {
            labelExitTypes.put(labelName, Option.<Set<Type>>none());
        } else {
            labelExitTypes.get(labelName).unwrap().add(withResult.type().unwrap());
        }
        return TypeCheckerResult.compose(that, Types.BOTTOM, withResult);
    }

    public TypeCheckerResult forSpawn(Spawn that) {
        // Create a new type checker that conceals any labels
        TypeChecker newChecker = this.extendWithout(labelExitTypes.keySet());
        TypeCheckerResult bodyResult = that.getBody().accept(newChecker);
        if (bodyResult.type().isSome()) {
            return TypeCheckerResult.compose(that,
                                             TypesUtil.makeThreadType(bodyResult.type().unwrap()),
                                             bodyResult);
        } else {
            return TypeCheckerResult.compose(that, bodyResult);
        }
    }

    public TypeCheckerResult forAtomicExpr(AtomicExpr that) {
        return forAtomic(that,
                         that.getExpr(),
                         errorMsg("A 'spawn' expression must not occur inside an 'atomic' expression."));
    }

    private TypeCheckerResult forAtomic(Node that, Expr body, final String errorMsg) {
        TypeChecker newChecker = new TypeChecker(table,
                                                 staticParamEnv,
                                                 typeEnv,
                                                 compilationUnit,
                                                 subtypeChecker,
                                                 labelExitTypes) {
            @Override public TypeCheckerResult forSpawn(Spawn that) {
                // Use TypeChecker's forSpawn method, but compose an error onto the result
                return TypeCheckerResult.compose(
                        that,
                        new TypeCheckerResult(that,
                                             TypeError.make(errorMsg,
                                                            that)),
                        that.accept(TypeChecker.this));
            }
        };
        TypeCheckerResult bodyResult = body.accept(newChecker);
        return TypeCheckerResult.compose(that, bodyResult.type(), bodyResult);
    }

    // TRIVIAL NODES ---------------------

    public TypeCheckerResult forFloatLiteralExpr(FloatLiteralExpr that) {
        return new TypeCheckerResult(that, Types.FLOAT_LITERAL);
    }

    public TypeCheckerResult forIntLiteralExpr(IntLiteralExpr that) {
        return new TypeCheckerResult(that, Types.INT_LITERAL);
    }

    public TypeCheckerResult forCharLiteralExpr(CharLiteralExpr that) {
        return new TypeCheckerResult(that, Types.CHAR);
    }

    public TypeCheckerResult forStringLiteralExpr(StringLiteralExpr that) {
        return new TypeCheckerResult(that, Types.STRING);
    }

    public TypeCheckerResult forVoidLiteralExpr(VoidLiteralExpr that) {
        return new TypeCheckerResult(that, Types.VOID);
    }

    public TypeCheckerResult forAnyType(AnyType that) {
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forTraitType(TraitType that) {
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forNormalParam(NormalParam that) {
        // No checks needed to be performed on a NormalParam.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forTypeParam(TypeParam that) {
        // No checks needed to be performed on a TypeParam.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forTypeArg(TypeArg that) {
        // No checks needed to be performed on a TypeArg.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forInFixity(InFixity that) {
        // No checks needed to be performed on a InFixity.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forPreFixity(PreFixity that) {
        // No checks needed to be performed on a PreFixity.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forPostFixity(PostFixity that) {
        // No checks needed to be performed on a PostFixity.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forNoFixity(NoFixity that) {
        // No checks needed to be performed on a NoFixity.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forMultiFixity(MultiFixity that) {
        // No checks needed to be performed on a MultiFixity.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forEnclosingFixity(EnclosingFixity that) {
        // No checks needed to be performed on a EnclosingFixity.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forBigFixity(BigFixity that) {
        // No checks needed to be performed on a BigFixity.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forImportStar(ImportStar that) {
        // No checks needed since all imports are handled by the trait table.
        return new TypeCheckerResult(that);
    }

    public TypeCheckerResult forTraitTypeWhereOnly(TraitTypeWhere that,
                                                   TypeCheckerResult type_result,
                                                   TypeCheckerResult where_result) {
        return TypeCheckerResult.compose(that, type_result, where_result);
    }

    // STUBS -----------------------------

    public TypeCheckerResult forComponentOnly(Component that,
                                              TypeCheckerResult name_result,
                                              List<TypeCheckerResult> imports_result,
                                              List<TypeCheckerResult> exports_result,
                                              List<TypeCheckerResult> decls_result) {
        return TypeCheckerResult.compose(that,
                                         name_result,
                                         TypeCheckerResult.compose(that, imports_result),
                                         TypeCheckerResult.compose(that, exports_result),
                                         TypeCheckerResult.compose(that, decls_result));
    }

    public TypeCheckerResult forWhereClause(WhereClause that) {
        if (that.getBindings().isEmpty() && that.getConstraints().isEmpty()) {
            return new TypeCheckerResult(that);
        } else {
            return defaultCase(that);
        }
    }

}

    /* Methods copied from superclass, to make it easier to incrementally define
     * overridings here.
     */

//    public RetType forAbsTraitDecl(AbsTraitDecl that) {
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        RetType where_result = that.getWhere().accept(this);
//        List<RetType> excludes_result = recurOnListOfBaseType(that.getExcludes());
//        Option<List<RetType>> comprises_result = recurOnOptionOfListOfBaseType(that.getComprises());
//
//
//        List<RetType> decls_result = recurOnListOfAbsDecl(that.getDecls());
//        return forAbsTraitDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result,
//                                   where_result, excludes_result, comprises_result, decls_result);
//    }
//
//    public RetType forTraitDecl(TraitDecl that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        RetType where_result = that.getWhere().accept(this);
//        List<RetType> excludes_result = recurOnListOfBaseType(that.getExcludes());
//        Option<List<RetType>> comprises_result = recurOnOptionOfListOfBaseType(that.getComprises());
//        List<RetType> decls_result = recurOnListOfDecl(that.getDecls());
//        return forTraitDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result,
//                                where_result, excludes_result, comprises_result, decls_result);
//    }
//
//    public RetType forAbsObjectDecl(AbsObjectDecl that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        RetType where_result = that.getWhere().accept(this);
//        Option<List<RetType>> params_result = recurOnOptionOfListOfParam(that.getParams());
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfBaseType(that.getThrowsClause());
//        RetType contract_result = that.getContract().accept(this);
//        List<RetType> decls_result = recurOnListOfAbsDecl(that.getDecls());
//        return forAbsObjectDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result,
//                                    where_result, params_result, throwsClause_result, contract_result, decls_result);
//    }

//    public RetType forObjectDecl(ObjectDecl that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        RetType where_result = that.getWhere().accept(this);
//        Option<List<RetType>> params_result = recurOnOptionOfListOfParam(that.getParams());
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfBaseType(that.getThrowsClause());
//        RetType contract_result = that.getContract().accept(this);
//        List<RetType> decls_result = recurOnListOfDecl(that.getDecls());
//        return forObjectDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result,
//                                 where_result, params_result, throwsClause_result, contract_result, decls_result);
//    }
//
//    public RetType forTraitObjectAbsDeclOrDeclOnly(TraitObjectAbsDeclOrDecl that, List<RetType> mods_result, RetType name_result,
//                                                   List<RetType> staticParams_result, List<RetType> extendsClause_result, RetType where_result,
//                                                   List<RetType> decls_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forTraitAbsDeclOrDeclOnly(TraitAbsDeclOrDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result,
//                                             List<RetType> extendsClause_result, RetType where_result, List<RetType> excludes_result,
//                                             Option<List<RetType>> comprises_result, List<RetType> decls_result) {
//        return forTraitObjectAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, decls_result);
//    }
//
//    public RetType forAbsTraitDeclOnly(AbsTraitDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result,
//                                       List<RetType> extendsClause_result, RetType where_result, List<RetType> excludes_result,
//                                       Option<List<RetType>> comprises_result, List<RetType> decls_result) {
//        return forTraitAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result,
//                                         where_result, excludes_result, comprises_result, decls_result);
//    }
//
//    public RetType forTraitDeclOnly(TraitDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result,
//                                    List<RetType> extendsClause_result, RetType where_result, List<RetType> excludes_result,
//                                    Option<List<RetType>> comprises_result, List<RetType> decls_result) {
//        return forTraitAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result,
//                                         where_result, excludes_result, comprises_result, decls_result);
//    }
//
//    public RetType forObjectAbsDeclOrDeclOnly(ObjectAbsDeclOrDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result,
//                                              List<RetType> extendsClause_result, RetType where_result, Option<List<RetType>> params_result,
//                                              Option<List<RetType>> throwsClause_result, RetType contract_result, List<RetType> decls_result) {
//        return forTraitObjectAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, decls_result);
//    }
//
//    public RetType forAbsObjectDeclOnly(AbsObjectDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result,
//                                        List<RetType> extendsClause_result, RetType where_result, Option<List<RetType>> params_result,
//                                        Option<List<RetType>> throwsClause_result, RetType contract_result, List<RetType> decls_result) {
//        return forObjectAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result,
//                                          where_result, params_result, throwsClause_result, contract_result, decls_result);
//    }
//
//    public RetType forObjectDeclOnly(ObjectDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result,
//                                     List<RetType> extendsClause_result, RetType where_result, Option<List<RetType>> params_result,
//                                     Option<List<RetType>> throwsClause_result, RetType contract_result, List<RetType> decls_result) {
//        return forObjectAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result,
//                                          where_result, params_result, throwsClause_result, contract_result, decls_result);
//    }

//    public RetType forAbstractNodeOnly(AbstractNode that) {
//        return defaultCase(that);
//    }
//
//    public RetType forCompilationUnitOnly(CompilationUnit that, RetType name_result, List<RetType> imports_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forApiOnly(Api that, RetType name_result, List<RetType> imports_result, List<RetType> decls_result) {
//        return forCompilationUnitOnly(that, name_result, imports_result);
//    }
//
//    public RetType forImportOnly(Import that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forImportedNamesOnly(ImportedNames that, RetType api_result) {
//        return forImportOnly(that);
//    }
//
//    public RetType forImportStarOnly(ImportStar that, RetType api_result, List<RetType> except_result) {
//        return forImportedNamesOnly(that, api_result);
//    }
//
//    public RetType forImportNamesOnly(ImportNames that, RetType api_result, List<RetType> aliasedNames_result) {
//        return forImportedNamesOnly(that, api_result);
//    }
//
//    public RetType forImportApiOnly(ImportApi that, List<RetType> apis_result) {
//        return forImportOnly(that);
//    }
//
//    public RetType forAliasedSimpleNameOnly(AliasedSimpleName that, RetType name_result, Option<RetType> alias_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forAliasedAPINameOnly(AliasedAPIName that, RetType api_result, Option<RetType> alias_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forExportOnly(Export that, List<RetType> apis_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forTraitObjectAbsDeclOrDeclOnly(TraitObjectAbsDeclOrDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> extendsClause_result, RetType where_result, List<RetType> decls_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forTraitAbsDeclOrDeclOnly(TraitAbsDeclOrDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> extendsClause_result, RetType where_result, List<RetType> excludes_result, Option<List<RetType>> comprises_result, List<RetType> decls_result) {
//        return forTraitObjectAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, decls_result);
//    }
//
//    public RetType forAbsTraitDeclOnly(AbsTraitDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> extendsClause_result, RetType where_result, List<RetType> excludes_result, Option<List<RetType>> comprises_result, List<RetType> decls_result) {
//        return forTraitAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, excludes_result, comprises_result, decls_result);
//    }
//
//    public RetType forTraitDeclOnly(TraitDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> extendsClause_result, RetType where_result, List<RetType> excludes_result, Option<List<RetType>> comprises_result, List<RetType> decls_result) {
//        return forTraitAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, excludes_result, comprises_result, decls_result);
//    }
//
//    public RetType forObjectAbsDeclOrDeclOnly(ObjectAbsDeclOrDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> extendsClause_result, RetType where_result, Option<List<RetType>> params_result, Option<List<RetType>> throwsClause_result, RetType contract_result, List<RetType> decls_result) {
//        return forTraitObjectAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, decls_result);
//    }
//
//    public RetType forAbsObjectDeclOnly(AbsObjectDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> extendsClause_result, RetType where_result, Option<List<RetType>> params_result, Option<List<RetType>> throwsClause_result, RetType contract_result, List<RetType> decls_result) {
//        return forObjectAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, params_result, throwsClause_result, contract_result, decls_result);
//    }
//
//    public RetType forObjectDeclOnly(ObjectDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> extendsClause_result, RetType where_result, Option<List<RetType>> params_result, Option<List<RetType>> throwsClause_result, RetType contract_result, List<RetType> decls_result) {
//        return forObjectAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, params_result, throwsClause_result, contract_result, decls_result);
//    }
//
//    public RetType forVarAbsDeclOrDeclOnly(VarAbsDeclOrDecl that, List<RetType> lhs_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forAbsVarDeclOnly(AbsVarDecl that, List<RetType> lhs_result) {
//        return forVarAbsDeclOrDeclOnly(that, lhs_result);
//    }
//
//    public RetType forVarDeclOnly(VarDecl that, List<RetType> lhs_result, RetType init_result) {
//        return forVarAbsDeclOrDeclOnly(that, lhs_result);
//    }
//
//    public RetType forLValueOnly(LValue that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forLValueBindOnly(LValueBind that, RetType name_result, Option<RetType> type_result, List<RetType> mods_result) {
//        return forLValueOnly(that);
//    }
//
//    public RetType forUnpastingOnly(Unpasting that) {
//        return forLValueOnly(that);
//    }
//
//    public RetType forUnpastingBindOnly(UnpastingBind that, RetType name_result, List<RetType> dim_result) {
//        return forUnpastingOnly(that);
//    }
//
//    public RetType forUnpastingSplitOnly(UnpastingSplit that, List<RetType> elems_result) {
//        return forUnpastingOnly(that);
//    }
//
//    public RetType forFnAbsDeclOrDeclOnly(FnAbsDeclOrDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> params_result, Option<RetType> returnType_result, Option<List<RetType>> throwsClause_result, RetType where_result, RetType contract_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forAbsFnDeclOnly(AbsFnDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> params_result, Option<RetType> returnType_result, Option<List<RetType>> throwsClause_result, RetType where_result, RetType contract_result) {
//        return forFnAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, params_result, returnType_result, throwsClause_result, where_result, contract_result);
//    }
//
//    public RetType forFnDeclOnly(FnDecl that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> params_result, Option<RetType> returnType_result, Option<List<RetType>> throwsClause_result, RetType where_result, RetType contract_result) {
//        return forFnAbsDeclOrDeclOnly(that, mods_result, name_result, staticParams_result, params_result, returnType_result, throwsClause_result, where_result, contract_result);
//    }
//
//    public RetType forFnDefOnly(FnDef that, List<RetType> mods_result, RetType name_result, List<RetType> staticParams_result, List<RetType> params_result, Option<RetType> returnType_result, Option<List<RetType>> throwsClause_result, RetType where_result, RetType contract_result, RetType body_result) {
//        return forFnDeclOnly(that, mods_result, name_result, staticParams_result, params_result, returnType_result, throwsClause_result, where_result, contract_result);
//    }
//
//    public RetType forParamOnly(Param that, List<RetType> mods_result, RetType name_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forNormalParamOnly(NormalParam that, List<RetType> mods_result, RetType name_result, Option<RetType> type_result, Option<RetType> defaultExpr_result) {
//        return forParamOnly(that, mods_result, name_result);
//    }
//
//    public RetType forVarargsParamOnly(VarargsParam that, List<RetType> mods_result, RetType name_result, RetType varargsType_result) {
//        return forParamOnly(that, mods_result, name_result);
//    }
//
//    public RetType forDimUnitDeclOnly(DimUnitDecl that, Option<RetType> dim_result, Option<RetType> derived_result, Option<RetType> default_result, List<RetType> units_result, Option<RetType> def_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forTestDeclOnly(TestDecl that, RetType name_result, List<RetType> gens_result, RetType expr_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forPropertyDeclOnly(PropertyDecl that, Option<RetType> name_result, List<RetType> params_result, RetType expr_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forExternalSyntaxAbsDeclOrDeclOnly(ExternalSyntaxAbsDeclOrDecl that, RetType openExpander_result, RetType name_result, RetType closeExpander_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forAbsExternalSyntaxOnly(AbsExternalSyntax that, RetType openExpander_result, RetType name_result, RetType closeExpander_result) {
//        return forExternalSyntaxAbsDeclOrDeclOnly(that, openExpander_result, name_result, closeExpander_result);
//    }
//
//    public RetType forExternalSyntaxOnly(ExternalSyntax that, RetType openExpander_result, RetType name_result, RetType closeExpander_result, RetType expr_result) {
//        return forExternalSyntaxAbsDeclOrDeclOnly(that, openExpander_result, name_result, closeExpander_result);
//    }
//
//    public RetType forGrammarDeclOnly(GrammarDecl that, RetType name_result, List<RetType> extends_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forGrammarDefOnly(GrammarDef that, RetType name_result, List<RetType> extends_result, List<RetType> nonterminal_result) {
//        return forGrammarDeclOnly(that, name_result, extends_result);
//    }
//
//    public RetType forProductionDeclOnly(ProductionDecl that, Option<RetType> modifier_result, RetType name_result, RetType type_result, Option<RetType> extends_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forProductionDefOnly(ProductionDef that, Option<RetType> modifier_result, RetType name_result, RetType type_result, Option<RetType> extends_result, List<RetType> syntaxDefs_result) {
//        return forProductionDeclOnly(that, modifier_result, name_result, type_result, extends_result);
//    }
//
//    public RetType forSyntaxDeclOnly(SyntaxDecl that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forSyntaxDefOnly(SyntaxDef that, List<RetType> syntaxSymbols_result, RetType transformationExpression_result) {
//        return forSyntaxDeclOnly(that);
//    }
//
//    public RetType forSyntaxSymbolOnly(SyntaxSymbol that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forPrefixedSymbolOnly(PrefixedSymbol that, Option<RetType> id_result, RetType symbol_result) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forOptionalSymbolOnly(OptionalSymbol that, RetType symbol_result) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forRepeatSymbolOnly(RepeatSymbol that, RetType symbol_result) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forRepeatOneOrMoreSymbolOnly(RepeatOneOrMoreSymbol that, RetType symbol_result) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forWhitespaceSymbolOnly(WhitespaceSymbol that, RetType symbol_result) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forItemSymbolOnly(ItemSymbol that) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forNonterminalSymbolOnly(NonterminalSymbol that, RetType nonterminal_result) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forKeywordSymbolOnly(KeywordSymbol that) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forTokenSymbolOnly(TokenSymbol that) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forNotPredicateSymbolOnly(NotPredicateSymbol that, RetType symbol_result) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forAndPredicateSymbolOnly(AndPredicateSymbol that, RetType symbol_result) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forExprOnly(Expr that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forAsIfExprOnly(AsIfExpr that, RetType expr_result, RetType type_result) {
//        return forExprOnly(that);
//    }
//
//    public RetType forAssignmentOnly(Assignment that, List<RetType> lhs_result, Option<RetType> opr_result, RetType rhs_result) {
//        return forExprOnly(that);
//    }
//
//    public RetType forDelimitedExprOnly(DelimitedExpr that) {
//        return forExprOnly(that);
//    }
//
//    public RetType forForOnly(For that, List<RetType> gens_result, RetType body_result) {
//        return forDelimitedExprOnly(that);
//    }
//
//    public RetType forLabelOnly(Label that, RetType name_result, RetType body_result) {
//        return forDelimitedExprOnly(that);
//    }
//
//    public RetType forAbstractObjectExprOnly(AbstractObjectExpr that, List<RetType> extendsClause_result, List<RetType> decls_result) {
//        return forDelimitedExprOnly(that);
//    }
//
//    public RetType forObjectExprOnly(ObjectExpr that, List<RetType> extendsClause_result, List<RetType> decls_result) {
//        return forAbstractObjectExprOnly(that, extendsClause_result, decls_result);
//    }
//
//    public RetType for_RewriteObjectExprOnly(_RewriteObjectExpr that, List<RetType> extendsClause_result, List<RetType> decls_result, List<RetType> staticParams_result, List<RetType> staticArgs_result, Option<List<RetType>> params_result) {
//        return forAbstractObjectExprOnly(that, extendsClause_result, decls_result);
//    }
//
//    public RetType forTryOnly(Try that, RetType body_result, Option<RetType> catchClause_result, List<RetType> forbid_result, Option<RetType> finallyClause_result) {
//        return forDelimitedExprOnly(that);
//    }
//
//    public RetType forTypecaseOnly(Typecase that, List<RetType> bind_result, List<RetType> clauses_result, Option<RetType> elseClause_result) {
//        return forDelimitedExprOnly(that);
//    }
//
//    public RetType forWhileOnly(While that, RetType test_result, RetType body_result) {
//        return forDelimitedExprOnly(that);
//    }
//
//    public RetType forFlowExprOnly(FlowExpr that) {
//        return forExprOnly(that);
//    }
//
//    public RetType forAccumulatorOnly(Accumulator that, RetType opr_result, List<RetType> gens_result, RetType body_result) {
//        return forFlowExprOnly(that);
//    }
//
//    public RetType forArrayComprehensionOnly(ArrayComprehension that, List<RetType> clauses_result) {
//        return forFlowExprOnly(that);
//    }
//
//    public RetType forAtomicExprOnly(AtomicExpr that, RetType expr_result) {
//        return forFlowExprOnly(that);
//    }
//
//    public RetType forExitOnly(Exit that, Option<RetType> target_result, Option<RetType> returnExpr_result) {
//        return forFlowExprOnly(that);
//    }
//
//    public RetType forSpawnOnly(Spawn that, RetType body_result) {
//        return forFlowExprOnly(that);
//    }
//
//    public RetType forThrowOnly(Throw that, RetType expr_result) {
//        return forFlowExprOnly(that);
//    }
//
//    public RetType forTryAtomicExprOnly(TryAtomicExpr that, RetType expr_result) {
//        return forFlowExprOnly(that);
//    }
//
//    public RetType forFnExprOnly(FnExpr that, RetType name_result, List<RetType> staticParams_result, List<RetType> params_result, Option<RetType> returnType_result, RetType where_result, Option<List<RetType>> throwsClause_result, RetType body_result) {
//        return forExprOnly(that);
//    }
//
//    public RetType forLetExprOnly(LetExpr that, List<RetType> body_result) {
//        return forExprOnly(that);
//    }
//
//    public RetType forLocalVarDeclOnly(LocalVarDecl that, List<RetType> body_result, List<RetType> lhs_result, Option<RetType> rhs_result) {
//        return forLetExprOnly(that, body_result);
//    }
//
//    public RetType forGeneratedExprOnly(GeneratedExpr that, RetType expr_result, List<RetType> gens_result) {
//        return forExprOnly(that);
//    }
//
//    public RetType forSimpleExprOnly(SimpleExpr that) {
//        return forExprOnly(that);
//    }
//
//    public RetType forOpExprOnly(OpExpr that, List<RetType> ops_result, List<RetType> args_result) {
//        return forSimpleExprOnly(that);
//    }
//
//    public RetType forSubscriptExprOnly(SubscriptExpr that, RetType obj_result, List<RetType> subs_result, Option<RetType> op_result) {
//        return forSimpleExprOnly(that);
//    }
//
//    public RetType forPrimaryOnly(Primary that) {
//        return forSimpleExprOnly(that);
//    }
//
//    public RetType forCoercionInvocationOnly(CoercionInvocation that, RetType type_result, List<RetType> staticArgs_result, RetType arg_result) {
//        return forPrimaryOnly(that);
//    }
//
//    public RetType forMethodInvocationOnly(MethodInvocation that, RetType obj_result, RetType method_result, List<RetType> staticArgs_result, RetType arg_result) {
//        return forPrimaryOnly(that);
//    }
//
//    public RetType forAbstractFieldRefOnly(AbstractFieldRef that, RetType obj_result) {
//        return forPrimaryOnly(that);
//    }
//
//    public RetType forFieldRefOnly(FieldRef that, RetType obj_result, RetType field_result) {
//        return forAbstractFieldRefOnly(that, obj_result);
//    }
//
//    public RetType forFieldRefForSureOnly(FieldRefForSure that, RetType obj_result, RetType field_result) {
//        return forAbstractFieldRefOnly(that, obj_result);
//    }
//
//    public RetType for_RewriteFieldRefOnly(_RewriteFieldRef that, RetType obj_result, RetType field_result) {
//        return forAbstractFieldRefOnly(that, obj_result);
//    }
//
//    public RetType forJuxtOnly(Juxt that, List<RetType> exprs_result) {
//        return forPrimaryOnly(that);
//    }
//
//    public RetType forLooseJuxtOnly(LooseJuxt that, List<RetType> exprs_result) {
//        return forJuxtOnly(that, exprs_result);
//    }
//
//    public RetType forMathPrimaryOnly(MathPrimary that, RetType front_result, List<RetType> rest_result) {
//        return forPrimaryOnly(that);
//    }
//
//    public RetType for_RewriteFnRefOnly(_RewriteFnRef that, RetType fn_result, List<RetType> staticArgs_result) {
//        return forPrimaryOnly(that);
//    }
//
//    public RetType forBaseExprOnly(BaseExpr that) {
//        return forPrimaryOnly(that);
//    }
//
//    public RetType forVarRefOnly(VarRef that, RetType var_result) {
//        return forBaseExprOnly(that);
//    }
//
//    public RetType forArrayExprOnly(ArrayExpr that) {
//        return forBaseExprOnly(that);
//    }
//
//    public RetType forArrayElementOnly(ArrayElement that, RetType element_result) {
//        return forArrayExprOnly(that);
//    }
//
//    public RetType forArrayElementsOnly(ArrayElements that, List<RetType> elements_result) {
//        return forArrayExprOnly(that);
//    }
//
//    public RetType forTypeOnly(Type that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forArrowTypeOnly(ArrowType that, RetType domain_result, RetType range_result, Option<List<RetType>> throwsClause_result) {
//        return forTypeOnly(that);
//    }
//
//    public RetType for_RewriteGenericArrowTypeOnly(_RewriteGenericArrowType that, RetType domain_result, RetType range_result, Option<List<RetType>> throwsClause_result, List<RetType> staticParams_result, RetType where_result) {
//        return forArrowTypeOnly(that, domain_result, range_result, throwsClause_result);
//    }
//
//    public RetType forNonArrowTypeOnly(NonArrowType that) {
//        return forTypeOnly(that);
//    }
//
//    public RetType forBottomTypeOnly(BottomType that) {
//        return forNonArrowTypeOnly(that);
//    }
//
//    public RetType forBaseTypeOnly(BaseType that) {
//        return forNonArrowTypeOnly(that);
//    }
//
//    public RetType forArrayTypeOnly(ArrayType that, RetType element_result, RetType indices_result) {
//        return forBaseTypeOnly(that);
//    }
//
//    public RetType forVarTypeOnly(VarType that, RetType name_result) {
//        return forBaseTypeOnly(that);
//    }
//
//    public RetType forInferenceVarTypeOnly(InferenceVarType that) {
//        return forBaseTypeOnly(that);
//    }
//
//    public RetType forMatrixTypeOnly(MatrixType that, RetType element_result, List<RetType> dimensions_result) {
//        return forBaseTypeOnly(that);
//    }
//
//    public RetType forTraitTypeOnly(TraitType that, RetType name_result, List<RetType> args_result) {
//        return forBaseTypeOnly(that);
//    }
//
//    public RetType forVoidTypeOnly(VoidType that) {
//        return forNonArrowTypeOnly(that);
//    }
//
//    public RetType forIntersectionTypeOnly(IntersectionType that, Set<RetType> elements_result) {
//        return forNonArrowTypeOnly(that);
//    }
//
//    public RetType forUnionTypeOnly(UnionType that, Set<RetType> elements_result) {
//        return forNonArrowTypeOnly(that);
//    }
//
//    public RetType forAndTypeOnly(AndType that, RetType first_result, RetType second_result) {
//        return forNonArrowTypeOnly(that);
//    }
//
//    public RetType forOrTypeOnly(OrType that, RetType first_result, RetType second_result) {
//        return forNonArrowTypeOnly(that);
//    }
//
//    public RetType forDimTypeOnly(DimType that, RetType type_result) {
//        return forNonArrowTypeOnly(that);
//    }
//
//    public RetType forTaggedDimTypeOnly(TaggedDimType that, RetType type_result, RetType dim_result, Option<RetType> unit_result) {
//        return forDimTypeOnly(that, type_result);
//    }
//
//    public RetType forTaggedUnitTypeOnly(TaggedUnitType that, RetType type_result, RetType unit_result) {
//        return forDimTypeOnly(that, type_result);
//    }
//
//    public RetType forStaticArgOnly(StaticArg that) {
//        return forTypeOnly(that);
//    }
//
//    public RetType forTypeArgOnly(TypeArg that, RetType type_result) {
//        return forStaticArgOnly(that);
//    }
//
//    public RetType forIntArgOnly(IntArg that, RetType val_result) {
//        return forStaticArgOnly(that);
//    }
//
//    public RetType forBoolArgOnly(BoolArg that, RetType bool_result) {
//        return forStaticArgOnly(that);
//    }
//
//    public RetType forOpArgOnly(OpArg that, RetType name_result) {
//        return forStaticArgOnly(that);
//    }
//
//    public RetType forDimArgOnly(DimArg that, RetType dim_result) {
//        return forStaticArgOnly(that);
//    }
//
//    public RetType forUnitArgOnly(UnitArg that, RetType unit_result) {
//        return forStaticArgOnly(that);
//    }
//
//    public RetType for_RewriteImplicitTypeOnly(_RewriteImplicitType that) {
//        return forTypeOnly(that);
//    }
//
//    public RetType for_RewriteIntersectionTypeOnly(_RewriteIntersectionType that, List<RetType> elements_result) {
//        return forTypeOnly(that);
//    }
//
//    public RetType for_RewriteUnionTypeOnly(_RewriteUnionType that, List<RetType> elements_result) {
//        return forTypeOnly(that);
//    }
//
//    public RetType for_RewriteFixedPointTypeOnly(_RewriteFixedPointType that, RetType var_result, RetType body_result) {
//        return forTypeOnly(that);
//    }
//
//    public RetType forStaticExprOnly(StaticExpr that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forIntExprOnly(IntExpr that) {
//        return forStaticExprOnly(that);
//    }
//
//    public RetType forIntValOnly(IntVal that) {
//        return forIntExprOnly(that);
//    }
//
//    public RetType forNumberConstraintOnly(NumberConstraint that, RetType val_result) {
//        return forIntValOnly(that);
//    }
//
//    public RetType forIntRefOnly(IntRef that, RetType name_result) {
//        return forIntValOnly(that);
//    }
//
//    public RetType forIntOpExprOnly(IntOpExpr that, RetType left_result, RetType right_result) {
//        return forIntExprOnly(that);
//    }
//
//    public RetType forSumConstraintOnly(SumConstraint that, RetType left_result, RetType right_result) {
//        return forIntOpExprOnly(that, left_result, right_result);
//    }
//
//    public RetType forMinusConstraintOnly(MinusConstraint that, RetType left_result, RetType right_result) {
//        return forIntOpExprOnly(that, left_result, right_result);
//    }
//
//    public RetType forProductConstraintOnly(ProductConstraint that, RetType left_result, RetType right_result) {
//        return forIntOpExprOnly(that, left_result, right_result);
//    }
//
//    public RetType forExponentConstraintOnly(ExponentConstraint that, RetType left_result, RetType right_result) {
//        return forIntOpExprOnly(that, left_result, right_result);
//    }
//
//    public RetType forBoolExprOnly(BoolExpr that) {
//        return forStaticExprOnly(that);
//    }
//
//    public RetType forBoolValOnly(BoolVal that) {
//        return forBoolExprOnly(that);
//    }
//
//    public RetType forBoolConstantOnly(BoolConstant that) {
//        return forBoolValOnly(that);
//    }
//
//    public RetType forBoolRefOnly(BoolRef that, RetType name_result) {
//        return forBoolValOnly(that);
//    }
//
//    public RetType forBoolConstraintOnly(BoolConstraint that) {
//        return forBoolExprOnly(that);
//    }
//
//    public RetType forNotConstraintOnly(NotConstraint that, RetType bool_result) {
//        return forBoolConstraintOnly(that);
//    }
//
//    public RetType forBinaryBoolConstraintOnly(BinaryBoolConstraint that, RetType left_result, RetType right_result) {
//        return forBoolConstraintOnly(that);
//    }
//
//    public RetType forOrConstraintOnly(OrConstraint that, RetType left_result, RetType right_result) {
//        return forBinaryBoolConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forAndConstraintOnly(AndConstraint that, RetType left_result, RetType right_result) {
//        return forBinaryBoolConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forImpliesConstraintOnly(ImpliesConstraint that, RetType left_result, RetType right_result) {
//        return forBinaryBoolConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forBEConstraintOnly(BEConstraint that, RetType left_result, RetType right_result) {
//        return forBinaryBoolConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forDimExprOnly(DimExpr that) {
//        return forStaticExprOnly(that);
//    }
//
//    public RetType forBaseDimOnly(BaseDim that) {
//        return forDimExprOnly(that);
//    }
//
//    public RetType forDimRefOnly(DimRef that, RetType name_result) {
//        return forDimExprOnly(that);
//    }
//
//    public RetType forProductDimOnly(ProductDim that, RetType multiplier_result, RetType multiplicand_result) {
//        return forDimExprOnly(that);
//    }
//
//    public RetType forQuotientDimOnly(QuotientDim that, RetType numerator_result, RetType denominator_result) {
//        return forDimExprOnly(that);
//    }
//
//    public RetType forExponentDimOnly(ExponentDim that, RetType base_result, RetType power_result) {
//        return forDimExprOnly(that);
//    }
//
//    public RetType forOpDimOnly(OpDim that, RetType val_result, RetType op_result) {
//        return forDimExprOnly(that);
//    }
//
//    public RetType forWhereBindingOnly(WhereBinding that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forWhereTypeOnly(WhereType that, RetType name_result, List<RetType> supers_result) {
//        return forWhereBindingOnly(that);
//    }
//
//    public RetType forWhereNatOnly(WhereNat that, RetType name_result) {
//        return forWhereBindingOnly(that);
//    }
//
//    public RetType forWhereIntOnly(WhereInt that, RetType name_result) {
//        return forWhereBindingOnly(that);
//    }
//
//    public RetType forWhereBoolOnly(WhereBool that, RetType name_result) {
//        return forWhereBindingOnly(that);
//    }
//
//    public RetType forWhereUnitOnly(WhereUnit that, RetType name_result) {
//        return forWhereBindingOnly(that);
//    }
//
//    public RetType forWhereConstraintOnly(WhereConstraint that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forWhereExtendsOnly(WhereExtends that, RetType name_result, List<RetType> supers_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forTypeAliasOnly(TypeAlias that, RetType name_result, List<RetType> staticParams_result, RetType type_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forWhereCoercesOnly(WhereCoerces that, RetType left_result, RetType right_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forWhereWidensOnly(WhereWidens that, RetType left_result, RetType right_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forWhereWidensCoercesOnly(WhereWidensCoerces that, RetType left_result, RetType right_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forWhereEqualsOnly(WhereEquals that, RetType left_result, RetType right_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forUnitConstraintOnly(UnitConstraint that, RetType name_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forIntConstraintOnly(IntConstraint that, RetType left_result, RetType right_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forLEConstraintOnly(LEConstraint that, RetType left_result, RetType right_result) {
//        return forIntConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forLTConstraintOnly(LTConstraint that, RetType left_result, RetType right_result) {
//        return forIntConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forGEConstraintOnly(GEConstraint that, RetType left_result, RetType right_result) {
//        return forIntConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forGTConstraintOnly(GTConstraint that, RetType left_result, RetType right_result) {
//        return forIntConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forIEConstraintOnly(IEConstraint that, RetType left_result, RetType right_result) {
//        return forIntConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forBoolConstraintExprOnly(BoolConstraintExpr that, RetType constraint_result) {
//        return forWhereConstraintOnly(that);
//    }
//
//    public RetType forContractOnly(Contract that, Option<List<RetType>> requires_result, Option<List<RetType>> ensures_result, Option<List<RetType>> invariants_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forEnsuresClauseOnly(EnsuresClause that, RetType post_result, Option<RetType> pre_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forModifierOnly(Modifier that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forModifierAbstractOnly(ModifierAbstract that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierAtomicOnly(ModifierAtomic that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierGetterOnly(ModifierGetter that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierHiddenOnly(ModifierHidden that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierIOOnly(ModifierIO that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierOverrideOnly(ModifierOverride that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierPrivateOnly(ModifierPrivate that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierSettableOnly(ModifierSettable that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierSetterOnly(ModifierSetter that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierTestOnly(ModifierTest that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierTransientOnly(ModifierTransient that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierValueOnly(ModifierValue that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierVarOnly(ModifierVar that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierWidensOnly(ModifierWidens that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forModifierWrappedOnly(ModifierWrapped that) {
//        return forModifierOnly(that);
//    }
//
//    public RetType forStaticParamOnly(StaticParam that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forOpParamOnly(OpParam that, RetType name_result) {
//        return forStaticParamOnly(that);
//    }
//
//    public RetType forIdStaticParamOnly(IdStaticParam that, RetType name_result) {
//        return forStaticParamOnly(that);
//    }
//
//    public RetType forBoolParamOnly(BoolParam that, RetType name_result) {
//        return forIdStaticParamOnly(that, name_result);
//    }
//
//    public RetType forDimParamOnly(DimParam that, RetType name_result) {
//        return forIdStaticParamOnly(that, name_result);
//    }
//
//    public RetType forIntParamOnly(IntParam that, RetType name_result) {
//        return forIdStaticParamOnly(that, name_result);
//    }
//
//    public RetType forNatParamOnly(NatParam that, RetType name_result) {
//        return forIdStaticParamOnly(that, name_result);
//    }
//
//    public RetType forTypeParamOnly(TypeParam that, RetType name_result, List<RetType> extendsClause_result) {
//        return forIdStaticParamOnly(that, name_result);
//    }
//
//    public RetType forUnitParamOnly(UnitParam that, RetType name_result, Option<RetType> dim_result) {
//        return forIdStaticParamOnly(that, name_result);
//    }
//
//    public RetType forNameOnly(Name that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forAPINameOnly(APIName that, List<RetType> ids_result) {
//        return forNameOnly(that);
//    }
//
//    public RetType forIdOrOpOrAnonymousNameOnly(IdOrOpOrAnonymousName that, Option<RetType> api_result, RetType name_result) {
//        return forNameOnly(that);
//    }
//
//    public RetType forIdOrOpOrAnonymousNameOnly(IdOrOpOrAnonymousName that) {
//        return forNameOnly(that);
//    }
//
//    public RetType forIdOnly(Id that) {
//        return forIdOrOpOrAnonymousNameOnly(that);
//    }
//
//    public RetType forOpNameOnly(OpName that) {
//        return forIdOrOpOrAnonymousNameOnly(that);
//    }
//
//    public RetType forEnclosingOnly(Enclosing that, RetType open_result, RetType close_result) {
//        return forOpNameOnly(that);
//    }
//
//    public RetType forAnonymousFnNameOnly(AnonymousFnName that) {
//        return forIdOrOpOrAnonymousNameOnly(that);
//    }
//
//    public RetType forConstructorFnNameOnly(ConstructorFnName that, RetType def_result) {
//        return forIdOrOpOrAnonymousNameOnly(that);
//    }
//
//    public RetType forArrayComprehensionClauseOnly(ArrayComprehensionClause that, List<RetType> bind_result, RetType init_result, List<RetType> gens_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forKeywordExprOnly(KeywordExpr that, RetType name_result, RetType init_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forCaseClauseOnly(CaseClause that, RetType match_result, RetType body_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forCatchOnly(Catch that, RetType name_result, List<RetType> clauses_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forCatchClauseOnly(CatchClause that, RetType match_result, RetType body_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forTypecaseClauseOnly(TypecaseClause that, List<RetType> match_result, RetType body_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forExtentRangeOnly(ExtentRange that, Option<RetType> base_result, Option<RetType> size_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forGeneratorClauseOnly(GeneratorClause that, List<RetType> bind_result, RetType init_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forVarargsExprOnly(VarargsExpr that, RetType varargs_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forKeywordTypeOnly(KeywordType that, RetType name_result, RetType type_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forIndicesOnly(Indices that, List<RetType> extents_result) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forDimUnitOpOnly(DimUnitOp that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forSquareDimUnitOnly(SquareDimUnit that) {
//        return forDimUnitOpOnly(that);
//    }
//
//    public RetType forCubicDimUnitOnly(CubicDimUnit that) {
//        return forDimUnitOpOnly(that);
//    }
//
//    public RetType forInverseDimUnitOnly(InverseDimUnit that) {
//        return forDimUnitOpOnly(that);
//    }
//
//    public RetType forMathItemOnly(MathItem that) {
//        return forAbstractNodeOnly(that);
//    }
//
//    public RetType forExprMIOnly(ExprMI that, RetType expr_result) {
//        return forMathItemOnly(that);
//    }
//
//    public RetType forParenthesisDelimitedMIOnly(ParenthesisDelimitedMI that, RetType expr_result) {
//        return forExprMIOnly(that, expr_result);
//    }
//
//    public RetType forNonParenthesisDelimitedMIOnly(NonParenthesisDelimitedMI that, RetType expr_result) {
//        return forExprMIOnly(that, expr_result);
//    }
//
//    public RetType forNonExprMIOnly(NonExprMI that) {
//        return forMathItemOnly(that);
//    }
//
//    public RetType forExponentiationMIOnly(ExponentiationMI that, RetType op_result, Option<RetType> expr_result) {
//        return forNonExprMIOnly(that);
//    }
//
//    public RetType forSubscriptingMIOnly(SubscriptingMI that, RetType op_result, List<RetType> exprs_result) {
//        return forNonExprMIOnly(that);
//    }
//
//
//    /** Methods to recur on each child. */
//    public RetType forComponent(Component that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> imports_result = recurOnListOfImport(that.getImports());
//        List<RetType> exports_result = recurOnListOfExport(that.getExports());
//        List<RetType> decls_result = recurOnListOfDecl(that.getDecls());
//        return forComponentOnly(that, name_result, imports_result, exports_result, decls_result);
//    }
//
//    public RetType forApi(Api that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> imports_result = recurOnListOfImport(that.getImports());
//        List<RetType> decls_result = recurOnListOfAbsDecl(that.getDecls());
//        return forApiOnly(that, name_result, imports_result, decls_result);
//    }
//
//    public RetType forImportStar(ImportStar that) {
//        RetType api_result = that.getApi().accept(this);
//        List<RetType> except_result = recurOnListOfIdOrOpOrAnonymousName(that.getExcept());
//        return forImportStarOnly(that, api_result, except_result);
//    }
//
//    public RetType forImportNames(ImportNames that) {
//        RetType api_result = that.getApi().accept(this);
//        List<RetType> aliasedNames_result = recurOnListOfAliasedIdOrOpOrAnonymousName(that.getAliasedNames());
//        return forImportNamesOnly(that, api_result, aliasedNames_result);
//    }
//
//    public RetType forImportApi(ImportApi that) {
//        List<RetType> apis_result = recurOnListOfAliasedAPIName(that.getApis());
//        return forImportApiOnly(that, apis_result);
//    }
//
//    public RetType forAliasedSimpleName(AliasedSimpleName that) {
//        RetType name_result = that.getName().accept(this);
//        Option<RetType> alias_result = recurOnOptionOfIdOrOpOrAnonymousName(that.getAlias());
//        return forAliasedSimpleNameOnly(that, name_result, alias_result);
//    }
//
//    public RetType forAliasedAPIName(AliasedAPIName that) {
//        RetType api_result = that.getApi().accept(this);
//        Option<RetType> alias_result = recurOnOptionOfId(that.getAlias());
//        return forAliasedAPINameOnly(that, api_result, alias_result);
//    }
//
//    public RetType forExport(Export that) {
//        List<RetType> apis_result = recurOnListOfAPIName(that.getApis());
//        return forExportOnly(that, apis_result);
//    }
//
//    public RetType forAbsTraitDecl(AbsTraitDecl that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        RetType where_result = that.getWhere().accept(this);
//        List<RetType> excludes_result = recurOnListOfBaseType(that.getExcludes());
//        Option<List<RetType>> comprises_result = recurOnOptionOfListOfBaseType(that.getComprises());
//        List<RetType> decls_result = recurOnListOfAbsDecl(that.getDecls());
//        return forAbsTraitDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, excludes_result, comprises_result, decls_result);
//    }
//
//    public RetType forTraitDecl(TraitDecl that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        RetType where_result = that.getWhere().accept(this);
//        List<RetType> excludes_result = recurOnListOfBaseType(that.getExcludes());
//        Option<List<RetType>> comprises_result = recurOnOptionOfListOfBaseType(that.getComprises());
//        List<RetType> decls_result = recurOnListOfDecl(that.getDecls());
//        return forTraitDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, excludes_result, comprises_result, decls_result);
//    }
//
//    public RetType forAbsObjectDecl(AbsObjectDecl that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        RetType where_result = that.getWhere().accept(this);
//        Option<List<RetType>> params_result = recurOnOptionOfListOfParam(that.getParams());
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfBaseType(that.getThrowsClause());
//        RetType contract_result = that.getContract().accept(this);
//        List<RetType> decls_result = recurOnListOfAbsDecl(that.getDecls());
//        return forAbsObjectDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, params_result, throwsClause_result, contract_result, decls_result);
//    }
//
//    public RetType forObjectDecl(ObjectDecl that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        RetType where_result = that.getWhere().accept(this);
//        Option<List<RetType>> params_result = recurOnOptionOfListOfParam(that.getParams());
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfBaseType(that.getThrowsClause());
//        RetType contract_result = that.getContract().accept(this);
//        List<RetType> decls_result = recurOnListOfDecl(that.getDecls());
//        return forObjectDeclOnly(that, mods_result, name_result, staticParams_result, extendsClause_result, where_result, params_result, throwsClause_result, contract_result, decls_result);
//    }
//
//    public RetType forAbsVarDecl(AbsVarDecl that) {
//        List<RetType> lhs_result = recurOnListOfLValueBind(that.getLhs());
//        return forAbsVarDeclOnly(that, lhs_result);
//    }
//
//    public RetType forVarDecl(VarDecl that) {
//        List<RetType> lhs_result = recurOnListOfLValueBind(that.getLhs());
//        RetType init_result = that.getInit().accept(this);
//        return forVarDeclOnly(that, lhs_result, init_result);
//    }
//
//    public RetType forLValueBind(LValueBind that) {
//        RetType name_result = that.getName().accept(this);
//        Option<RetType> type_result = recurOnOptionOfType(that.getType());
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        return forLValueBindOnly(that, name_result, type_result, mods_result);
//    }
//
//    public RetType forUnpastingBind(UnpastingBind that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> dim_result = recurOnListOfExtentRange(that.getDim());
//        return forUnpastingBindOnly(that, name_result, dim_result);
//    }
//
//    public RetType forUnpastingSplit(UnpastingSplit that) {
//        List<RetType> elems_result = recurOnListOfUnpasting(that.getElems());
//        return forUnpastingSplitOnly(that, elems_result);
//    }
//
//    public RetType forAbsFnDecl(AbsFnDecl that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> params_result = recurOnListOfParam(that.getParams());
//        Option<RetType> returnType_result = recurOnOptionOfType(that.getReturnType());
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfBaseType(that.getThrowsClause());
//        RetType where_result = that.getWhere().accept(this);
//        RetType contract_result = that.getContract().accept(this);
//        return forAbsFnDeclOnly(that, mods_result, name_result, staticParams_result, params_result, returnType_result, throwsClause_result, where_result, contract_result);
//    }
//
//    public RetType forFnDef(FnDef that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> params_result = recurOnListOfParam(that.getParams());
//        Option<RetType> returnType_result = recurOnOptionOfType(that.getReturnType());
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfBaseType(that.getThrowsClause());
//        RetType where_result = that.getWhere().accept(this);
//        RetType contract_result = that.getContract().accept(this);
//        RetType body_result = that.getBody().accept(this);
//        return forFnDefOnly(that, mods_result, name_result, staticParams_result, params_result, returnType_result, throwsClause_result, where_result, contract_result, body_result);
//    }
//
//    public RetType forVarargsParam(VarargsParam that) {
//        List<RetType> mods_result = recurOnListOfModifier(that.getMods());
//        RetType name_result = that.getName().accept(this);
//        RetType varargsType_result = that.getType().accept(this);
//        return forVarargsParamOnly(that, mods_result, name_result, varargsType_result);
//    }
//
//    public RetType forDimUnitDecl(DimUnitDecl that) {
//        Option<RetType> dim_result = recurOnOptionOfId(that.getDim());
//        Option<RetType> derived_result = recurOnOptionOfDimExpr(that.getDerived());
//        Option<RetType> default_result = recurOnOptionOfId(that.getDefault());
//        List<RetType> units_result = recurOnListOfId(that.getUnits());
//        Option<RetType> def_result = recurOnOptionOfExpr(that.getDef());
//        return forDimUnitDeclOnly(that, dim_result, derived_result, default_result, units_result, def_result);
//    }
//
//    public RetType forTestDecl(TestDecl that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> gens_result = recurOnListOfGeneratorClause(that.getGens());
//        RetType expr_result = that.getExpr().accept(this);
//        return forTestDeclOnly(that, name_result, gens_result, expr_result);
//    }
//
//    public RetType forPropertyDecl(PropertyDecl that) {
//        Option<RetType> name_result = recurOnOptionOfId(that.getName());
//        List<RetType> params_result = recurOnListOfParam(that.getParams());
//        RetType expr_result = that.getExpr().accept(this);
//        return forPropertyDeclOnly(that, name_result, params_result, expr_result);
//    }
//
//    public RetType forAbsExternalSyntax(AbsExternalSyntax that) {
//        RetType openExpander_result = that.getOpenExpander().accept(this);
//        RetType name_result = that.getName().accept(this);
//        RetType closeExpander_result = that.getCloseExpander().accept(this);
//        return forAbsExternalSyntaxOnly(that, openExpander_result, name_result, closeExpander_result);
//    }
//
//    public RetType forExternalSyntax(ExternalSyntax that) {
//        RetType openExpander_result = that.getOpenExpander().accept(this);
//        RetType name_result = that.getName().accept(this);
//        RetType closeExpander_result = that.getCloseExpander().accept(this);
//        RetType expr_result = that.getExpr().accept(this);
//        return forExternalSyntaxOnly(that, openExpander_result, name_result, closeExpander_result, expr_result);
//    }
//
//    public RetType forGrammarDef(GrammarDef that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> extends_result = recurOnListOfId(that.getExtends());
//        List<RetType> productions_result = recurOnListOfProductionDef(that.getProductions());
//        return forGrammarDefOnly(that, name_result, extends_result, productions_result);
//    }
//
//    public RetType forProductionDef(ProductionDef that) {
//        Option<RetType> modifier_result = recurOnOptionOfModifier(that.getModifier());
//        RetType name_result = that.getName().accept(this);
//        RetType type_result = that.getType().accept(this);
//        Option<RetType> extends_result = recurOnOptionOfId(that.getExtends());
//        List<RetType> syntaxDefs_result = recurOnListOfSyntaxDef(that.getSyntaxDefs());
//        return forProductionDefOnly(that, modifier_result, name_result, type_result, extends_result, syntaxDefs_result);
//    }
//
//    public RetType forSyntaxDef(SyntaxDef that) {
//        List<RetType> syntaxSymbols_result = recurOnListOfSyntaxSymbol(that.getSyntaxSymbols());
//        RetType transformationExpression_result = that.getTransformationExpression().accept(this);
//        return forSyntaxDefOnly(that, syntaxSymbols_result, transformationExpression_result);
//    }
//
//    public RetType forSyntaxSymbol(SyntaxSymbol that) {
//        return forSyntaxSymbolOnly(that);
//    }
//
//    public RetType forPrefixedSymbol(PrefixedSymbol that) {
//        Option<RetType> id_result = recurOnOptionOfId(that.getId());
//        RetType symbol_result = that.getSymbol().accept(this);
//        return forPrefixedSymbolOnly(that, id_result, symbol_result);
//    }
//
//    public RetType forOptionalSymbol(OptionalSymbol that) {
//        RetType symbol_result = that.getSymbol().accept(this);
//        return forOptionalSymbolOnly(that, symbol_result);
//    }
//
//    public RetType forRepeatSymbol(RepeatSymbol that) {
//        RetType symbol_result = that.getSymbol().accept(this);
//        return forRepeatSymbolOnly(that, symbol_result);
//    }
//
//    public RetType forRepeatOneOrMoreSymbol(RepeatOneOrMoreSymbol that) {
//        RetType symbol_result = that.getSymbol().accept(this);
//        return forRepeatOneOrMoreSymbolOnly(that, symbol_result);
//    }
//
//    public RetType forWhitespaceSymbol(WhitespaceSymbol that) {
//        RetType symbol_result = that.getSymbol().accept(this);
//        return forWhitespaceSymbolOnly(that, symbol_result);
//    }
//
//    public RetType forItemSymbol(ItemSymbol that) {
//        return forItemSymbolOnly(that);
//    }
//
//    public RetType forNonterminalSymbol(NonterminalSymbol that) {
//        RetType nonterminal_result = that.getNonterminal().accept(this);
//        return forNonterminalSymbolOnly(that, nonterminal_result);
//    }
//
//    public RetType forKeywordSymbol(KeywordSymbol that) {
//        return forKeywordSymbolOnly(that);
//    }
//
//    public RetType forTokenSymbol(TokenSymbol that) {
//        return forTokenSymbolOnly(that);
//    }
//
//    public RetType forNotPredicateSymbol(NotPredicateSymbol that) {
//        RetType symbol_result = that.getSymbol().accept(this);
//        return forNotPredicateSymbolOnly(that, symbol_result);
//    }
//
//    public RetType forAndPredicateSymbol(AndPredicateSymbol that) {
//        RetType symbol_result = that.getSymbol().accept(this);
//        return forAndPredicateSymbolOnly(that, symbol_result);
//    }
//
//    public RetType forAsExpr(AsExpr that) {
//        RetType expr_result = that.getExpr().accept(this);
//        RetType type_result = that.getType().accept(this);
//        return forAsExprOnly(that, expr_result, type_result);
//    }
//
//    public RetType forAsIfExpr(AsIfExpr that) {
//        RetType expr_result = that.getExpr().accept(this);
//        RetType type_result = that.getType().accept(this);
//        return forAsIfExprOnly(that, expr_result, type_result);
//    }
//
//    public RetType forAssignment(Assignment that) {
//        List<RetType> lhs_result = recurOnListOfLHS(that.getLhs());
//        Option<RetType> opr_result = recurOnOptionOfOp(that.getOpr());
//        RetType rhs_result = that.getRhs().accept(this);
//        return forAssignmentOnly(that, lhs_result, opr_result, rhs_result);
//    }
//
//    public RetType forCaseExpr(CaseExpr that) {
//        Option<RetType> param_result = recurOnOptionOfExpr(that.getParam());
//        Option<RetType> compare_result = recurOnOptionOfOp(that.getCompare());
//        List<RetType> clauses_result = recurOnListOfCaseClause(that.getClauses());
//        Option<RetType> elseClause_result = recurOnOptionOfBlock(that.getElseClause());
//        return forCaseExprOnly(that, param_result, compare_result, clauses_result, elseClause_result);
//    }
//
//    public RetType forDo(Do that) {
//        List<RetType> fronts_result = recurOnListOfDoFront(that.getFronts());
//        return forDoOnly(that, fronts_result);
//    }
//
//    public RetType forFor(For that) {
//        List<RetType> gens_result = recurOnListOfGeneratorClause(that.getGens());
//        RetType body_result = that.getBody().accept(this);
//        return forForOnly(that, gens_result, body_result);
//    }
//
//    public RetType forIf(If that) {
//        List<RetType> clauses_result = recurOnListOfIfClause(that.getClauses());
//        Option<RetType> elseClause_result = recurOnOptionOfBlock(that.getElseClause());
//        return forIfOnly(that, clauses_result, elseClause_result);
//    }
//
//    public RetType forLabel(Label that) {
//        RetType name_result = that.getName().accept(this);
//        RetType body_result = that.getBody().accept(this);
//        return forLabelOnly(that, name_result, body_result);
//    }
//
//    public RetType forObjectExpr(ObjectExpr that) {
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        List<RetType> decls_result = recurOnListOfDecl(that.getDecls());
//        return forObjectExprOnly(that, extendsClause_result, decls_result);
//    }
//
//    public RetType for_RewriteObjectExpr(_RewriteObjectExpr that) {
//        List<RetType> extendsClause_result = recurOnListOfTraitTypeWhere(that.getExtendsClause());
//        List<RetType> decls_result = recurOnListOfDecl(that.getDecls());
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> staticArgs_result = recurOnListOfStaticArg(that.getStaticArgs());
//        Option<List<RetType>> params_result = recurOnOptionOfListOfParam(that.getParams());
//        return for_RewriteObjectExprOnly(that, extendsClause_result, decls_result, staticParams_result, staticArgs_result, params_result);
//    }
//
//    public RetType forTry(Try that) {
//        RetType body_result = that.getBody().accept(this);
//        Option<RetType> catchClause_result = recurOnOptionOfCatch(that.getCatchClause());
//        List<RetType> forbid_result = recurOnListOfBaseType(that.getForbid());
//        Option<RetType> finallyClause_result = recurOnOptionOfBlock(that.getFinallyClause());
//        return forTryOnly(that, body_result, catchClause_result, forbid_result, finallyClause_result);
//    }
//
//    public RetType forArgExpr(ArgExpr that) {
//        List<RetType> exprs_result = recurOnListOfExpr(that.getExprs());
//        RetType varargs_result = that.getVarargs().accept(this);
//        return forArgExprOnly(that, exprs_result, varargs_result);
//    }
//
//    public RetType forTupleExpr(TupleExpr that) {
//        List<RetType> exprs_result = recurOnListOfExpr(that.getExprs());
//        return forArgExprOnly(that, exprs_result);
//    }
//
//    public RetType forTypecase(Typecase that) {
//        List<RetType> bind_result = recurOnListOfBinding(that.getBind());
//        List<RetType> clauses_result = recurOnListOfTypecaseClause(that.getClauses());
//        Option<RetType> elseClause_result = recurOnOptionOfBlock(that.getElseClause());
//        return forTypecaseOnly(that, bind_result, clauses_result, elseClause_result);
//    }
//
//    public RetType forWhile(While that) {
//        RetType test_result = that.getTest().accept(this);
//        RetType body_result = that.getBody().accept(this);
//        return forWhileOnly(that, test_result, body_result);
//    }
//
//    public RetType forAccumulator(Accumulator that) {
//        RetType opr_result = that.getOpr().accept(this);
//        List<RetType> gens_result = recurOnListOfGeneratorClause(that.getGens());
//        RetType body_result = that.getBody().accept(this);
//        return forAccumulatorOnly(that, opr_result, gens_result, body_result);
//    }
//
//    public RetType forArrayComprehension(ArrayComprehension that) {
//        List<RetType> clauses_result = recurOnListOfArrayComprehensionClause(that.getClauses());
//        return forArrayComprehensionOnly(that, clauses_result);
//    }
//
//    public RetType forAtomicExpr(AtomicExpr that) {
//        RetType expr_result = that.getExpr().accept(this);
//        return forAtomicExprOnly(that, expr_result);
//    }
//
//    public RetType forExit(Exit that) {
//        Option<RetType> target_result = recurOnOptionOfId(that.getTarget());
//        Option<RetType> returnExpr_result = recurOnOptionOfExpr(that.getReturnExpr());
//        return forExitOnly(that, target_result, returnExpr_result);
//    }
//
//    public RetType forSpawn(Spawn that) {
//        RetType body_result = that.getBody().accept(this);
//        return forSpawnOnly(that, body_result);
//    }
//
//    public RetType forThrow(Throw that) {
//        RetType expr_result = that.getExpr().accept(this);
//        return forThrowOnly(that, expr_result);
//    }
//
//    public RetType forTryAtomicExpr(TryAtomicExpr that) {
//        RetType expr_result = that.getExpr().accept(this);
//        return forTryAtomicExprOnly(that, expr_result);
//    }
//
//    public RetType forFnExpr(FnExpr that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        List<RetType> params_result = recurOnListOfParam(that.getParams());
//        Option<RetType> returnType_result = recurOnOptionOfType(that.getReturnType());
//        RetType where_result = that.getWhere().accept(this);
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfBaseType(that.getThrowsClause());
//        RetType body_result = that.getBody().accept(this);
//        return forFnExprOnly(that, name_result, staticParams_result, params_result, returnType_result, where_result, throwsClause_result, body_result);
//    }
//
//    public RetType forGeneratedExpr(GeneratedExpr that) {
//        RetType expr_result = that.getExpr().accept(this);
//        List<RetType> gens_result = recurOnListOfGeneratorClause(that.getGens());
//        return forGeneratedExprOnly(that, expr_result, gens_result);
//    }
//
//    public RetType forOpExpr(OpExpr that) {
//        List<RetType> ops_result = recurOnListOfOpName(that.getOps());
//        List<RetType> args_result = recurOnListOfExpr(that.getArgs());
//        return forOpExprOnly(that, ops_result, args_result);
//    }
//
//    public RetType forSubscriptExpr(SubscriptExpr that) {
//        RetType obj_result = that.getObj().accept(this);
//        List<RetType> subs_result = recurOnListOfExpr(that.getSubs());
//        Option<RetType> op_result = recurOnOptionOfEnclosing(that.getOp());
//        return forSubscriptExprOnly(that, obj_result, subs_result, op_result);
//    }
//
//    public RetType forCoercionInvocation(CoercionInvocation that) {
//        RetType type_result = that.getType().accept(this);
//        List<RetType> staticArgs_result = recurOnListOfStaticArg(that.getStaticArgs());
//        RetType arg_result = that.getArg().accept(this);
//        return forCoercionInvocationOnly(that, type_result, staticArgs_result, arg_result);
//    }
//
//    public RetType forMethodInvocation(MethodInvocation that) {
//        RetType obj_result = that.getObj().accept(this);
//        RetType method_result = that.getMethod().accept(this);
//        List<RetType> staticArgs_result = recurOnListOfStaticArg(that.getStaticArgs());
//        RetType arg_result = that.getArg().accept(this);
//        return forMethodInvocationOnly(that, obj_result, method_result, staticArgs_result, arg_result);
//    }
//
//    public RetType forFieldRef(FieldRef that) {
//        RetType obj_result = that.getObj().accept(this);
//        RetType field_result = that.getField().accept(this);
//        return forFieldRefOnly(that, obj_result, field_result);
//    }
//
//    public RetType forFieldRefForSure(FieldRefForSure that) {
//        RetType obj_result = that.getObj().accept(this);
//        RetType field_result = that.getField().accept(this);
//        return forFieldRefForSureOnly(that, obj_result, field_result);
//    }
//
//    public RetType for_RewriteFieldRef(_RewriteFieldRef that) {
//        RetType obj_result = that.getObj().accept(this);
//        RetType field_result = that.getField().accept(this);
//        return for_RewriteFieldRefOnly(that, obj_result, field_result);
//    }
//
//    public RetType forLooseJuxt(LooseJuxt that) {
//        List<RetType> exprs_result = recurOnListOfExpr(that.getExprs());
//        return forLooseJuxtOnly(that, exprs_result);
//    }
//
//    public RetType forMathPrimary(MathPrimary that) {
//        RetType front_result = that.getFront().accept(this);
//        List<RetType> rest_result = recurOnListOfMathItem(that.getRest());
//        return forMathPrimaryOnly(that, front_result, rest_result);
//    }
//
//    public RetType forFnRef(FnRef that) {
//        List<RetType> fns_result = recurOnListOfId(that.getFns());
//        List<RetType> staticArgs_result = recurOnListOfStaticArg(that.getStaticArgs());
//        return forFnRefOnly(that, fns_result, staticArgs_result);
//    }
//
//    public RetType for_RewriteFnRef(_RewriteFnRef that) {
//        RetType fn_result = that.getFn().accept(this);
//        List<RetType> staticArgs_result = recurOnListOfStaticArg(that.getStaticArgs());
//        return for_RewriteFnRefOnly(that, fn_result, staticArgs_result);
//    }
//
//    public RetType forVarRef(VarRef that) {
//        RetType var_result = that.getVar().accept(this);
//        return forVarRefOnly(that, var_result);
//    }
//
//    public RetType forArrayElement(ArrayElement that) {
//        RetType element_result = that.getElement().accept(this);
//        return forArrayElementOnly(that, element_result);
//    }
//
//    public RetType forArrayElements(ArrayElements that) {
//        List<RetType> elements_result = recurOnListOfArrayExpr(that.getElements());
//        return forArrayElementsOnly(that, elements_result);
//    }
//
//    public RetType forArrowType(ArrowType that) {
//        RetType domain_result = that.getDomain().accept(this);
//        RetType range_result = that.getRange().accept(this);
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfType(that.getThrowsClause());
//        return forArrowTypeOnly(that, domain_result, range_result, throwsClause_result);
//    }
//
//    public RetType for_RewriteGenericArrowType(_RewriteGenericArrowType that) {
//        RetType domain_result = that.getDomain().accept(this);
//        RetType range_result = that.getRange().accept(this);
//        Option<List<RetType>> throwsClause_result = recurOnOptionOfListOfType(that.getThrowsClause());
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        RetType where_result = that.getWhere().accept(this);
//        return for_RewriteGenericArrowTypeOnly(that, domain_result, range_result, throwsClause_result, staticParams_result, where_result);
//    }
//
//    public RetType forBottomType(BottomType that) {
//        return forBottomTypeOnly(that);
//    }
//
//    public RetType forArrayType(ArrayType that) {
//        RetType element_result = that.getElement().accept(this);
//        RetType indices_result = that.getIndices().accept(this);
//        return forArrayTypeOnly(that, element_result, indices_result);
//    }
//
//    public RetType forVarType(VarType that) {
//        RetType name_result = that.getName().accept(this);
//        return forVarTypeOnly(that, name_result);
//    }
//
//    public RetType forInferenceVarType(InferenceVarType that) {
//        return forInferenceVarTypeOnly(that);
//    }
//
//    public RetType forMatrixType(MatrixType that) {
//        RetType element_result = that.getElement().accept(this);
//        List<RetType> dimensions_result = recurOnListOfExtentRange(that.getDimensions());
//        return forMatrixTypeOnly(that, element_result, dimensions_result);
//    }
//
//    public RetType forTraitType(TraitType that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> args_result = recurOnListOfStaticArg(that.getArgs());
//        return forTraitTypeOnly(that, name_result, args_result);
//    }
//
//    public RetType forArgType(ArgType that) {
//        List<RetType> elements_result = recurOnListOfType(that.getElements());
//        RetType varargs_result = that.getVarargs().accept(this);
//        return forArgTypeOnly(that, elements_result, varargs_result);
//    }
//
//    public RetType forTupleType(TupleType that) {
//        List<RetType> elements_result = recurOnListOfType(that.getElements());
//        return forTupleTypeOnly(that, elements_result, varargs_result, keywords_result);
//    }
//
//    public RetType forVoidType(VoidType that) {
//        return forVoidTypeOnly(that);
//    }
//
//    public RetType forIntersectionType(IntersectionType that) {
//        Set<RetType> elements_result = recurOnSetOfType(that.getElements());
//        return forIntersectionTypeOnly(that, elements_result);
//    }
//
//    public RetType forUnionType(UnionType that) {
//        Set<RetType> elements_result = recurOnSetOfType(that.getElements());
//        return forUnionTypeOnly(that, elements_result);
//    }
//
//    public RetType forAndType(AndType that) {
//        RetType first_result = that.getFirst().accept(this);
//        RetType second_result = that.getSecond().accept(this);
//        return forAndTypeOnly(that, first_result, second_result);
//    }
//
//    public RetType forOrType(OrType that) {
//        RetType first_result = that.getFirst().accept(this);
//        RetType second_result = that.getSecond().accept(this);
//        return forOrTypeOnly(that, first_result, second_result);
//    }
//
//    public RetType forTaggedDimType(TaggedDimType that) {
//        RetType type_result = that.getType().accept(this);
//        RetType dim_result = that.getDim().accept(this);
//        Option<RetType> unit_result = recurOnOptionOfExpr(that.getUnit());
//        return forTaggedDimTypeOnly(that, type_result, dim_result, unit_result);
//    }
//
//    public RetType forTaggedUnitType(TaggedUnitType that) {
//        RetType type_result = that.getType().accept(this);
//        RetType unit_result = that.getUnit().accept(this);
//        return forTaggedUnitTypeOnly(that, type_result, unit_result);
//    }
//
//    public RetType forTypeArg(TypeArg that) {
//        RetType type_result = that.getType().accept(this);
//        return forTypeArgOnly(that, type_result);
//    }
//
//    public RetType forIntArg(IntArg that) {
//        RetType val_result = that.getVal().accept(this);
//        return forIntArgOnly(that, val_result);
//    }
//
//    public RetType forBoolArg(BoolArg that) {
//        RetType bool_result = that.getBool().accept(this);
//        return forBoolArgOnly(that, bool_result);
//    }
//
//    public RetType forOpArg(OpArg that) {
//        RetType name_result = that.getName().accept(this);
//        return forOpArgOnly(that, name_result);
//    }
//
//    public RetType forDimArg(DimArg that) {
//        RetType dim_result = that.getDim().accept(this);
//        return forDimArgOnly(that, dim_result);
//    }
//
//    public RetType forUnitArg(UnitArg that) {
//        RetType unit_result = that.getUnit().accept(this);
//        return forUnitArgOnly(that, unit_result);
//    }
//
//    public RetType for_RewriteImplicitType(_RewriteImplicitType that) {
//        return for_RewriteImplicitTypeOnly(that);
//    }
//
//    public RetType for_RewriteIntersectionType(_RewriteIntersectionType that) {
//        List<RetType> elements_result = recurOnListOfType(that.getElements());
//        return for_RewriteIntersectionTypeOnly(that, elements_result);
//    }
//
//    public RetType for_RewriteUnionType(_RewriteUnionType that) {
//        List<RetType> elements_result = recurOnListOfType(that.getElements());
//        return for_RewriteUnionTypeOnly(that, elements_result);
//    }
//
//    public RetType for_RewriteFixedPointType(_RewriteFixedPointType that) {
//        RetType var_result = that.getVar().accept(this);
//        RetType body_result = that.getBody().accept(this);
//        return for_RewriteFixedPointTypeOnly(that, var_result, body_result);
//    }
//
//    public RetType forNumberConstraint(NumberConstraint that) {
//        RetType val_result = that.getVal().accept(this);
//        return forNumberConstraintOnly(that, val_result);
//    }
//
//    public RetType forIntRef(IntRef that) {
//        RetType name_result = that.getName().accept(this);
//        return forIntRefOnly(that, name_result);
//    }
//
//    public RetType forSumConstraint(SumConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forSumConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forMinusConstraint(MinusConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forMinusConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forProductConstraint(ProductConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forProductConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forExponentConstraint(ExponentConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forExponentConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forBoolConstant(BoolConstant that) {
//        return forBoolConstantOnly(that);
//    }
//
//    public RetType forBoolRef(BoolRef that) {
//        RetType name_result = that.getName().accept(this);
//        return forBoolRefOnly(that, name_result);
//    }
//
//    public RetType forNotConstraint(NotConstraint that) {
//        RetType bool_result = that.getBool().accept(this);
//        return forNotConstraintOnly(that, bool_result);
//    }
//
//    public RetType forOrConstraint(OrConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forOrConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forAndConstraint(AndConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forAndConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forImpliesConstraint(ImpliesConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forImpliesConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forBEConstraint(BEConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forBEConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forBaseDim(BaseDim that) {
//        return forBaseDimOnly(that);
//    }
//
//    public RetType forDimRef(DimRef that) {
//        RetType name_result = that.getName().accept(this);
//        return forDimRefOnly(that, name_result);
//    }
//
//    public RetType forProductDim(ProductDim that) {
//        RetType multiplier_result = that.getMultiplier().accept(this);
//        RetType multiplicand_result = that.getMultiplicand().accept(this);
//        return forProductDimOnly(that, multiplier_result, multiplicand_result);
//    }
//
//    public RetType forQuotientDim(QuotientDim that) {
//        RetType numerator_result = that.getNumerator().accept(this);
//        RetType denominator_result = that.getDenominator().accept(this);
//        return forQuotientDimOnly(that, numerator_result, denominator_result);
//    }
//
//    public RetType forExponentDim(ExponentDim that) {
//        RetType base_result = that.getBase().accept(this);
//        RetType power_result = that.getPower().accept(this);
//        return forExponentDimOnly(that, base_result, power_result);
//    }
//
//    public RetType forOpDim(OpDim that) {
//        RetType val_result = that.getVal().accept(this);
//        RetType op_result = that.getOp().accept(this);
//        return forOpDimOnly(that, val_result, op_result);
//    }
//
//    public RetType forWhereType(WhereType that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> supers_result = recurOnListOfBaseType(that.getSupers());
//        return forWhereTypeOnly(that, name_result, supers_result);
//    }
//
//    public RetType forWhereNat(WhereNat that) {
//        RetType name_result = that.getName().accept(this);
//        return forWhereNatOnly(that, name_result);
//    }
//
//    public RetType forWhereInt(WhereInt that) {
//        RetType name_result = that.getName().accept(this);
//        return forWhereIntOnly(that, name_result);
//    }
//
//    public RetType forWhereBool(WhereBool that) {
//        RetType name_result = that.getName().accept(this);
//        return forWhereBoolOnly(that, name_result);
//    }
//
//    public RetType forWhereUnit(WhereUnit that) {
//        RetType name_result = that.getName().accept(this);
//        return forWhereUnitOnly(that, name_result);
//    }
//
//    public RetType forWhereExtends(WhereExtends that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> supers_result = recurOnListOfBaseType(that.getSupers());
//        return forWhereExtendsOnly(that, name_result, supers_result);
//    }
//
//    public RetType forTypeAlias(TypeAlias that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> staticParams_result = recurOnListOfStaticParam(that.getStaticParams());
//        RetType type_result = that.getType().accept(this);
//        return forTypeAliasOnly(that, name_result, staticParams_result, type_result);
//    }
//
//    public RetType forWhereCoerces(WhereCoerces that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forWhereCoercesOnly(that, left_result, right_result);
//    }
//
//    public RetType forWhereWidens(WhereWidens that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forWhereWidensOnly(that, left_result, right_result);
//    }
//
//    public RetType forWhereWidensCoerces(WhereWidensCoerces that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forWhereWidensCoercesOnly(that, left_result, right_result);
//    }
//
//    public RetType forWhereEquals(WhereEquals that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forWhereEqualsOnly(that, left_result, right_result);
//    }
//
//    public RetType forUnitConstraint(UnitConstraint that) {
//        RetType name_result = that.getName().accept(this);
//        return forUnitConstraintOnly(that, name_result);
//    }
//
//    public RetType forLEConstraint(LEConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forLEConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forLTConstraint(LTConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forLTConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forGEConstraint(GEConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forGEConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forGTConstraint(GTConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forGTConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forIEConstraint(IEConstraint that) {
//        RetType left_result = that.getLeft().accept(this);
//        RetType right_result = that.getRight().accept(this);
//        return forIEConstraintOnly(that, left_result, right_result);
//    }
//
//    public RetType forBoolConstraintExpr(BoolConstraintExpr that) {
//        RetType constraint_result = that.getConstraint().accept(this);
//        return forBoolConstraintExprOnly(that, constraint_result);
//    }
//
//    public RetType forContract(Contract that) {
//        Option<List<RetType>> requires_result = recurOnOptionOfListOfExpr(that.getRequires());
//        Option<List<RetType>> ensures_result = recurOnOptionOfListOfEnsuresClause(that.getEnsures());
//        Option<List<RetType>> invariants_result = recurOnOptionOfListOfExpr(that.getInvariants());
//        return forContractOnly(that, requires_result, ensures_result, invariants_result);
//    }
//
//    public RetType forEnsuresClause(EnsuresClause that) {
//        RetType post_result = that.getPost().accept(this);
//        Option<RetType> pre_result = recurOnOptionOfExpr(that.getPre());
//        return forEnsuresClauseOnly(that, post_result, pre_result);
//    }
//
//    public RetType forModifierAbstract(ModifierAbstract that) {
//        return forModifierAbstractOnly(that);
//    }
//
//    public RetType forModifierAtomic(ModifierAtomic that) {
//        return forModifierAtomicOnly(that);
//    }
//
//    public RetType forModifierGetter(ModifierGetter that) {
//        return forModifierGetterOnly(that);
//    }
//
//    public RetType forModifierHidden(ModifierHidden that) {
//        return forModifierHiddenOnly(that);
//    }
//
//    public RetType forModifierIO(ModifierIO that) {
//        return forModifierIOOnly(that);
//    }
//
//    public RetType forModifierOverride(ModifierOverride that) {
//        return forModifierOverrideOnly(that);
//    }
//
//    public RetType forModifierPrivate(ModifierPrivate that) {
//        return forModifierPrivateOnly(that);
//    }
//
//    public RetType forModifierSettable(ModifierSettable that) {
//        return forModifierSettableOnly(that);
//    }
//
//    public RetType forModifierSetter(ModifierSetter that) {
//        return forModifierSetterOnly(that);
//    }
//
//    public RetType forModifierTest(ModifierTest that) {
//        return forModifierTestOnly(that);
//    }
//
//    public RetType forModifierTransient(ModifierTransient that) {
//        return forModifierTransientOnly(that);
//    }
//
//    public RetType forModifierValue(ModifierValue that) {
//        return forModifierValueOnly(that);
//    }
//
//    public RetType forModifierVar(ModifierVar that) {
//        return forModifierVarOnly(that);
//    }
//
//    public RetType forModifierWidens(ModifierWidens that) {
//        return forModifierWidensOnly(that);
//    }
//
//    public RetType forModifierWrapped(ModifierWrapped that) {
//        return forModifierWrappedOnly(that);
//    }
//
//    public RetType forOpParam(OpParam that) {
//        RetType name_result = that.getName().accept(this);
//        return forOpParamOnly(that, name_result);
//    }
//
//    public RetType forBoolParam(BoolParam that) {
//        RetType name_result = that.getName().accept(this);
//        return forBoolParamOnly(that, name_result);
//    }
//
//    public RetType forDimParam(DimParam that) {
//        RetType name_result = that.getName().accept(this);
//        return forDimParamOnly(that, name_result);
//    }
//
//    public RetType forIntParam(IntParam that) {
//        RetType name_result = that.getName().accept(this);
//        return forIntParamOnly(that, name_result);
//    }
//
//    public RetType forNatParam(NatParam that) {
//        RetType name_result = that.getName().accept(this);
//        return forNatParamOnly(that, name_result);
//    }
//
//    public RetType forUnitParam(UnitParam that) {
//        RetType name_result = that.getName().accept(this);
//        Option<RetType> dim_result = recurOnOptionOfDimExpr(that.getDim());
//        return forUnitParamOnly(that, name_result, dim_result);
//    }
//
//    public RetType forAPIName(APIName that) {
//        List<RetType> ids_result = recurOnListOfId(that.getIds());
//        return forAPINameOnly(that, ids_result);
//    }
//
//    public RetType forId(Id that) {
//        Option<RetType> api_result = recurOnOptionOfAPIName(that.getApi());
//        RetType name_result = that.getName().accept(this);
//        return forIdOnly(that, api_result, name_result);
//    }
//
//    public RetType forOp(Op that) {
//        return forOpOnly(that);
//    }
//
//    public RetType forEnclosing(Enclosing that) {
//        RetType open_result = that.getOpen().accept(this);
//        RetType close_result = that.getClose().accept(this);
//        return forEnclosingOnly(that, open_result, close_result);
//    }
//
//    public RetType forAnonymousFnName(AnonymousFnName that) {
//        return forAnonymousFnNameOnly(that);
//    }
//
//    public RetType forConstructorFnName(ConstructorFnName that) {
//        RetType def_result = that.getDef().accept(this);
//        return forConstructorFnNameOnly(that, def_result);
//    }
//
//    public RetType forArrayComprehensionClause(ArrayComprehensionClause that) {
//        List<RetType> bind_result = recurOnListOfExpr(that.getBind());
//        RetType init_result = that.getInit().accept(this);
//        List<RetType> gens_result = recurOnListOfGeneratorClause(that.getGens());
//        return forArrayComprehensionClauseOnly(that, bind_result, init_result, gens_result);
//    }
//
//    public RetType forKeywordExpr(KeywordExpr that) {
//        RetType name_result = that.getName().accept(this);
//        RetType init_result = that.getInit().accept(this);
//        return forKeywordExprOnly(that, name_result, init_result);
//    }
//
//    public RetType forCaseClause(CaseClause that) {
//        RetType match_result = that.getMatch().accept(this);
//        RetType body_result = that.getBody().accept(this);
//        return forCaseClauseOnly(that, match_result, body_result);
//    }
//
//    public RetType forCatch(Catch that) {
//        RetType name_result = that.getName().accept(this);
//        List<RetType> clauses_result = recurOnListOfCatchClause(that.getClauses());
//        return forCatchOnly(that, name_result, clauses_result);
//    }
//
//    public RetType forCatchClause(CatchClause that) {
//        RetType match_result = that.getMatch().accept(this);
//        RetType body_result = that.getBody().accept(this);
//        return forCatchClauseOnly(that, match_result, body_result);
//    }
//
//    public RetType forDoFront(DoFront that) {
//        Option<RetType> loc_result = recurOnOptionOfExpr(that.getLoc());
//        RetType expr_result = that.getExpr().accept(this);
//        return forDoFrontOnly(that, loc_result, expr_result);
//    }
//
//    public RetType forIfClause(IfClause that) {
//        RetType test_result = that.getTest().accept(this);
//        RetType body_result = that.getBody().accept(this);
//        return forIfClauseOnly(that, test_result, body_result);
//    }
//
//    public RetType forTypecaseClause(TypecaseClause that) {
//        List<RetType> match_result = recurOnListOfType(that.getMatch());
//        RetType body_result = that.getBody().accept(this);
//        return forTypecaseClauseOnly(that, match_result, body_result);
//    }
//
//    public RetType forExtentRange(ExtentRange that) {
//        Option<RetType> base_result = recurOnOptionOfStaticArg(that.getBase());
//        Option<RetType> size_result = recurOnOptionOfStaticArg(that.getSize());
//        return forExtentRangeOnly(that, base_result, size_result);
//    }
//
//    public RetType forGeneratorClause(GeneratorClause that) {
//        List<RetType> bind_result = recurOnListOfId(that.getBind());
//        RetType init_result = that.getInit().accept(this);
//        return forGeneratorClauseOnly(that, bind_result, init_result);
//    }
//
//    public RetType forVarargsExpr(VarargsExpr that) {
//        RetType varargs_result = that.getVarargs().accept(this);
//        return forVarargsExprOnly(that, varargs_result);
//    }
//
//    public RetType forKeywordType(KeywordType that) {
//        RetType name_result = that.getName().accept(this);
//        RetType type_result = that.getType().accept(this);
//        return forKeywordTypeOnly(that, name_result, type_result);
//    }
//
//    public RetType forTraitTypeWhere(TraitTypeWhere that) {
//        RetType type_result = that.getType().accept(this);
//        RetType where_result = that.getWhere().accept(this);
//        return forTraitTypeWhereOnly(that, type_result, where_result);
//    }
//
//    public RetType forIndices(Indices that) {
//        List<RetType> extents_result = recurOnListOfExtentRange(that.getExtents());
//        return forIndicesOnly(that, extents_result);
//    }
//
//    public RetType forSquareDimUnit(SquareDimUnit that) {
//        return forSquareDimUnitOnly(that);
//    }
//
//    public RetType forCubicDimUnit(CubicDimUnit that) {
//        return forCubicDimUnitOnly(that);
//    }
//
//    public RetType forInverseDimUnit(InverseDimUnit that) {
//        return forInverseDimUnitOnly(that);
//    }
//
//    public RetType forParenthesisDelimitedMI(ParenthesisDelimitedMI that) {
//        RetType expr_result = that.getExpr().accept(this);
//        return forParenthesisDelimitedMIOnly(that, expr_result);
//    }
//
//    public RetType forNonParenthesisDelimitedMI(NonParenthesisDelimitedMI that) {
//        RetType expr_result = that.getExpr().accept(this);
//        return forNonParenthesisDelimitedMIOnly(that, expr_result);
//    }
//
//    public RetType forExponentiationMI(ExponentiationMI that) {
//        RetType op_result = that.getOp().accept(this);
//        Option<RetType> expr_result = recurOnOptionOfExpr(that.getExpr());
//        return forExponentiationMIOnly(that, op_result, expr_result);
//    }
//
//    public RetType forSubscriptingMI(SubscriptingMI that) {
//        RetType op_result = that.getOp().accept(this);
//        List<RetType> exprs_result = recurOnListOfExpr(that.getExprs());
//        return forSubscriptingMIOnly(that, op_result, exprs_result);
//    }
