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

package com.sun.fortress.interpreter.glue.prim;

import static com.sun.fortress.exceptions.ProgramError.error;
import static com.sun.fortress.exceptions.ProgramError.errorMsg;

import java.util.List;

import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.types.FTypeObject;
import com.sun.fortress.interpreter.evaluator.values.FBool;
import com.sun.fortress.interpreter.evaluator.values.FObject;
import com.sun.fortress.interpreter.evaluator.values.FFloat;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.interpreter.evaluator.values.NativeConstructor;
import com.sun.fortress.interpreter.glue.NativeMeth1;
import com.sun.fortress.interpreter.glue.NativeMeth2;
import com.sun.fortress.nodes.GenericWithParams;

public class PrimImmutableRR64Array extends NativeConstructor {
    int s0;

    public PrimImmutableRR64Array(Environment env,
                              FTypeObject selfType,
                              GenericWithParams def) {
        super(env, selfType, def);
        // TODO Auto-generated constructor stub
    }

    protected FNativeObject makeNativeObject(List<FValue> args, NativeConstructor con) {
        return new PrimImmutableRR64ArrayObject(con,s0);
    }

    protected void oneTimeInit(Environment self_env) {
        s0 = self_env.getValue("s0").getInt();
    }

    public static final class PrimImmutableRR64ArrayObject extends FNativeObject {
        protected final double[] contents;
        protected final NativeConstructor con;

        public PrimImmutableRR64ArrayObject(NativeConstructor con, int capacity) {
            super(con);
            this.con = con;
            this.contents = new double[capacity];
        }

        public NativeConstructor getConstructor() {
            return con;
        }

        public boolean seqv(FValue other) {
            return this==other;
        }

        public double get(int i) {
            return contents[i];
        }

        public boolean init(int i, double v) {
            double old = contents[i];
            contents[i] = v;
            return true;
        }
    }

    private static abstract class vi2R extends NativeMeth1 {
        protected abstract double f(PrimImmutableRR64ArrayObject v, int i);
        public FValue act(FObject self, FValue ii) {
            PrimImmutableRR64ArrayObject v = (PrimImmutableRR64ArrayObject) self;
            int i = ii.getInt();
            return FFloat.make(f(v,i));
        }
    }

    private static abstract class vio2B extends NativeMeth2 {
        protected abstract boolean f(PrimImmutableRR64ArrayObject v, int i, double x);
        public FValue act(FObject self, FValue ii, FValue x) {
            PrimImmutableRR64ArrayObject v = (PrimImmutableRR64ArrayObject) self;
            int i = ii.getInt();
            return FBool.make(f(v,i,x.getFloat()));
        }
    }

    public static final class get extends vi2R {
        protected double f(PrimImmutableRR64ArrayObject v, int i) {
            double r = v.get(i);
            return r;
        }
    }

    public static final class init0 extends vio2B {
        protected boolean f(PrimImmutableRR64ArrayObject v, int i, double x) {
            return v.init(i,x);
        }
    }

}
