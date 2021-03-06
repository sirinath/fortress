/*******************************************************************************
 Copyright 2008,2009, Oracle and/or its affiliates.
 All rights reserved.


 Use is subject to license terms.

 This distribution may include materials developed by third parties.

 ******************************************************************************/

package com.sun.fortress.parser_util.precedence_opexpr;


public interface PrecedenceOpExpr {

    public <RetType> RetType accept(OpExprVisitor<RetType> visitor);

    public void accept(OpExprVisitor_void visitor);

    public void outputHelp(TabPrintWriter writer);
}
