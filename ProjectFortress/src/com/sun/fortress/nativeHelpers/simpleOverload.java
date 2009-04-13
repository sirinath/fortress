/*******************************************************************************
    Copyright 2009 Sun Microsystems, Inc.,
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

package com.sun.fortress.nativeHelpers;

public class simpleOverload {
    
    public static void foo(int i, int j, int k, int l) {
        System.out.println("" + i + " " + j + " " + k + " " + l);
    }

    public static void foo(int i, int j, long k, int l) {
        System.out.println("" + i + " " + j + " " + k + " " + l);
    }

    public static void foo(int i, int j, int k, long l) {
        System.out.println("" + i + " " + j + " " + k + " " + l);
    }

    public static void foo(int i, int j, float k, float l) {
        System.out.println("" + i + " " + j + " " + k + " " + l);
    }

    public static void foo(int i, int j, double k, double l) {
        System.out.println("" + i + " " + j + " " + k + " " + l);
    }

    public static void foo(int i, String j, double k, double l) {
        System.out.println("" + i + " " + j + " " + k + " " + l);
    }

    public static String bar() {
        return "bar";
    }
    
}