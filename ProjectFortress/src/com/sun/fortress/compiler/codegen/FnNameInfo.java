/*******************************************************************************
    Copyright 2009,2012, Oracle and/or its affiliates.
    All rights reserved.


    Use is subject to license terms.

    This distribution may include materials developed by third parties.

******************************************************************************/
package com.sun.fortress.compiler.codegen;

import java.util.List;

import com.sun.fortress.compiler.NamingCzar;
import com.sun.fortress.compiler.index.Functional;
import com.sun.fortress.compiler.index.HasTraitStaticParameters;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.ArrowType;
import com.sun.fortress.nodes.FnDecl;
import com.sun.fortress.nodes.FnHeader;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.IdOrOp;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.TupleType;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.runtimeSystem.Naming;
import com.sun.fortress.useful.ConcatenatedList;
import com.sun.fortress.useful.DeletedList;
import com.sun.fortress.useful.InsertedList;
import com.sun.fortress.useful.Useful;

public class FnNameInfo {
    final List<StaticParam> static_params;
    final List<StaticParam> trait_static_params;
    final Type returnType;
    final Type paramType;
    final APIName ifNone;
    final IdOrOp name;
    final Span span;
    
    public FnNameInfo(List<StaticParam> static_params,
            List<StaticParam> trait_static_params, Type returnType,
            Type paramType, APIName ifNone, IdOrOp name, Span span) {
        
        super();
        this.static_params = static_params;
        this.trait_static_params = trait_static_params;
        this.returnType = returnType;
        this.paramType = paramType;
        this.ifNone = ifNone;
        this.name = name;
        this.span = span;
    }
    
    public FnNameInfo convertGenericMethodToClosureDecl(int self_index,
            List<StaticParam> to_static_params) {
        Id sp_name = NodeFactory.makeId(span, Naming.UP_INDEX);
        Id p_name = NamingCzar.selfName(span);
        StaticParam new_sp = NodeFactory.makeTypeParam(span, Naming.UP_INDEX);
        Type inserted_param_type = NodeFactory.makeVarType(span, sp_name);
        // convert paramType to list of types, remove self, add inserted_param_type at zero.
        List<Type> param_types = paramType instanceof TupleType ?
                ((TupleType) paramType).getElements() : Useful.list(paramType);
        if (self_index != Naming.NO_SELF)
            param_types = new DeletedList<Type>(param_types, self_index);
        param_types = new InsertedList<Type>(param_types, 0, inserted_param_type);
        Type new_param_type = param_types.size() == 1 ?
                param_types.get(0) :
                    NodeFactory.makeTupleType(span, param_types);
        // edit static params -- new_sp, method_sp, trait_sp
        List<StaticParam> new_sparams =
            new ConcatenatedList<StaticParam>(
                    new InsertedList<StaticParam>(static_params, 0, new_sp),
                to_static_params);
        // This use of trait_static_params is perhaps a little dubious; we'll see.
        return new FnNameInfo(new_sparams, trait_static_params, returnType, new_param_type,ifNone, name, span);
    }

    public FnNameInfo(FnDecl x, List<StaticParam> trait_static_params, APIName ifNone) {
        FnHeader header = x.getHeader();

        static_params = x.getHeader().getStaticParams();
        this.trait_static_params = trait_static_params;
        returnType = header.getReturnType().unwrap();
        paramType = NodeUtil.getParamType(x);
        this.ifNone = ifNone;
        span = NodeUtil.getSpan(x);
        name = (IdOrOp) (x.getHeader().getName());
    }
    
    public FnNameInfo(Functional x, APIName ifNone) {
        HasTraitStaticParameters htsp = (HasTraitStaticParameters) x;
        trait_static_params = htsp.traitStaticParameters();
        static_params = x.staticParameters();
        ArrowType at = CodeGen.fndeclToType(x, Naming.NO_SELF);
        returnType = at.getRange();
        paramType = at.getDomain();
        this.ifNone = ifNone;
        span =x.getSpan();
        name = x.name();
    }
}
