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

import java.util.List;

import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.types.FTypeObject;
import com.sun.fortress.interpreter.evaluator.values.FFloat;
import com.sun.fortress.interpreter.evaluator.values.FFloatLiteral;
import com.sun.fortress.interpreter.evaluator.values.FObject;
import com.sun.fortress.interpreter.evaluator.values.FString;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.interpreter.evaluator.values.NativeConstructor;
import com.sun.fortress.interpreter.glue.NativeMeth0;
import com.sun.fortress.nodes.ObjectConstructor;

public class FloatLiteral extends NativeConstructor {

public FloatLiteral(Environment env, FTypeObject selfType, ObjectConstructor def) {
    super(env, selfType, def);
}

protected FNativeObject makeNativeObject(List<FValue> args,
                                         NativeConstructor con) {
    FFloatLiteral.setConstructor(this);
    return FFloatLiteral.ZERO;
}

static private abstract class Rlit2S extends NativeMeth0 {
    protected abstract java.lang.String f(java.lang.String x);
    public final FValue applyMethod(FObject x) {
        return FString.make(f(x.getString()));
    }
}
static private abstract class Rlit2R extends NativeMeth0 {
    protected abstract double f(double x);
    public final FFloat applyMethod(FObject x) {
        return FFloat.make(f(x.getFloat()));
    }
}
public static final class ToString extends Rlit2S {
    protected java.lang.String f(java.lang.String x) { return x; }
}
public static final class AsFloat extends Rlit2R {
    protected double f(double x) { return x; }
}
@Override
protected void unregister() {
    FFloatLiteral.resetConstructor();

}

}
