/*******************************************************************************
    Copyright 2009,2011, Oracle and/or its affiliates.
    All rights reserved.


    Use is subject to license terms.

    This distribution may include materials developed by third parties.

 ******************************************************************************/

package com.sun.fortress.compiler.runtimeValues;

public class FRR32  extends fortress.CompilerBuiltin.RR32.DefaultTraitMethods
implements fortress.CompilerBuiltin.RR32, fortress.CompilerBuiltin.RR64 {
    final float val;

    private FRR32(float x) { val = x; }
    public String toString() { return "" + val;}
    public float getValue() {return val;}
    public static FRR32 make(float x) {return new FRR32(x);}
    
    public static class RTTIc extends RTTI {
        public static final RTTIc ONLY = new RTTIc();
    }

}
