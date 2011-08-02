/*
 * Created on Aug 1, 2011
 *
 */
package com.sun.fortress.runtimeSystem;

import com.sun.fortress.compiler.runtimeValues.RTTI;
import com.sun.fortress.useful.Useful;

public class RTHelpers {
    static public Class getRTTIclass(String stem, RTTI[] params) {
        
        StringBuilder classNameBuf = new StringBuilder(stem + Naming.LEFT_OXFORD);
        for (int i = 0; i < params.length - 1; i++) {
            classNameBuf.append(params[i].className() + ";");
        }
        classNameBuf.append(params[params.length-1].className() + Naming.RIGHT_OXFORD);
        
        String mangledClassName = Naming.mangleFortressIdentifier(classNameBuf.toString());
        String mangledDots = Naming.sepToDot(mangledClassName);
        
        try {
            return Class.forName(mangledDots); //ONLY.loadClass(Naming.sepToDot(mangledClassName), false);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException("class " + mangledClassName.toString() + " failed to load");
        }
    }
    
    static public Class getRTTIclass(String stem, RTTI param1) {
        RTTI[] params = { param1 };
        return getRTTIclass(stem, params);
    }
    
    static public Class getRTTIclass(String stem, RTTI param1, RTTI param2) {
        RTTI[] params = { param1, param2 };
        return getRTTIclass(stem, params);
    }
    
    static public Class getRTTIclass(String stem, RTTI param1, RTTI param2, RTTI param3) {
        RTTI[] params = { param1, param2, param3 };
        return getRTTIclass(stem, params);
    }
    
    static public Class getRTTIclass(String stem, RTTI param1, RTTI param2, RTTI param3, RTTI param4) {
        RTTI[] params = { param1, param2, param3, param4 };
        return getRTTIclass(stem, params);
    }
    
    static public Class getRTTIclass(String stem, RTTI param1, RTTI param2, RTTI param3, RTTI param4, RTTI param5) {
        RTTI[] params = { param1, param2, param3, param4, param5 };
        return getRTTIclass(stem, params);
    }

    static public Class getRTTIclass(String stem, RTTI param1, RTTI param2, RTTI param3, RTTI param4, RTTI param5, RTTI param6) {
        RTTI[] params = { param1, param2, param3, param4, param5, param6 };
        return getRTTIclass(stem, params);
    }

    //generic method in non-generic trait
    static public Object findGenericMethodClosure(long l, BAlongTree t, String tcn, String sig) {
        if (InstantiatingClassloader.LOG_LOADS)
            System.err.println("findGenericMethodClosure("+l+", t, " + tcn +", " + sig +")");
    
        int up_index = tcn.indexOf(Naming.UP_INDEX);
        int envelope = tcn.indexOf(Naming.ENVELOPE); // Preceding char is RIGHT_OXFORD;
        int begin_static_params = tcn.indexOf(Naming.LEFT_OXFORD, up_index);
        // int gear_index = tcn.indexOf(Naming.GEAR);
        // String self_class = tcn.substring(0,gear_index) + tcn.substring(gear_index+1,up_index);
        
        String class_we_want = tcn.substring(0,begin_static_params+1) + // self_class + ";" +
            sig.substring(1) + tcn.substring(envelope);
        class_we_want = Naming.mangleFortressIdentifier(class_we_want);
        return RTHelpers.loadClosureClass(l, t, class_we_want);
    }

    //generic method in generic trait
    static public Object findGenericMethodClosure(long l, BAlongTree t,
            String tcn, String sig, String trait_sig) {
        if (InstantiatingClassloader.LOG_LOADS)
            System.err.println("findGenericMethodClosure("+l+", t, " + tcn +
                    ", " + sig +", " + trait_sig + ")");
    
        int up_index = tcn.indexOf(Naming.UP_INDEX);
        int envelope = tcn.indexOf(Naming.ENVELOPE); // Preceding char is RIGHT_OXFORD;
        int begin_static_params = tcn.indexOf(Naming.LEFT_OXFORD, up_index);
        // int gear_index = tcn.indexOf(Naming.GEAR);
        // String self_class = tcn.substring(0,gear_index) + tcn.substring(gear_index+1,up_index);
        
        String class_we_want = tcn.substring(0,begin_static_params+1) + // self_class + ";" +
            Useful.substring(sig,1,-1) + ";" + trait_sig.substring(1) + tcn.substring(envelope);
        class_we_want = Naming.mangleFortressIdentifier(class_we_want);
        return RTHelpers.loadClosureClass(l, t, class_we_want);
    }

    /**
     * @param l
     * @param t
     * @param class_we_want
     * @throws Error
     */
    static Object loadClosureClass(long l, BAlongTree t,
            String class_we_want) throws Error {
        Class cl;
        try {
            cl = Class.forName(class_we_want);
            synchronized (t) {
                Object o = t.get(l);
                if (o == null) {
                    o = cl.newInstance();
                    t.put(l,o);
                }
                return o;
            }
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        
        throw new Error("Not supposed to happen; some template class must be missing.");
    }

    public static Object loadClosureClass(String stem, RTTI[] params) {
        StringBuilder paramList = new StringBuilder(Naming.LEFT_OXFORD);
        for (int i = 0; i < params.length-1; i++) paramList.append(params[i].className() + ",");
        paramList.append(params[params.length-1].className() + Naming.RIGHT_OXFORD);
        int insertLoc = stem.indexOf(Naming.LEFT_OXFORD + Naming.RIGHT_OXFORD);
        String className = stem.substring(0,insertLoc) + paramList.toString() + stem.substring(insertLoc+2);
        String class_we_want = Naming.sepToDot(Naming.mangleFortressIdentifier(className));
        Class cl;
        try {
            cl = Class.forName(class_we_want); //ONLY.loadClass(Naming.sepToDot(class_we_want), false);
            return cl.newInstance();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            throw new RuntimeException("class " + class_we_want + " failed to load");
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
        
        throw new Error("Not supposed to happen; some template class must be missing.");
    }

    public static Object loadClosureClass(String stem, RTTI param1) {
        RTTI[] params = { param1 };
        Object cclass = loadClosureClass(stem, params); 
        return cclass;
    }

    public static Object loadClosureClass(String stem, RTTI param1, RTTI param2) {
        RTTI[] params = { param1, param2 };
        return loadClosureClass(stem, params);
    }

    public static Object loadClosureClass(String stem, RTTI param1, RTTI param2, RTTI param3) {
        RTTI[] params = { param1, param2, param3 };
        return loadClosureClass(stem, params);
    }

    public static Object loadClosureClass(String stem, RTTI param1, RTTI param2, RTTI param3, RTTI param4) {
        RTTI[] params = { param1, param2, param3, param4 };
        return loadClosureClass(stem, params);
    }

    public static Object loadClosureClass(String stem, RTTI param1, RTTI param2, RTTI param3, RTTI param4, RTTI param5) {
        RTTI[] params = { param1, param2, param3, param4, param5 };
        return loadClosureClass(stem, params);
    }

    public static Object loadClosureClass(String stem, RTTI param1, RTTI param2, RTTI param3, RTTI param4, RTTI param5, RTTI param6) {
        RTTI[] params = { param1, param2, param3, param4, param5, param6 };
        return loadClosureClass(stem, params);
    }
}
