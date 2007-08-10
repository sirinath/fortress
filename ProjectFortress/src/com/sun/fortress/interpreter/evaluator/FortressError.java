/*******************************************************************************
    Copyright 2007 Sun Microsystems, Inc.,
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

package com.sun.fortress.interpreter.evaluator;

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;

import com.sun.fortress.nodes.AbstractNode;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.useful.HasAt;

import static com.sun.fortress.nodes_util.ErrorMsgMaker.makeErrorMsg;

public abstract class FortressError extends Error {

    /**
     * Make Eclipse happy
     */
    private static final long serialVersionUID = 6117319678737763137L;

    HasAt where;
    HasAt where2;
    Environment within;

    public static String errorMsg(Object... messages) {
        StringBuffer fullMessage = new StringBuffer();
        for (Object message : messages) {
            if (message instanceof AbstractNode) {
                fullMessage.append(makeErrorMsg((AbstractNode)message));
            }
            else {
                fullMessage.append(message.toString());
            }
        }
        return fullMessage.toString();
    }

    public FortressError setWhere(HasAt where) {
        this.where = where;
        return this;
    }

    public FortressError setWhere2(HasAt where2) {
        this.where2 = where2;
        return this;
        }

    public FortressError setWithin(Environment within) {
        this.within = within;
        return this;
        }

    public FortressError() {
        super();

    }

    public FortressError(HasAt loc, Environment env, String arg0) {
        super(arg0);
        where = loc; within = env;

    }

    public FortressError(HasAt loc, String arg0) {
        super(arg0);
        where = loc;

    }

    public FortressError(HasAt loc1, HasAt loc2, Environment env, String arg0) {
        super(arg0);
        where = loc1; where2 = loc2; within = env;

    }

    public FortressError(String arg0) {
        super(arg0);

    }

    public FortressError(String arg0, Throwable arg1) {
        super(arg0, arg1);

    }

    public FortressError(HasAt loc, Environment env, String arg0, Throwable arg1) {
        super(arg0, arg1);
        where = loc; within = env;

    }

    public FortressError(Throwable arg0) {
        super(arg0);

    }

    public FortressError(HasAt loc, String string, Throwable ex) {
        this(loc, null, string, ex);
    }

    /* (non-Javadoc)
     * @see java.lang.Throwable#getMessage()
     */
    @Override
    public String getMessage() {
        return (where == null ? "" : ("\n"+where.at() + (where2 == null ? "" : (": and\n" + where2.at()) ) + ": ")) +
        super.getMessage();
    }

    /* (non-Javadoc)
     * @see java.lang.Throwable#printStackTrace()
     */
    @Override
    public void printStackTrace() {
        // TODO Auto-generated method stub
        super.printStackTrace();
    }

    /**
     *
     */
    private void printInterpreterStackTrace(PrintWriter app) {
        if (within != null) {
            try {
            within.dump(app);
            } catch (IOException ex) {
                app.println("Error dumping interpreter environment");
                ex.printStackTrace(app);
            }
        }
    }

    /**
     *
     */
    public void printInterpreterStackTrace(PrintStream app) {
        if (within != null) {
            try {
            within.dump(app);
            } catch (IOException ex) {
                app.println("Error dumping interpreter environment");
                ex.printStackTrace(app);
            }
        }
    }

    /* (non-Javadoc)
     * @see java.lang.Throwable#printStackTrace(java.io.PrintStream)
     */
    @Override
    public void printStackTrace(PrintStream arg0) {
        // TODO Auto-generated method stub
        super.printStackTrace(arg0);
        printInterpreterStackTrace(arg0);
    }

    /* (non-Javadoc)
     * @see java.lang.Throwable#printStackTrace(java.io.PrintWriter)
     */
    @Override
    public void printStackTrace(PrintWriter arg0) {
        // TODO Auto-generated method stub
        super.printStackTrace(arg0);
        printInterpreterStackTrace(arg0);
    }

}
